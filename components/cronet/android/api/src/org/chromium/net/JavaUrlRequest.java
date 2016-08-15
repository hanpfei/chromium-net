// Copyright 2015 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.net;

import android.annotation.TargetApi;
import android.net.TrafficStats;
import android.os.Build;
import android.util.Log;

import java.io.Closeable;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Pure java UrlRequest, backed by {@link HttpURLConnection}.
 */
@TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH) // TrafficStats only available on ICS
final class JavaUrlRequest implements UrlRequest {
    private static final String X_ANDROID = "X-Android";
    private static final String X_ANDROID_SELECTED_TRANSPORT = "X-Android-Selected-Transport";
    private static final String TAG = "JavaUrlConnection";
    private static final int DEFAULT_UPLOAD_BUFFER_SIZE = 8192;
    private static final int DEFAULT_CHUNK_LENGTH = DEFAULT_UPLOAD_BUFFER_SIZE;
    private static final String USER_AGENT = "User-Agent";
    private final AsyncUrlRequestCallback mCallbackAsync;
    private final Executor mExecutor;
    private final String mUserAgent;
    private final Map<String, String> mRequestHeaders =
            new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final List<String> mUrlChain = new ArrayList<>();
    /**
     * This is the source of thread safety in this class - no other synchronization is performed.
     * By compare-and-swapping from one state to another, we guarantee that operations aren't
     * running concurrently. Only the winner of a CAS proceeds.
     *
     * <p>A caller can lose a CAS for three reasons - user error (two calls to read() without
     * waiting for the read to succeed), runtime error (network code or user code throws an
     * exception), or cancellation.
     */
    private final AtomicReference<State> mState = new AtomicReference<>(State.NOT_STARTED);

    /**
     * Traffic stats tag to associate this requests' data use with. It's captured when the request
     * is created, so that applications doing work on behalf of another app can correctly attribute
     * that data use.
     */
    private final int mTrafficStatsTag;

    /* These don't change with redirects */
    private String mInitialMethod;
    private UploadDataProvider mUploadDataProvider;
    private Executor mUploadExecutor;
    private AtomicBoolean mUploadProviderClosed = new AtomicBoolean(false);

    /**
     * Holds a subset of StatusValues - {@link State#STARTED} can represent
     * {@link Status#SENDING_REQUEST} or {@link Status#WAITING_FOR_RESPONSE}. While the distinction
     * isn't needed to implement the logic in this class, it is needed to implement
     * {@link #getStatus(StatusListener)}.
     *
     * <p>Concurrency notes - this value is not atomically updated with mState, so there is some
     * risk that we'd get an inconsistent snapshot of both - however, it also happens that this
     * value is only used with the STARTED state, so it's inconsequential.
     */
    @Status.StatusValues private volatile int mAdditionalStatusDetails = Status.INVALID;

    /* These change with redirects. */
    private String mCurrentUrl;
    private ReadableByteChannel mResponseChannel;
    private UrlResponseInfo mUrlResponseInfo;
    private String mPendingRedirectUrl;
    /**
     * The happens-before edges created by the executor submission and AtomicReference setting are
     * sufficient to guarantee the correct behavior of this field; however, this is an
     * AtomicReference so that we can cleanly dispose of a new connection if we're cancelled during
     * a redirect, which requires get-and-set semantics.
     * */
    private final AtomicReference<HttpURLConnection> mCurrentUrlConnection =
            new AtomicReference<>();

    /**
     *             /- AWAITING_FOLLOW_REDIRECT <-  REDIRECT_RECEIVED <-\     /- READING <--\
     *             |                                                   |     |             |
     *             \                                                   /     \             /
     * NOT_STARTED --->                   STARTED                       ----> AWAITING_READ --->
     * COMPLETE
     */
    private enum State {
        NOT_STARTED,
        STARTED,
        REDIRECT_RECEIVED,
        AWAITING_FOLLOW_REDIRECT,
        AWAITING_READ,
        READING,
        ERROR,
        COMPLETE,
        CANCELLED,
    }

    /**
     * @param executor The executor used for reading and writing from sockets
     * @param userExecutor The executor used to dispatch to {@code callback}
     */
    JavaUrlRequest(Callback callback, final Executor executor, Executor userExecutor, String url,
            String userAgent) {
        if (url == null) {
            throw new NullPointerException("URL is required");
        }
        if (callback == null) {
            throw new NullPointerException("Listener is required");
        }
        if (executor == null) {
            throw new NullPointerException("Executor is required");
        }
        if (userExecutor == null) {
            throw new NullPointerException("userExecutor is required");
        }
        this.mCallbackAsync = new AsyncUrlRequestCallback(callback, userExecutor);
        this.mTrafficStatsTag = TrafficStats.getThreadStatsTag();
        this.mExecutor = new Executor() {
            @Override
            public void execute(final Runnable command) {
                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        int oldTag = TrafficStats.getThreadStatsTag();
                        TrafficStats.setThreadStatsTag(mTrafficStatsTag);
                        try {
                            command.run();
                        } finally {
                            TrafficStats.setThreadStatsTag(oldTag);
                        }
                    }
                });
            }
        };
        this.mCurrentUrl = url;
        this.mUserAgent = userAgent;
    }

    @Override
    public void setHttpMethod(String method) {
        checkNotStarted();
        if (method == null) {
            throw new NullPointerException("Method is required.");
        }
        if ("OPTIONS".equalsIgnoreCase(method) || "GET".equalsIgnoreCase(method)
                || "HEAD".equalsIgnoreCase(method) || "POST".equalsIgnoreCase(method)
                || "PUT".equalsIgnoreCase(method) || "DELETE".equalsIgnoreCase(method)
                || "TRACE".equalsIgnoreCase(method) || "PATCH".equalsIgnoreCase(method)) {
            mInitialMethod = method;
        } else {
            throw new IllegalArgumentException("Invalid http method " + method);
        }
    }

    private void checkNotStarted() {
        State state = mState.get();
        if (state != State.NOT_STARTED) {
            throw new IllegalStateException("Request is already started. State is: " + state);
        }
    }

    @Override
    public void addHeader(String header, String value) {
        checkNotStarted();
        if (!isValidHeaderName(header) || value.contains("\r\n")) {
            throw new IllegalArgumentException("Invalid header " + header + "=" + value);
        }
        if (mRequestHeaders.containsKey(header)) {
            mRequestHeaders.remove(header);
        }
        mRequestHeaders.put(header, value);
    }

    private boolean isValidHeaderName(String header) {
        for (int i = 0; i < header.length(); i++) {
            char c = header.charAt(i);
            switch (c) {
                case '(':
                case ')':
                case '<':
                case '>':
                case '@':
                case ',':
                case ';':
                case ':':
                case '\\':
                case '\'':
                case '/':
                case '[':
                case ']':
                case '?':
                case '=':
                case '{':
                case '}':
                    return false;
                default: {
                    if (Character.isISOControl(c) || Character.isWhitespace(c)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    @Override
    public void setUploadDataProvider(UploadDataProvider uploadDataProvider, Executor executor) {
        if (uploadDataProvider == null) {
            throw new NullPointerException("Invalid UploadDataProvider.");
        }
        if (!mRequestHeaders.containsKey("Content-Type")) {
            throw new IllegalArgumentException(
                    "Requests with upload data must have a Content-Type.");
        }
        checkNotStarted();
        if (mInitialMethod == null) {
            mInitialMethod = "POST";
        }
        this.mUploadDataProvider = uploadDataProvider;
        this.mUploadExecutor = executor;
    }

    private enum SinkState {
        AWAITING_READ_RESULT,
        AWAITING_REWIND_RESULT,
        UPLOADING,
        NOT_STARTED,
    }

    private final class OutputStreamDataSink implements UploadDataSink {
        final AtomicReference<SinkState> mSinkState = new AtomicReference<>(SinkState.NOT_STARTED);
        final Executor mUserExecutor;
        final Executor mExecutor;
        final HttpURLConnection mUrlConnection;
        WritableByteChannel mOutputChannel;
        final UploadDataProvider mUploadProvider;
        ByteBuffer mBuffer;
        /** This holds the total bytes to send (the content-length). -1 if unknown. */
        long mTotalBytes;
        /** This holds the bytes written so far */
        long mWrittenBytes = 0;

        OutputStreamDataSink(final Executor userExecutor, Executor executor,
                HttpURLConnection urlConnection, UploadDataProvider provider) {
            this.mUserExecutor = new Executor() {
                @Override
                public void execute(Runnable runnable) {
                    try {
                        userExecutor.execute(runnable);
                    } catch (RejectedExecutionException e) {
                        enterUploadErrorState(e);
                    }
                }
            };
            this.mExecutor = executor;
            this.mUrlConnection = urlConnection;
            this.mUploadProvider = provider;
        }

        @Override
        public void onReadSucceeded(final boolean finalChunk) {
            if (!mSinkState.compareAndSet(SinkState.AWAITING_READ_RESULT, SinkState.UPLOADING)) {
                throw new IllegalStateException(
                        "Not expecting a read result, expecting: " + mSinkState.get());
            }
            mExecutor.execute(errorSetting(State.STARTED, new CheckedRunnable() {
                @Override
                public void run() throws Exception {
                    mBuffer.flip();
                    if (mTotalBytes != -1 && mTotalBytes - mWrittenBytes < mBuffer.remaining()) {
                        enterUploadErrorState(new IllegalArgumentException(String.format(
                                "Read upload data length %d exceeds expected length %d",
                                mWrittenBytes + mBuffer.remaining(), mTotalBytes)));
                        return;
                    }
                    while (mBuffer.hasRemaining()) {
                        mWrittenBytes += mOutputChannel.write(mBuffer);
                    }
                    if (mWrittenBytes < mTotalBytes || (mTotalBytes == -1 && !finalChunk)) {
                        mBuffer.clear();
                        mSinkState.set(SinkState.AWAITING_READ_RESULT);
                        mUserExecutor.execute(uploadErrorSetting(new CheckedRunnable() {
                            @Override
                            public void run() throws Exception {
                                mUploadProvider.read(OutputStreamDataSink.this, mBuffer);
                            }
                        }));
                    } else if (mTotalBytes == -1) {
                        finish();
                    } else if (mTotalBytes == mWrittenBytes) {
                        finish();
                    } else {
                        enterUploadErrorState(new IllegalArgumentException(String.format(
                                "Read upload data length %d exceeds expected length %d",
                                mWrittenBytes, mTotalBytes)));
                    }
                }
            }));
        }

        @Override
        public void onRewindSucceeded() {
            if (!mSinkState.compareAndSet(SinkState.AWAITING_REWIND_RESULT, SinkState.UPLOADING)) {
                throw new IllegalStateException("Not expecting a read result");
            }
            startRead();
        }

        @Override
        public void onReadError(Exception exception) {
            enterUploadErrorState(exception);
        }

        @Override
        public void onRewindError(Exception exception) {
            enterUploadErrorState(exception);
        }

        void startRead() {
            mExecutor.execute(errorSetting(State.STARTED, new CheckedRunnable() {
                @Override
                public void run() throws Exception {
                    if (mOutputChannel == null) {
                        mAdditionalStatusDetails = Status.CONNECTING;
                        mUrlConnection.connect();
                        mAdditionalStatusDetails = Status.SENDING_REQUEST;
                        mOutputChannel = Channels.newChannel(mUrlConnection.getOutputStream());
                    }
                    mSinkState.set(SinkState.AWAITING_READ_RESULT);
                    mUserExecutor.execute(uploadErrorSetting(new CheckedRunnable() {
                        @Override
                        public void run() throws Exception {
                            mUploadProvider.read(OutputStreamDataSink.this, mBuffer);
                        }
                    }));
                }
            }));
        }

        void finish() throws IOException {
            if (mOutputChannel != null) {
                mOutputChannel.close();
            }
            fireGetHeaders();
        }

        void start(final boolean firstTime) {
            mUserExecutor.execute(uploadErrorSetting(new CheckedRunnable() {
                @Override
                public void run() throws Exception {
                    mTotalBytes = mUploadProvider.getLength();
                    if (mTotalBytes == 0) {
                        finish();
                    } else {
                        // If we know how much data we have to upload, and it's small, we can save
                        // memory by allocating a reasonably sized buffer to read into.
                        if (mTotalBytes > 0 && mTotalBytes < DEFAULT_UPLOAD_BUFFER_SIZE) {
                            // Allocate one byte more than necessary, to detect callers uploading
                            // more bytes than they specified in length.
                            mBuffer = ByteBuffer.allocateDirect((int) mTotalBytes + 1);
                        } else {
                            mBuffer = ByteBuffer.allocateDirect(DEFAULT_UPLOAD_BUFFER_SIZE);
                        }

                        if (mTotalBytes > 0 && mTotalBytes <= Integer.MAX_VALUE) {
                            mUrlConnection.setFixedLengthStreamingMode((int) mTotalBytes);
                        } else if (mTotalBytes > Integer.MAX_VALUE
                                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                            mUrlConnection.setFixedLengthStreamingMode(mTotalBytes);
                        } else {
                            // If we know the length, but we're running pre-kitkat and it's larger
                            // than an int can hold, we have to use chunked - otherwise we'll end up
                            // buffering the whole response in memory.
                            mUrlConnection.setChunkedStreamingMode(DEFAULT_CHUNK_LENGTH);
                        }
                        if (firstTime) {
                            startRead();
                        } else {
                            mSinkState.set(SinkState.AWAITING_REWIND_RESULT);
                            mUploadProvider.rewind(OutputStreamDataSink.this);
                        }
                    }
                }
            }));
        }
    }

    @Override
    public void start() {
        mAdditionalStatusDetails = Status.CONNECTING;
        transitionStates(State.NOT_STARTED, State.STARTED, new Runnable() {
            @Override
            public void run() {
                mUrlChain.add(mCurrentUrl);
                fireOpenConnection();
            }
        });
    }

    private void enterErrorState(State previousState, final UrlRequestException error) {
        if (mState.compareAndSet(previousState, State.ERROR)) {
            fireDisconnect();
            fireCloseUploadDataProvider();
            mCallbackAsync.onFailed(mUrlResponseInfo, error);
        }
    }

    /** Ends the request with an error, caused by an exception thrown from user code. */
    private void enterUserErrorState(State previousState, final Throwable error) {
        enterErrorState(previousState,
                new UrlRequestException("Exception received from UrlRequest.Callback", error));
    }

    /** Ends the request with an error, caused by an exception thrown from user code. */
    private void enterUploadErrorState(final Throwable error) {
        enterErrorState(State.STARTED,
                new UrlRequestException("Exception received from UploadDataProvider", error));
    }

    private void enterCronetErrorState(State previousState, final Throwable error) {
        // TODO(clm) mapping from Java exception (UnknownHostException, for example) to net error
        // code goes here.
        enterErrorState(previousState, new UrlRequestException("System error", error));
    }

    /**
     * Atomically swaps from the expected state to a new state. If the swap fails, and it's not
     * due to an earlier error or cancellation, throws an exception.
     *
     * @param afterTransition Callback to run after transition completes successfully.
     */
    private void transitionStates(State expected, State newState, Runnable afterTransition) {
        if (!mState.compareAndSet(expected, newState)) {
            State state = mState.get();
            if (!(state == State.CANCELLED || state == State.ERROR)) {
                throw new IllegalStateException(
                        "Invalid state transition - expected " + expected + " but was " + state);
            }
        } else {
            afterTransition.run();
        }
    }

    @Override
    public void followRedirect() {
        transitionStates(State.AWAITING_FOLLOW_REDIRECT, State.STARTED, new Runnable() {
            @Override
            public void run() {
                mCurrentUrl = mPendingRedirectUrl;
                mPendingRedirectUrl = null;
                fireOpenConnection();
            }
        });
    }

    private void fireGetHeaders() {
        mAdditionalStatusDetails = Status.WAITING_FOR_RESPONSE;
        mExecutor.execute(errorSetting(State.STARTED, new CheckedRunnable() {
            @Override
            public void run() throws Exception {
                HttpURLConnection connection = mCurrentUrlConnection.get();
                if (connection == null) {
                    return; // We've been cancelled
                }
                final List<Map.Entry<String, String>> headerList = new ArrayList<>();
                String selectedTransport = "http/1.1";
                String headerKey;
                for (int i = 0; (headerKey = connection.getHeaderFieldKey(i)) != null; i++) {
                    if (X_ANDROID_SELECTED_TRANSPORT.equalsIgnoreCase(headerKey)) {
                        selectedTransport = connection.getHeaderField(i);
                    }
                    if (!headerKey.startsWith(X_ANDROID)) {
                        headerList.add(new SimpleEntry<>(headerKey, connection.getHeaderField(i)));
                    }
                }

                int responseCode = connection.getResponseCode();
                // Important to copy the list here, because although we never concurrently modify
                // the list ourselves, user code might iterate over it while we're redirecting, and
                // that would throw ConcurrentModificationException.
                mUrlResponseInfo = new UrlResponseInfo(new ArrayList<>(mUrlChain), responseCode,
                        connection.getResponseMessage(), Collections.unmodifiableList(headerList),
                        false, selectedTransport, "");
                // TODO(clm) actual redirect handling? post -> get and whatnot?
                if (responseCode >= 300 && responseCode < 400) {
                    fireRedirectReceived(mUrlResponseInfo.getAllHeaders());
                    return;
                }
                fireCloseUploadDataProvider();
                if (responseCode >= 400) {
                    mResponseChannel = InputStreamChannel.wrap(connection.getErrorStream());
                    mCallbackAsync.onResponseStarted(mUrlResponseInfo);
                } else {
                    mResponseChannel = InputStreamChannel.wrap(connection.getInputStream());
                    mCallbackAsync.onResponseStarted(mUrlResponseInfo);
                }
            }
        }));
    }

    private void fireCloseUploadDataProvider() {
        if (mUploadDataProvider != null && mUploadProviderClosed.compareAndSet(false, true)) {
            try {
                mUploadExecutor.execute(uploadErrorSetting(new CheckedRunnable() {
                    @Override
                    public void run() throws Exception {
                        mUploadDataProvider.close();
                    }
                }));
            } catch (RejectedExecutionException e) {
                Log.e(TAG, "Exception when closing uploadDataProvider", e);
            }
        }
    }

    private void fireRedirectReceived(final Map<String, List<String>> headerFields) {
        transitionStates(State.STARTED, State.REDIRECT_RECEIVED, new Runnable() {
            @Override
            public void run() {
                mPendingRedirectUrl = URI.create(mCurrentUrl)
                                              .resolve(headerFields.get("location").get(0))
                                              .toString();
                mUrlChain.add(mPendingRedirectUrl);
                transitionStates(
                        State.REDIRECT_RECEIVED, State.AWAITING_FOLLOW_REDIRECT, new Runnable() {
                            @Override
                            public void run() {
                                mCallbackAsync.onRedirectReceived(
                                        mUrlResponseInfo, mPendingRedirectUrl);
                            }
                        });
            }
        });
    }

    private void fireOpenConnection() {
        mExecutor.execute(errorSetting(State.STARTED, new CheckedRunnable() {
            @Override
            public void run() throws Exception {
                // If we're cancelled, then our old connection will be disconnected for us and
                // we shouldn't open a new one.
                if (mState.get() == State.CANCELLED) {
                    return;
                }

                final URL url = new URL(mCurrentUrl);
                HttpURLConnection newConnection = (HttpURLConnection) url.openConnection();
                HttpURLConnection oldConnection = mCurrentUrlConnection.getAndSet(newConnection);
                if (oldConnection != null) {
                    oldConnection.disconnect();
                }
                newConnection.setInstanceFollowRedirects(false);
                if (!mRequestHeaders.containsKey(USER_AGENT)) {
                    mRequestHeaders.put(USER_AGENT, mUserAgent);
                }
                for (Map.Entry<String, String> entry : mRequestHeaders.entrySet()) {
                    newConnection.setRequestProperty(entry.getKey(), entry.getValue());
                }
                if (mInitialMethod == null) {
                    mInitialMethod = "GET";
                }
                newConnection.setRequestMethod(mInitialMethod);
                if (mUploadDataProvider != null) {
                    OutputStreamDataSink dataSink = new OutputStreamDataSink(
                            mUploadExecutor, mExecutor, newConnection, mUploadDataProvider);
                    dataSink.start(mUrlChain.size() == 1);
                } else {
                    mAdditionalStatusDetails = Status.CONNECTING;
                    newConnection.connect();
                    fireGetHeaders();
                }
            }
        }));
    }

    private Runnable errorSetting(final State expectedState, final CheckedRunnable delegate) {
        return new Runnable() {
            @Override
            public void run() {
                try {
                    delegate.run();
                } catch (Throwable t) {
                    enterCronetErrorState(expectedState, t);
                }
            }
        };
    }

    private Runnable userErrorSetting(final State expectedState, final CheckedRunnable delegate) {
        return new Runnable() {
            @Override
            public void run() {
                try {
                    delegate.run();
                } catch (Throwable t) {
                    enterUserErrorState(expectedState, t);
                }
            }
        };
    }

    private Runnable uploadErrorSetting(final CheckedRunnable delegate) {
        return new Runnable() {
            @Override
            public void run() {
                try {
                    delegate.run();
                } catch (Throwable t) {
                    enterUploadErrorState(t);
                }
            }
        };
    }

    private interface CheckedRunnable { void run() throws Exception; }

    @Override
    public void read(final ByteBuffer buffer) {
        Preconditions.checkDirect(buffer);
        Preconditions.checkHasRemaining(buffer);
        transitionStates(State.AWAITING_READ, State.READING, new Runnable() {
            @Override
            public void run() {
                mExecutor.execute(errorSetting(State.READING, new CheckedRunnable() {
                    @Override
                    public void run() throws Exception {
                        int read = mResponseChannel.read(buffer);
                        processReadResult(read, buffer);
                    }
                }));
            }
        });
    }

    private void processReadResult(int read, final ByteBuffer buffer) throws IOException {
        if (read != -1) {
            mCallbackAsync.onReadCompleted(mUrlResponseInfo, buffer);
        } else {
            mResponseChannel.close();
            if (mState.compareAndSet(State.READING, State.COMPLETE)) {
                fireDisconnect();
                mCallbackAsync.onSucceeded(mUrlResponseInfo);
            }
        }
    }

    private void fireDisconnect() {
        final HttpURLConnection connection = mCurrentUrlConnection.getAndSet(null);
        if (connection != null) {
            mExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    connection.disconnect();
                }
            });
        }
    }

    @Override
    public void cancel() {
        State oldState = mState.getAndSet(State.CANCELLED);
        switch (oldState) {
            // We've just scheduled some user code to run. When they perform their next operation,
            // they'll observe it and fail. However, if user code is cancelling in response to one
            // of these callbacks, we'll never actually cancel!
            // TODO(clm) figure out if it's possible to avoid concurrency in user callbacks.
            case REDIRECT_RECEIVED:
            case AWAITING_FOLLOW_REDIRECT:
            case AWAITING_READ:

            // User code is waiting on us - cancel away!
            case STARTED:
            case READING:
                fireDisconnect();
                fireCloseUploadDataProvider();
                mCallbackAsync.onCanceled(mUrlResponseInfo);
                break;
            // The rest are all termination cases - we're too late to cancel.
            case ERROR:
            case COMPLETE:
            case CANCELLED:
                break;
        }
    }

    @Override
    public boolean isDone() {
        State state = mState.get();
        return state == State.COMPLETE | state == State.ERROR | state == State.CANCELLED;
    }

    @Override
    public void getStatus(StatusListener listener) {
        State state = mState.get();
        int extraStatus = this.mAdditionalStatusDetails;

        @Status.StatusValues final int status;
        switch (state) {
            case ERROR:
            case COMPLETE:
            case CANCELLED:
            case NOT_STARTED:
                status = Status.INVALID;
                break;
            case STARTED:
                status = extraStatus;
                break;
            case REDIRECT_RECEIVED:
            case AWAITING_FOLLOW_REDIRECT:
            case AWAITING_READ:
                status = Status.IDLE;
                break;
            case READING:
                status = Status.READING_RESPONSE;
                break;
            default:
                throw new IllegalStateException("Switch is exhaustive: " + state);
        }

        mCallbackAsync.sendStatus(listener, status);
    }

    /** This wrapper ensures that callbacks are always called on the correct executor */
    private final class AsyncUrlRequestCallback {
        final UrlRequest.Callback mCallback;
        final Executor mUserExecutor;

        AsyncUrlRequestCallback(Callback callback, final Executor userExecutor) {
            this.mCallback = callback;
            this.mUserExecutor = userExecutor;
        }

        void sendStatus(final StatusListener listener, final int status) {
            mUserExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    listener.onStatus(status);
                }
            });
        }

        void execute(State currentState, CheckedRunnable runnable) {
            try {
                mUserExecutor.execute(userErrorSetting(currentState, runnable));
            } catch (RejectedExecutionException e) {
                enterUserErrorState(currentState, e);
            }
        }

        void onRedirectReceived(final UrlResponseInfo info, final String newLocationUrl) {
            execute(State.AWAITING_FOLLOW_REDIRECT, new CheckedRunnable() {
                @Override
                public void run() throws Exception {
                    mCallback.onRedirectReceived(JavaUrlRequest.this, info, newLocationUrl);
                }
            });
        }

        void onResponseStarted(UrlResponseInfo info) {
            execute(State.AWAITING_READ, new CheckedRunnable() {
                @Override
                public void run() throws Exception {
                    if (mState.compareAndSet(State.STARTED, State.AWAITING_READ)) {
                        mCallback.onResponseStarted(JavaUrlRequest.this, mUrlResponseInfo);
                    }
                }
            });
        }

        void onReadCompleted(final UrlResponseInfo info, final ByteBuffer byteBuffer) {
            execute(State.AWAITING_READ, new CheckedRunnable() {
                @Override
                public void run() throws Exception {
                    if (mState.compareAndSet(State.READING, State.AWAITING_READ)) {
                        mCallback.onReadCompleted(JavaUrlRequest.this, info, byteBuffer);
                    }
                }
            });
        }

        void onCanceled(final UrlResponseInfo info) {
            closeQuietly(mResponseChannel);
            mUserExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        mCallback.onCanceled(JavaUrlRequest.this, info);
                    } catch (Exception exception) {
                        Log.e(TAG, "Exception in onCanceled method", exception);
                    }
                }
            });
        }

        void onSucceeded(final UrlResponseInfo info) {
            mUserExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        mCallback.onSucceeded(JavaUrlRequest.this, info);
                    } catch (Exception exception) {
                        Log.e(TAG, "Exception in onSucceeded method", exception);
                    }
                }
            });
        }

        void onFailed(final UrlResponseInfo urlResponseInfo, final UrlRequestException e) {
            closeQuietly(mResponseChannel);
            mUserExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        mCallback.onFailed(JavaUrlRequest.this, urlResponseInfo, e);
                    } catch (Exception exception) {
                        Log.e(TAG, "Exception in onFailed method", exception);
                    }
                }
            });
        }
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
