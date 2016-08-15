// Copyright 2014 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.net.impl;

import android.os.SystemClock;
import android.support.annotation.Nullable;
import android.util.Log;

import org.chromium.base.VisibleForTesting;
import org.chromium.base.annotations.CalledByNative;
import org.chromium.base.annotations.JNIAdditionalImport;
import org.chromium.base.annotations.JNINamespace;
import org.chromium.base.annotations.NativeClassQualifiedName;
import org.chromium.net.Preconditions;
import org.chromium.net.QuicException;
import org.chromium.net.RequestFinishedInfo;
import org.chromium.net.RequestPriority;
import org.chromium.net.UploadDataProvider;
import org.chromium.net.UrlRequest;
import org.chromium.net.UrlRequestException;
import org.chromium.net.UrlResponseInfo;

import java.nio.ByteBuffer;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import javax.annotation.concurrent.GuardedBy;

/**
 * UrlRequest using Chromium HTTP stack implementation. Could be accessed from
 * any thread on Executor. Cancel can be called from any thread.
 * All @CallByNative methods are called on native network thread
 * and post tasks with listener calls onto Executor. Upon return from listener
 * callback native request adapter is called on executive thread and posts
 * native tasks to native network thread. Because Cancel could be called from
 * any thread it is protected by mUrlRequestAdapterLock.
 */
@JNINamespace("cronet")
// Qualifies UrlRequest.StatusListener which is used in onStatus, a JNI method.
@JNIAdditionalImport(UrlRequest.class)
@VisibleForTesting
public final class CronetUrlRequest implements UrlRequest {
    private static final RequestFinishedInfo.Metrics EMPTY_METRICS =
            new RequestFinishedInfo.Metrics(null, null, null, null);

    /* Native adapter object, owned by UrlRequest. */
    @GuardedBy("mUrlRequestAdapterLock")
    private long mUrlRequestAdapter;

    @GuardedBy("mUrlRequestAdapterLock")
    private boolean mStarted = false;
    @GuardedBy("mUrlRequestAdapterLock")
    private boolean mWaitingOnRedirect = false;
    @GuardedBy("mUrlRequestAdapterLock")
    private boolean mWaitingOnRead = false;
    @GuardedBy("mUrlRequestAdapterLock")
    @Nullable
    private final UrlRequestMetricsAccumulator mRequestMetricsAccumulator;

    /*
     * Synchronize access to mUrlRequestAdapter, mStarted, mWaitingOnRedirect,
     * and mWaitingOnRead.
     */
    private final Object mUrlRequestAdapterLock = new Object();
    private final CronetUrlRequestContext mRequestContext;
    private final Executor mExecutor;

    /*
     * URL chain contains the URL currently being requested, and
     * all URLs previously requested. New URLs are added before
     * mCallback.onRedirectReceived is called.
     */
    private final List<String> mUrlChain = new ArrayList<String>();
    private long mReceivedBytesCountFromRedirects;

    private final UrlRequest.Callback mCallback;
    private final String mInitialUrl;
    private final int mPriority;
    private String mInitialMethod;
    private final HeadersList mRequestHeaders = new HeadersList();
    private final Collection<Object> mRequestAnnotations;
    private final boolean mDisableCache;
    private final boolean mDisableConnectionMigration;

    private CronetUploadDataStream mUploadDataStream;

    private UrlResponseInfo mResponseInfo;

    /*
     * Listener callback is repeatedly invoked when each read is completed, so it
     * is cached as a member variable.
     */
    private OnReadCompletedRunnable mOnReadCompletedTask;

    private Runnable mOnDestroyedCallbackForTesting;

    private static final class HeadersList extends ArrayList<Map.Entry<String, String>> {}

    private final class OnReadCompletedRunnable implements Runnable {
        // Buffer passed back from current invocation of onReadCompleted.
        ByteBuffer mByteBuffer;

        @Override
        public void run() {
            // Null out mByteBuffer, to pass buffer ownership to callback or release if done.
            ByteBuffer buffer = mByteBuffer;
            mByteBuffer = null;

            try {
                synchronized (mUrlRequestAdapterLock) {
                    if (isDoneLocked()) {
                        return;
                    }
                    mWaitingOnRead = true;
                }
                mCallback.onReadCompleted(CronetUrlRequest.this, mResponseInfo, buffer);
            } catch (Exception e) {
                onCallbackException(e);
            }
        }
    }

    CronetUrlRequest(CronetUrlRequestContext requestContext, String url, int priority,
            UrlRequest.Callback callback, Executor executor, Collection<Object> requestAnnotations,
            boolean metricsCollectionEnabled, boolean disableCache,
            boolean disableConnectionMigration) {
        if (url == null) {
            throw new NullPointerException("URL is required");
        }
        if (callback == null) {
            throw new NullPointerException("Listener is required");
        }
        if (executor == null) {
            throw new NullPointerException("Executor is required");
        }
        if (requestAnnotations == null) {
            throw new NullPointerException("requestAnnotations is required");
        }

        mRequestContext = requestContext;
        mInitialUrl = url;
        mUrlChain.add(url);
        mPriority = convertRequestPriority(priority);
        mCallback = callback;
        mExecutor = executor;
        mRequestAnnotations = requestAnnotations;
        mRequestMetricsAccumulator =
                metricsCollectionEnabled ? new UrlRequestMetricsAccumulator() : null;
        mDisableCache = disableCache;
        mDisableConnectionMigration = disableConnectionMigration;
    }

    @Override
    public void setHttpMethod(String method) {
        checkNotStarted();
        if (method == null) {
            throw new NullPointerException("Method is required.");
        }
        mInitialMethod = method;
    }

    @Override
    public void addHeader(String header, String value) {
        checkNotStarted();
        if (header == null) {
            throw new NullPointerException("Invalid header name.");
        }
        if (value == null) {
            throw new NullPointerException("Invalid header value.");
        }
        mRequestHeaders.add(new AbstractMap.SimpleImmutableEntry<String, String>(header, value));
    }

    @Override
    public void setUploadDataProvider(UploadDataProvider uploadDataProvider, Executor executor) {
        if (uploadDataProvider == null) {
            throw new NullPointerException("Invalid UploadDataProvider.");
        }
        if (mInitialMethod == null) {
            mInitialMethod = "POST";
        }
        mUploadDataStream = new CronetUploadDataStream(uploadDataProvider, executor);
    }

    @Override
    public void start() {
        synchronized (mUrlRequestAdapterLock) {
            checkNotStarted();

            try {
                mUrlRequestAdapter =
                        nativeCreateRequestAdapter(mRequestContext.getUrlRequestContextAdapter(),
                                mInitialUrl, mPriority, mDisableCache, mDisableConnectionMigration);
                mRequestContext.onRequestStarted();
                if (mInitialMethod != null) {
                    if (!nativeSetHttpMethod(mUrlRequestAdapter, mInitialMethod)) {
                        throw new IllegalArgumentException("Invalid http method " + mInitialMethod);
                    }
                }

                boolean hasContentType = false;
                for (Map.Entry<String, String> header : mRequestHeaders) {
                    if (header.getKey().equalsIgnoreCase("Content-Type")
                            && !header.getValue().isEmpty()) {
                        hasContentType = true;
                    }
                    if (!nativeAddRequestHeader(
                                mUrlRequestAdapter, header.getKey(), header.getValue())) {
                        throw new IllegalArgumentException(
                                "Invalid header " + header.getKey() + "=" + header.getValue());
                    }
                }
                if (mUploadDataStream != null) {
                    if (!hasContentType) {
                        throw new IllegalArgumentException(
                                "Requests with upload data must have a Content-Type.");
                    }
                    mStarted = true;
                    mUploadDataStream.postTaskToExecutor(new Runnable() {
                        @Override
                        public void run() {
                            mUploadDataStream.initializeWithRequest(CronetUrlRequest.this);
                            synchronized (mUrlRequestAdapterLock) {
                                if (isDoneLocked()) {
                                    return;
                                }
                                mUploadDataStream.attachNativeAdapterToRequest(mUrlRequestAdapter);
                                startInternalLocked();
                            }
                        }
                    });
                    return;
                }
            } catch (RuntimeException e) {
                // If there's an exception, cleanup and then throw the
                // exception to the caller.
                destroyRequestAdapter(false);
                throw e;
            }
            mStarted = true;
            startInternalLocked();
        }
    }

    /*
     * Starts fully configured request. Could execute on UploadDataProvider executor.
     * Caller is expected to ensure that request isn't canceled and mUrlRequestAdapter is valid.
     */
    @GuardedBy("mUrlRequestAdapterLock")
    private void startInternalLocked() {
        if (mRequestMetricsAccumulator != null) {
            mRequestMetricsAccumulator.onRequestStarted();
        }
        nativeStart(mUrlRequestAdapter);
    }

    @Override
    public void followRedirect() {
        synchronized (mUrlRequestAdapterLock) {
            if (!mWaitingOnRedirect) {
                throw new IllegalStateException("No redirect to follow.");
            }
            mWaitingOnRedirect = false;

            if (isDoneLocked()) {
                return;
            }

            nativeFollowDeferredRedirect(mUrlRequestAdapter);
        }
    }

    @Override
    public void read(ByteBuffer buffer) {
        Preconditions.checkHasRemaining(buffer);
        Preconditions.checkDirect(buffer);
        synchronized (mUrlRequestAdapterLock) {
            if (!mWaitingOnRead) {
                throw new IllegalStateException("Unexpected read attempt.");
            }
            mWaitingOnRead = false;

            if (isDoneLocked()) {
                return;
            }

            if (!nativeReadData(mUrlRequestAdapter, buffer, buffer.position(), buffer.limit())) {
                // Still waiting on read. This is just to have consistent
                // behavior with the other error cases.
                mWaitingOnRead = true;
                throw new IllegalArgumentException("Unable to call native read");
            }
        }
    }

    @Override
    public void cancel() {
        synchronized (mUrlRequestAdapterLock) {
            if (isDoneLocked() || !mStarted) {
                return;
            }
            destroyRequestAdapter(true);
        }
    }

    @Override
    public boolean isDone() {
        synchronized (mUrlRequestAdapterLock) {
            return isDoneLocked();
        }
    }

    @GuardedBy("mUrlRequestAdapterLock")
    private boolean isDoneLocked() {
        return mStarted && mUrlRequestAdapter == 0;
    }

    @Override
    public void getStatus(final UrlRequest.StatusListener listener) {
        synchronized (mUrlRequestAdapterLock) {
            if (mUrlRequestAdapter != 0) {
                nativeGetStatus(mUrlRequestAdapter, listener);
                return;
            }
        }
        Runnable task = new Runnable() {
            @Override
            public void run() {
                listener.onStatus(UrlRequest.Status.INVALID);
            }
        };
        postTaskToExecutor(task);
    }

    @VisibleForTesting
    public void setOnDestroyedCallbackForTesting(Runnable onDestroyedCallbackForTesting) {
        mOnDestroyedCallbackForTesting = onDestroyedCallbackForTesting;
    }

    @VisibleForTesting
    public void setOnDestroyedUploadCallbackForTesting(
            Runnable onDestroyedUploadCallbackForTesting) {
        mUploadDataStream.setOnDestroyedCallbackForTesting(onDestroyedUploadCallbackForTesting);
    }

    @VisibleForTesting
    public long getUrlRequestAdapterForTesting() {
        synchronized (mUrlRequestAdapterLock) {
            return mUrlRequestAdapter;
        }
    }

    /**
     * Posts task to application Executor. Used for Listener callbacks
     * and other tasks that should not be executed on network thread.
     */
    private void postTaskToExecutor(Runnable task) {
        try {
            mExecutor.execute(task);
        } catch (RejectedExecutionException failException) {
            Log.e(CronetUrlRequestContext.LOG_TAG, "Exception posting task to executor",
                    failException);
            // If posting a task throws an exception, then there is no choice
            // but to destroy the request without invoking the callback.
            destroyRequestAdapter(false);
        }
    }

    private static int convertRequestPriority(int priority) {
        switch (priority) {
            case Builder.REQUEST_PRIORITY_IDLE:
                return RequestPriority.IDLE;
            case Builder.REQUEST_PRIORITY_LOWEST:
                return RequestPriority.LOWEST;
            case Builder.REQUEST_PRIORITY_LOW:
                return RequestPriority.LOW;
            case Builder.REQUEST_PRIORITY_MEDIUM:
                return RequestPriority.MEDIUM;
            case Builder.REQUEST_PRIORITY_HIGHEST:
                return RequestPriority.HIGHEST;
            default:
                return RequestPriority.MEDIUM;
        }
    }

    private UrlResponseInfo prepareResponseInfoOnNetworkThread(int httpStatusCode,
            String httpStatusText, String[] headers, boolean wasCached, String negotiatedProtocol,
            String proxyServer) {
        HeadersList headersList = new HeadersList();
        for (int i = 0; i < headers.length; i += 2) {
            headersList.add(new AbstractMap.SimpleImmutableEntry<String, String>(
                    headers[i], headers[i + 1]));
        }
        return new UrlResponseInfo(new ArrayList<String>(mUrlChain), httpStatusCode, httpStatusText,
                headersList, wasCached, negotiatedProtocol, proxyServer);
    }

    private void checkNotStarted() {
        synchronized (mUrlRequestAdapterLock) {
            if (mStarted || isDoneLocked()) {
                throw new IllegalStateException("Request is already started.");
            }
        }
    }

    private void destroyRequestAdapter(boolean sendOnCanceled) {
        synchronized (mUrlRequestAdapterLock) {
            if (mUrlRequestAdapter == 0) {
                return;
            }
            if (mRequestMetricsAccumulator != null) {
                mRequestMetricsAccumulator.onRequestFinished();
            }
            nativeDestroy(mUrlRequestAdapter, sendOnCanceled);
            mRequestContext.reportFinished(this);
            mRequestContext.onRequestDestroyed();
            mUrlRequestAdapter = 0;
            if (mOnDestroyedCallbackForTesting != null) {
                mOnDestroyedCallbackForTesting.run();
            }
        }
    }

    /**
     * If callback method throws an exception, request gets canceled
     * and exception is reported via onFailed listener callback.
     * Only called on the Executor.
     */
    private void onCallbackException(Exception e) {
        UrlRequestException requestError =
                new UrlRequestException("Exception received from UrlRequest.Callback", e);
        Log.e(CronetUrlRequestContext.LOG_TAG, "Exception in CalledByNative method", e);
        // Do not call into listener if request is finished.
        synchronized (mUrlRequestAdapterLock) {
            if (isDoneLocked()) {
                return;
            }
            destroyRequestAdapter(false);
        }
        try {
            mCallback.onFailed(this, mResponseInfo, requestError);
        } catch (Exception failException) {
            Log.e(CronetUrlRequestContext.LOG_TAG, "Exception notifying of failed request",
                    failException);
        }
    }

    /**
     * Called when UploadDataProvider encounters an error.
     */
    void onUploadException(Throwable e) {
        UrlRequestException uploadError =
                new UrlRequestException("Exception received from UploadDataProvider", e);
        Log.e(CronetUrlRequestContext.LOG_TAG, "Exception in upload method", e);
        failWithException(uploadError);
    }

    /**
     * Fails the request with an exception. Can be called on any thread.
     */
    private void failWithException(final UrlRequestException exception) {
        Runnable task = new Runnable() {
            @Override
            public void run() {
                synchronized (mUrlRequestAdapterLock) {
                    if (isDoneLocked()) {
                        return;
                    }
                    destroyRequestAdapter(false);
                }
                try {
                    mCallback.onFailed(CronetUrlRequest.this, mResponseInfo, exception);
                } catch (Exception e) {
                    Log.e(CronetUrlRequestContext.LOG_TAG, "Exception in onError method", e);
                }
            }
        };
        postTaskToExecutor(task);
    }

    ////////////////////////////////////////////////
    // Private methods called by the native code.
    // Always called on network thread.
    ////////////////////////////////////////////////

    /**
     * Called before following redirects. The redirect will only be followed if
     * {@link #followRedirect()} is called. If the redirect response has a body, it will be ignored.
     * This will only be called between start and onResponseStarted.
     *
     * @param newLocation Location where request is redirected.
     * @param httpStatusCode from redirect response
     * @param receivedBytesCount count of bytes received for redirect response
     * @param headers an array of response headers with keys at the even indices
     *         followed by the corresponding values at the odd indices.
     */
    @SuppressWarnings("unused")
    @CalledByNative
    private void onRedirectReceived(final String newLocation, int httpStatusCode,
            String httpStatusText, String[] headers, boolean wasCached, String negotiatedProtocol,
            String proxyServer, long receivedBytesCount) {
        final UrlResponseInfo responseInfo = prepareResponseInfoOnNetworkThread(httpStatusCode,
                httpStatusText, headers, wasCached, negotiatedProtocol, proxyServer);
        mReceivedBytesCountFromRedirects += receivedBytesCount;
        responseInfo.setReceivedBytesCount(mReceivedBytesCountFromRedirects);

        // Have to do this after creating responseInfo.
        mUrlChain.add(newLocation);

        Runnable task = new Runnable() {
            @Override
            public void run() {
                synchronized (mUrlRequestAdapterLock) {
                    if (isDoneLocked()) {
                        return;
                    }
                    mWaitingOnRedirect = true;
                }

                try {
                    mCallback.onRedirectReceived(CronetUrlRequest.this, responseInfo, newLocation);
                } catch (Exception e) {
                    onCallbackException(e);
                }
            }
        };
        postTaskToExecutor(task);
    }

    /**
     * Called when the final set of headers, after all redirects,
     * is received. Can only be called once for each request.
     */
    @SuppressWarnings("unused")
    @CalledByNative
    private void onResponseStarted(int httpStatusCode, String httpStatusText, String[] headers,
            boolean wasCached, String negotiatedProtocol, String proxyServer) {
        mResponseInfo = prepareResponseInfoOnNetworkThread(httpStatusCode, httpStatusText, headers,
                wasCached, negotiatedProtocol, proxyServer);
        Runnable task = new Runnable() {
            @Override
            public void run() {
                synchronized (mUrlRequestAdapterLock) {
                    if (isDoneLocked()) {
                        return;
                    }
                    if (mRequestMetricsAccumulator != null) {
                        mRequestMetricsAccumulator.onResponseStarted();
                    }
                    mWaitingOnRead = true;
                }

                try {
                    mCallback.onResponseStarted(CronetUrlRequest.this, mResponseInfo);
                } catch (Exception e) {
                    onCallbackException(e);
                }
            }
        };
        postTaskToExecutor(task);
    }

    /**
     * Called whenever data is received. The ByteBuffer remains
     * valid only until listener callback. Or if the callback
     * pauses the request, it remains valid until the request is resumed.
     * Cancelling the request also invalidates the buffer.
     *
     * @param byteBuffer ByteBuffer containing received data, starting at
     *        initialPosition. Guaranteed to have at least one read byte. Its
     *        limit has not yet been updated to reflect the bytes read.
     * @param bytesRead Number of bytes read.
     * @param initialPosition Original position of byteBuffer when passed to
     *        read(). Used as a minimal check that the buffer hasn't been
     *        modified while reading from the network.
     * @param initialLimit Original limit of byteBuffer when passed to
     *        read(). Used as a minimal check that the buffer hasn't been
     *        modified while reading from the network.
     * @param receivedBytesCount number of bytes received.
     */
    @SuppressWarnings("unused")
    @CalledByNative
    private void onReadCompleted(final ByteBuffer byteBuffer, int bytesRead, int initialPosition,
            int initialLimit, long receivedBytesCount) {
        mResponseInfo.setReceivedBytesCount(mReceivedBytesCountFromRedirects + receivedBytesCount);
        if (byteBuffer.position() != initialPosition || byteBuffer.limit() != initialLimit) {
            failWithException(
                    new UrlRequestException("ByteBuffer modified externally during read", null));
            return;
        }
        if (mOnReadCompletedTask == null) {
            mOnReadCompletedTask = new OnReadCompletedRunnable();
        }
        byteBuffer.position(initialPosition + bytesRead);
        mOnReadCompletedTask.mByteBuffer = byteBuffer;
        postTaskToExecutor(mOnReadCompletedTask);
    }

    /**
     * Called when request is completed successfully, no callbacks will be
     * called afterwards.
     *
     * @param receivedBytesCount number of bytes received.
     */
    @SuppressWarnings("unused")
    @CalledByNative
    private void onSucceeded(long receivedBytesCount) {
        mResponseInfo.setReceivedBytesCount(mReceivedBytesCountFromRedirects + receivedBytesCount);
        Runnable task = new Runnable() {
            @Override
            public void run() {
                synchronized (mUrlRequestAdapterLock) {
                    if (isDoneLocked()) {
                        return;
                    }
                    // Destroy adapter first, so request context could be shut
                    // down from the listener.
                    destroyRequestAdapter(false);
                }
                try {
                    mCallback.onSucceeded(CronetUrlRequest.this, mResponseInfo);
                } catch (Exception e) {
                    Log.e(CronetUrlRequestContext.LOG_TAG, "Exception in onComplete method", e);
                }
            }
        };
        postTaskToExecutor(task);
    }

    /**
     * Called when error has occured, no callbacks will be called afterwards.
     *
     * @param errorCode error code from {@link UrlRequestException.ERROR_LISTENER_EXCEPTION_THROWN
     *         UrlRequestException.ERROR_*}.
     * @param nativeError native net error code.
     * @param errorString textual representation of the error code.
     * @param receivedBytesCount number of bytes received.
     */
    @SuppressWarnings("unused")
    @CalledByNative
    private void onError(int errorCode, int nativeError, int nativeQuicError, String errorString,
            long receivedBytesCount) {
        if (mResponseInfo != null) {
            mResponseInfo.setReceivedBytesCount(
                    mReceivedBytesCountFromRedirects + receivedBytesCount);
        }
        if (errorCode == UrlRequestException.ERROR_QUIC_PROTOCOL_FAILED) {
            failWithException(new QuicException(
                    "Exception in CronetUrlRequest: " + errorString, nativeError, nativeQuicError));
        } else {
            failWithException(new UrlRequestException(
                    "Exception in CronetUrlRequest: " + errorString, errorCode, nativeError));
        }
    }

    /**
     * Called when request is canceled, no callbacks will be called afterwards.
     */
    @SuppressWarnings("unused")
    @CalledByNative
    private void onCanceled() {
        Runnable task = new Runnable() {
            @Override
            public void run() {
                try {
                    mCallback.onCanceled(CronetUrlRequest.this, mResponseInfo);
                } catch (Exception e) {
                    Log.e(CronetUrlRequestContext.LOG_TAG, "Exception in onCanceled method", e);
                }
            }
        };
        postTaskToExecutor(task);
    }

    /**
     * Called by the native code when request status is fetched from the
     * native stack.
     */
    @SuppressWarnings("unused")
    @CalledByNative
    private void onStatus(final UrlRequest.StatusListener listener, final int loadState) {
        Runnable task = new Runnable() {
            @Override
            public void run() {
                listener.onStatus(UrlRequest.Status.convertLoadState(loadState));
            }
        };
        postTaskToExecutor(task);
    }

    RequestFinishedInfo getRequestFinishedInfo() {
        return new RequestFinishedInfo(mInitialUrl, mRequestAnnotations,
                (mRequestMetricsAccumulator != null ? mRequestMetricsAccumulator.getRequestMetrics()
                                                    : EMPTY_METRICS),
                mResponseInfo);
    }

    private final class UrlRequestMetricsAccumulator {
        @Nullable
        private Long mRequestStartTime;
        @Nullable
        private Long mTtfbMs;
        @Nullable
        private Long mTotalTimeMs;

        private RequestFinishedInfo.Metrics getRequestMetrics() {
            return new RequestFinishedInfo.Metrics(mTtfbMs, mTotalTimeMs,
                    null, // TODO(klm): Compute sentBytesCount.
                    (mResponseInfo != null ? mResponseInfo.getReceivedBytesCount() : 0));
        }

        private void onRequestStarted() {
            if (mRequestStartTime != null) {
                throw new IllegalStateException("onRequestStarted called repeatedly");
            }
            mRequestStartTime = SystemClock.elapsedRealtime();
        }

        private void onRequestFinished() {
            if (mRequestStartTime != null && mTotalTimeMs == null) {
                mTotalTimeMs = SystemClock.elapsedRealtime() - mRequestStartTime;
            }
        }

        private void onResponseStarted() {
            if (mRequestStartTime != null && mTtfbMs == null) {
                mTtfbMs = SystemClock.elapsedRealtime() - mRequestStartTime;
            }
        }
    }

    // Native methods are implemented in cronet_url_request_adapter.cc.

    private native long nativeCreateRequestAdapter(long urlRequestContextAdapter, String url,
            int priority, boolean disableCache, boolean disableConnectionMigration);

    @NativeClassQualifiedName("CronetURLRequestAdapter")
    private native boolean nativeSetHttpMethod(long nativePtr, String method);

    @NativeClassQualifiedName("CronetURLRequestAdapter")
    private native boolean nativeAddRequestHeader(long nativePtr, String name, String value);

    @NativeClassQualifiedName("CronetURLRequestAdapter")
    private native void nativeStart(long nativePtr);

    @NativeClassQualifiedName("CronetURLRequestAdapter")
    private native void nativeFollowDeferredRedirect(long nativePtr);

    @NativeClassQualifiedName("CronetURLRequestAdapter")
    private native boolean nativeReadData(
            long nativePtr, ByteBuffer byteBuffer, int position, int capacity);

    @NativeClassQualifiedName("CronetURLRequestAdapter")
    private native void nativeDestroy(long nativePtr, boolean sendOnCanceled);

    @NativeClassQualifiedName("CronetURLRequestAdapter")
    private native void nativeGetStatus(long nativePtr, UrlRequest.StatusListener listener);
}
