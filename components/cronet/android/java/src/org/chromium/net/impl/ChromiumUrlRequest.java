// Copyright 2014 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.net.impl;

import android.util.Log;

import org.chromium.base.annotations.CalledByNative;
import org.chromium.base.annotations.JNINamespace;
import org.chromium.base.annotations.SuppressFBWarnings;
import org.chromium.net.ChromiumUrlRequestError;
import org.chromium.net.ChromiumUrlRequestPriority;
import org.chromium.net.ChunkedWritableByteChannel;
import org.chromium.net.HttpUrlRequest;
import org.chromium.net.HttpUrlRequestListener;
import org.chromium.net.ResponseTooLargeException;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

/**
 * Network request using the native http stack implementation.
 * @deprecated Use {@link CronetUrlRequest} instead.
 */
@JNINamespace("cronet")
@Deprecated
public class ChromiumUrlRequest implements HttpUrlRequest {
    /**
     * Native adapter object, owned by UrlRequest.
     */
    private long mUrlRequestAdapter;
    private final ChromiumUrlRequestContext mRequestContext;
    private final String mUrl;
    private final int mPriority;
    private final Map<String, String> mHeaders;
    private final WritableByteChannel mSink;
    private Map<String, String> mAdditionalHeaders;
    private String mUploadContentType;
    private String mMethod;
    private byte[] mUploadData;
    private ReadableByteChannel mUploadChannel;
    private boolean mChunkedUpload;
    private IOException mSinkException;
    private volatile boolean mStarted;
    private volatile boolean mCanceled;
    private volatile boolean mFinished;
    private boolean mHeadersAvailable;
    private long mUploadContentLength;
    private final HttpUrlRequestListener mListener;
    private boolean mBufferFullResponse;
    private long mOffset;
    private long mContentLengthLimit;
    private boolean mCancelIfContentLengthOverLimit;
    private boolean mContentLengthOverLimit;
    private boolean mSkippingToOffset;
    private long mSize;

    // Indicates whether redirects have been disabled.
    private boolean mDisableRedirects;

    // Http status code. Default to 0. Populated in onResponseStarted().
    private int mHttpStatusCode = 0;

    // Http status text. Default to null. Populated in onResponseStarted().
    private String mHttpStatusText;

    // Content type. Default to null. Populated in onResponseStarted().
    private String mContentType;

    // Compressed content length as reported by the server. Populated in onResponseStarted().
    private long mContentLength;

    // Native error code. Default to no error. Populated in onRequestComplete().
    private int mErrorCode = ChromiumUrlRequestError.SUCCESS;

    // Native error string. Default to null. Populated in onRequestComplete().
    private String mErrorString;

    // Protects access of mUrlRequestAdapter, mStarted, mCanceled, and mFinished.
    private final Object mLock = new Object();

    public ChromiumUrlRequest(ChromiumUrlRequestContext requestContext, String url, int priority,
            Map<String, String> headers, HttpUrlRequestListener listener) {
        this(requestContext, url, priority, headers, new ChunkedWritableByteChannel(), listener);
        mBufferFullResponse = true;
    }

    /**
     * Constructor.
     *
     * @param requestContext The context.
     * @param url The URL.
     * @param priority Request priority, e.g. {@link #REQUEST_PRIORITY_MEDIUM}.
     * @param headers HTTP headers.
     * @param sink The output channel into which downloaded content will be
     *            written.
     */
    public ChromiumUrlRequest(ChromiumUrlRequestContext requestContext, String url, int priority,
            Map<String, String> headers, WritableByteChannel sink,
            HttpUrlRequestListener listener) {
        if (requestContext == null) {
            throw new NullPointerException("Context is required");
        }
        if (url == null) {
            throw new NullPointerException("URL is required");
        }
        mRequestContext = requestContext;
        mUrl = url;
        mPriority = convertRequestPriority(priority);
        mHeaders = headers;
        mSink = sink;
        mUrlRequestAdapter = nativeCreateRequestAdapter(
                mRequestContext.getUrlRequestContextAdapter(), mUrl, mPriority);
        mListener = listener;
    }

    @Override
    public void setOffset(long offset) {
        mOffset = offset;
        if (offset != 0) {
            addHeader("Range", "bytes=" + offset + "-");
        }
    }

    /**
     * The compressed content length as reported by the server.  May be -1 if
     * the server did not provide a length.  Some servers may also report the
     * wrong number.  Since this is the compressed content length, and only
     * uncompressed content is returned by the consumer, the consumer should
     * not rely on this value.
     */
    @Override
    public long getContentLength() {
        return mContentLength;
    }

    @Override
    public void setContentLengthLimit(long limit, boolean cancelEarly) {
        mContentLengthLimit = limit;
        mCancelIfContentLengthOverLimit = cancelEarly;
    }

    @Override
    public int getHttpStatusCode() {
        // TODO(mef): Investigate the following:
        // If we have been able to successfully resume a previously interrupted
        // download, the status code will be 206, not 200. Since the rest of the
        // application is expecting 200 to indicate success, we need to fake it.
        if (mHttpStatusCode == 206) {
            return 200;
        }
        return mHttpStatusCode;
    }

    @Override
    public String getHttpStatusText() {
        return mHttpStatusText;
    }

    /**
     * Returns an exception if any, or null if the request has not completed or
     * completed successfully.
     */
    @Override
    public IOException getException() {
        if (mSinkException != null) {
            return mSinkException;
        }

        switch (mErrorCode) {
            case ChromiumUrlRequestError.SUCCESS:
                if (mContentLengthOverLimit) {
                    return new ResponseTooLargeException();
                }
                return null;
            case ChromiumUrlRequestError.UNKNOWN:
                return new IOException(mErrorString);
            case ChromiumUrlRequestError.MALFORMED_URL:
                return new MalformedURLException("Malformed URL: " + mUrl);
            case ChromiumUrlRequestError.CONNECTION_TIMED_OUT:
                return new SocketTimeoutException("Connection timed out");
            case ChromiumUrlRequestError.UNKNOWN_HOST:
                String host;
                try {
                    host = new URL(mUrl).getHost();
                } catch (MalformedURLException e) {
                    host = mUrl;
                }
                return new UnknownHostException("Unknown host: " + host);
            case ChromiumUrlRequestError.TOO_MANY_REDIRECTS:
                return new IOException("Request failed because there were too "
                        + "many redirects or redirects have been disabled");
            default:
                throw new IllegalStateException("Unrecognized error code: " + mErrorCode);
        }
    }

    @Override
    public ByteBuffer getByteBuffer() {
        return ((ChunkedWritableByteChannel) getSink()).getByteBuffer();
    }

    @Override
    public byte[] getResponseAsBytes() {
        return ((ChunkedWritableByteChannel) getSink()).getBytes();
    }

    /**
     * Adds a request header. Must be done before request has started.
     */
    public void addHeader(String header, String value) {
        synchronized (mLock) {
            validateNotStarted();
            if (mAdditionalHeaders == null) {
                mAdditionalHeaders = new HashMap<String, String>();
            }
            mAdditionalHeaders.put(header, value);
        }
    }

    /**
     * Sets data to upload as part of a POST or PUT request.
     *
     * @param contentType MIME type of the upload content or null if this is not
     *            an upload.
     * @param data The content that needs to be uploaded.
     */
    @Override
    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public void setUploadData(String contentType, byte[] data) {
        synchronized (mLock) {
            validateNotStarted();
            validateContentType(contentType);
            mUploadContentType = contentType;
            mUploadData = data;
            mUploadChannel = null;
            mChunkedUpload = false;
        }
    }

    /**
     * Sets a readable byte channel to upload as part of a POST or PUT request.
     *
     * @param contentType MIME type of the upload content or null if this is not
     *            an upload request.
     * @param channel The channel to read to read upload data from if this is an
     *            upload request.
     * @param contentLength The length of data to upload.
     */
    @Override
    public void setUploadChannel(
            String contentType, ReadableByteChannel channel, long contentLength) {
        synchronized (mLock) {
            validateNotStarted();
            validateContentType(contentType);
            mUploadContentType = contentType;
            mUploadChannel = channel;
            mUploadContentLength = contentLength;
            mUploadData = null;
            mChunkedUpload = false;
        }
    }

    /**
     * Sets this request up for chunked uploading. To upload data call
     * {@link #appendChunk(ByteBuffer, boolean)} after {@link #start()}.
     *
     * @param contentType MIME type of the post content or null if this is not a
     *            POST request.
     */
    public void setChunkedUpload(String contentType) {
        synchronized (mLock) {
            validateNotStarted();
            validateContentType(contentType);
            mUploadContentType = contentType;
            mChunkedUpload = true;
            mUploadData = null;
            mUploadChannel = null;
        }
    }

    /**
     * Uploads a new chunk. Must have called {@link #setChunkedUpload(String)}
     * and {@link #start()}.
     *
     * @param chunk The data, which will not be modified. Its current position.
     *            must be zero. The last chunk can be empty, but all other
     *            chunks must be non-empty.
     * @param isLastChunk Whether this chunk is the last one.
     */
    public void appendChunk(ByteBuffer chunk, boolean isLastChunk) throws IOException {
        if (!isLastChunk && !chunk.hasRemaining()) {
            throw new IllegalArgumentException("Attempted to write empty buffer.");
        }
        if (chunk.position() != 0) {
            throw new IllegalArgumentException("The position must be zero.");
        }
        synchronized (mLock) {
            if (!mStarted) {
                throw new IllegalStateException("Request not yet started.");
            }
            if (!mChunkedUpload) {
                throw new IllegalStateException("Request not set for chunked uploadind.");
            }
            if (mUrlRequestAdapter == 0) {
                throw new IOException("Native peer destroyed.");
            }
            nativeAppendChunk(mUrlRequestAdapter, chunk, chunk.limit(), isLastChunk);
        }
    }

    @Override
    public void setHttpMethod(String method) {
        validateNotStarted();
        mMethod = method;
    }

    @Override
    public void disableRedirects() {
        synchronized (mLock) {
            validateNotStarted();
            validateNativeAdapterNotDestroyed();
            mDisableRedirects = true;
            nativeDisableRedirects(mUrlRequestAdapter);
        }
    }

    public WritableByteChannel getSink() {
        return mSink;
    }

    @Override
    public void start() {
        synchronized (mLock) {
            if (mCanceled) {
                return;
            }

            validateNotStarted();
            validateNativeAdapterNotDestroyed();

            mStarted = true;

            if (mHeaders != null && !mHeaders.isEmpty()) {
                for (Entry<String, String> entry : mHeaders.entrySet()) {
                    nativeAddHeader(mUrlRequestAdapter, entry.getKey(), entry.getValue());
                }
            }

            if (mAdditionalHeaders != null) {
                for (Entry<String, String> entry : mAdditionalHeaders.entrySet()) {
                    nativeAddHeader(mUrlRequestAdapter, entry.getKey(), entry.getValue());
                }
            }

            if (mUploadData != null && mUploadData.length > 0) {
                nativeSetUploadData(mUrlRequestAdapter, mUploadContentType, mUploadData);
            } else if (mUploadChannel != null) {
                nativeSetUploadChannel(
                        mUrlRequestAdapter, mUploadContentType, mUploadContentLength);
            } else if (mChunkedUpload) {
                nativeEnableChunkedUpload(mUrlRequestAdapter, mUploadContentType);
            }

            // Note:  The above functions to set the upload body also set the
            // method to POST, behind the scenes, so if mMethod is null but
            // there's an upload body, the method will default to POST.
            if (mMethod != null) {
                nativeSetMethod(mUrlRequestAdapter, mMethod);
            }

            nativeStart(mUrlRequestAdapter);
        }
    }

    @Override
    public void cancel() {
        synchronized (mLock) {
            if (mCanceled) {
                return;
            }

            mCanceled = true;

            if (mUrlRequestAdapter != 0) {
                nativeCancel(mUrlRequestAdapter);
            }
        }
    }

    @Override
    public boolean isCanceled() {
        synchronized (mLock) {
            return mCanceled;
        }
    }

    @Override
    public String getNegotiatedProtocol() {
        synchronized (mLock) {
            validateNativeAdapterNotDestroyed();
            validateHeadersAvailable();
            return nativeGetNegotiatedProtocol(mUrlRequestAdapter);
        }
    }

    @Override
    public boolean wasCached() {
        synchronized (mLock) {
            validateNativeAdapterNotDestroyed();
            validateHeadersAvailable();
            return nativeGetWasCached(mUrlRequestAdapter);
        }
    }

    @Override
    public String getContentType() {
        return mContentType;
    }

    @Override
    public String getHeader(String name) {
        synchronized (mLock) {
            validateNativeAdapterNotDestroyed();
            validateHeadersAvailable();
            return nativeGetHeader(mUrlRequestAdapter, name);
        }
    }

    // All response headers.
    @Override
    public Map<String, List<String>> getAllHeaders() {
        synchronized (mLock) {
            validateNativeAdapterNotDestroyed();
            validateHeadersAvailable();
            ResponseHeadersMap result = new ResponseHeadersMap();
            nativeGetAllHeaders(mUrlRequestAdapter, result);
            return result;
        }
    }

    @Override
    public String getUrl() {
        return mUrl;
    }

    private static int convertRequestPriority(int priority) {
        switch (priority) {
            case REQUEST_PRIORITY_IDLE:
                return ChromiumUrlRequestPriority.IDLE;
            case REQUEST_PRIORITY_LOWEST:
                return ChromiumUrlRequestPriority.LOWEST;
            case REQUEST_PRIORITY_LOW:
                return ChromiumUrlRequestPriority.LOW;
            case REQUEST_PRIORITY_MEDIUM:
                return ChromiumUrlRequestPriority.MEDIUM;
            case REQUEST_PRIORITY_HIGHEST:
                return ChromiumUrlRequestPriority.HIGHEST;
            default:
                return ChromiumUrlRequestPriority.MEDIUM;
        }
    }

    private void onContentLengthOverLimit() {
        mContentLengthOverLimit = true;
        cancel();
    }

    /**
     * A callback invoked when the response has been fully consumed.
     */
    private void onRequestComplete() {
        mErrorCode = nativeGetErrorCode(mUrlRequestAdapter);
        mErrorString = nativeGetErrorString(mUrlRequestAdapter);
        // When there is an error or redirects have been disabled,
        // onResponseStarted is often not invoked.
        // Populate status code and status text if that's the case.
        // Note that besides redirects, these two fields may be set on the
        // request for AUTH and CERT requests.
        if (mErrorCode != ChromiumUrlRequestError.SUCCESS) {
            mHttpStatusCode = nativeGetHttpStatusCode(mUrlRequestAdapter);
            mHttpStatusText = nativeGetHttpStatusText(mUrlRequestAdapter);
        }
        mListener.onRequestComplete(this);
    }

    private void validateNativeAdapterNotDestroyed() {
        if (mUrlRequestAdapter == 0) {
            throw new IllegalStateException("Adapter has been destroyed");
        }
    }

    private void validateNotStarted() {
        if (mStarted) {
            throw new IllegalStateException("Request already started");
        }
    }

    private void validateHeadersAvailable() {
        if (!mHeadersAvailable) {
            throw new IllegalStateException("Response headers not available");
        }
    }

    private void validateContentType(String contentType) {
        if (contentType == null) {
            throw new NullPointerException("contentType is required");
        }
    }

    // Private methods called by native library.

    /**
     * If @CalledByNative method throws an exception, request gets canceled
     * and exception could be retrieved from request using getException().
     */
    private void onCalledByNativeException(Exception e) {
        mSinkException = new IOException("CalledByNative method has thrown an exception", e);
        Log.e(ChromiumUrlRequestContext.LOG_TAG, "Exception in CalledByNative method", e);
        try {
            cancel();
        } catch (Exception cancel_exception) {
            Log.e(ChromiumUrlRequestContext.LOG_TAG, "Exception trying to cancel request",
                    cancel_exception);
        }
    }

    /**
     * A callback invoked when the first chunk of the response has arrived.
     */
    @CalledByNative
    private void onResponseStarted() {
        try {
            mHttpStatusCode = nativeGetHttpStatusCode(mUrlRequestAdapter);
            mHttpStatusText = nativeGetHttpStatusText(mUrlRequestAdapter);
            mContentType = nativeGetContentType(mUrlRequestAdapter);
            mContentLength = nativeGetContentLength(mUrlRequestAdapter);
            mHeadersAvailable = true;

            if (mContentLengthLimit > 0 && mContentLength > mContentLengthLimit
                    && mCancelIfContentLengthOverLimit) {
                onContentLengthOverLimit();
                return;
            }

            if (mBufferFullResponse && mContentLength != -1 && !mContentLengthOverLimit) {
                ((ChunkedWritableByteChannel) getSink()).setCapacity((int) mContentLength);
            }

            if (mOffset != 0) {
                // The server may ignore the request for a byte range, in which
                // case status code will be 200, instead of 206. Note that we
                // cannot call getHttpStatusCode as it rewrites 206 into 200.
                if (mHttpStatusCode == 200) {
                    // TODO(mef): Revisit this logic.
                    if (mContentLength != -1) {
                        mContentLength -= mOffset;
                    }
                    mSkippingToOffset = true;
                } else {
                    mSize = mOffset;
                }
            }
            mListener.onResponseStarted(this);
        } catch (Exception e) {
            onCalledByNativeException(e);
        }
    }

    /**
     * Consumes a portion of the response.
     *
     * @param byteBuffer The ByteBuffer to append. Must be a direct buffer, and
     *            no references to it may be retained after the method ends, as
     *            it wraps code managed on the native heap.
     */
    @CalledByNative
    private void onBytesRead(ByteBuffer buffer) {
        try {
            if (mContentLengthOverLimit) {
                return;
            }

            int size = buffer.remaining();
            mSize += size;
            if (mSkippingToOffset) {
                if (mSize <= mOffset) {
                    return;
                } else {
                    mSkippingToOffset = false;
                    buffer.position((int) (mOffset - (mSize - size)));
                }
            }

            boolean contentLengthOverLimit =
                    (mContentLengthLimit != 0 && mSize > mContentLengthLimit);
            if (contentLengthOverLimit) {
                buffer.limit(size - (int) (mSize - mContentLengthLimit));
            }

            while (buffer.hasRemaining()) {
                mSink.write(buffer);
            }
            if (contentLengthOverLimit) {
                onContentLengthOverLimit();
            }
        } catch (Exception e) {
            onCalledByNativeException(e);
        }
    }

    /**
     * Notifies the listener, releases native data structures.
     */
    @SuppressWarnings("unused")
    @CalledByNative
    private void finish() {
        try {
            synchronized (mLock) {
                if (mDisableRedirects) {
                    mHeadersAvailable = true;
                }
                mFinished = true;

                if (mUrlRequestAdapter == 0) {
                    return;
                }
                try {
                    mSink.close();
                } catch (IOException e) {
                    // Ignore
                }
                try {
                    if (mUploadChannel != null && mUploadChannel.isOpen()) {
                        mUploadChannel.close();
                    }
                } catch (IOException e) {
                    // Ignore
                }
                onRequestComplete();
                nativeDestroyRequestAdapter(mUrlRequestAdapter);
                mUrlRequestAdapter = 0;
            }
        } catch (Exception e) {
            mSinkException = new IOException("Exception in finish", e);
        }
    }

    /**
     * Appends header |name| with value |value| to |headersMap|.
     */
    @SuppressWarnings("unused")
    @CalledByNative
    private void onAppendResponseHeader(ResponseHeadersMap headersMap, String name, String value) {
        try {
            if (!headersMap.containsKey(name)) {
                headersMap.put(name, new ArrayList<String>());
            }
            headersMap.get(name).add(value);
        } catch (Exception e) {
            onCalledByNativeException(e);
        }
    }

    /**
     * Reads a sequence of bytes from upload channel into the given buffer.
     * @param dest The buffer into which bytes are to be transferred.
     * @return Returns number of bytes read (could be 0) or -1 and closes
     * the channel if error occured.
     */
    @SuppressWarnings("unused")
    @CalledByNative
    private int readFromUploadChannel(ByteBuffer dest) {
        try {
            if (mUploadChannel == null || !mUploadChannel.isOpen()) return -1;
            int result = mUploadChannel.read(dest);
            if (result < 0) {
                mUploadChannel.close();
                return 0;
            }
            return result;
        } catch (Exception e) {
            onCalledByNativeException(e);
        }
        return -1;
    }

    // Native methods are implemented in chromium_url_request.cc.

    private native long nativeCreateRequestAdapter(
            long urlRequestContextAdapter, String url, int priority);

    private native void nativeAddHeader(long urlRequestAdapter, String name, String value);

    private native void nativeSetMethod(long urlRequestAdapter, String method);

    private native void nativeSetUploadData(
            long urlRequestAdapter, String contentType, byte[] content);

    private native void nativeSetUploadChannel(
            long urlRequestAdapter, String contentType, long contentLength);

    private native void nativeEnableChunkedUpload(long urlRequestAdapter, String contentType);

    private native void nativeDisableRedirects(long urlRequestAdapter);

    private native void nativeAppendChunk(
            long urlRequestAdapter, ByteBuffer chunk, int chunkSize, boolean isLastChunk);

    private native void nativeStart(long urlRequestAdapter);

    private native void nativeCancel(long urlRequestAdapter);

    private native void nativeDestroyRequestAdapter(long urlRequestAdapter);

    private native int nativeGetErrorCode(long urlRequestAdapter);

    private native int nativeGetHttpStatusCode(long urlRequestAdapter);

    private native String nativeGetHttpStatusText(long urlRequestAdapter);

    private native String nativeGetErrorString(long urlRequestAdapter);

    private native String nativeGetContentType(long urlRequestAdapter);

    private native long nativeGetContentLength(long urlRequestAdapter);

    private native String nativeGetHeader(long urlRequestAdapter, String name);

    private native void nativeGetAllHeaders(long urlRequestAdapter, ResponseHeadersMap headers);

    private native String nativeGetNegotiatedProtocol(long urlRequestAdapter);

    private native boolean nativeGetWasCached(long urlRequestAdapter);

    // Explicit class to work around JNI-generator generics confusion.
    private static class ResponseHeadersMap extends HashMap<String, List<String>> {}
}
