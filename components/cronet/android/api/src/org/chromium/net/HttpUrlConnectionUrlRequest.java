// Copyright 2014 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.net;

import android.content.Context;
import android.text.TextUtils;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ProtocolException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;

/**
 * Network request using the HttpUrlConnection implementation.
 * @deprecated Use {@link UrlRequest} instead.
 */
@Deprecated
class HttpUrlConnectionUrlRequest implements HttpUrlRequest {

    private static final int MAX_CHUNK_SIZE = 8192;

    private static final int CONNECT_TIMEOUT = 3000;

    private static final int READ_TIMEOUT = 90000;

    private final Context mContext;

    private final String mDefaultUserAgent;

    private final String mUrl;

    private final Map<String, String> mHeaders;

    private final WritableByteChannel mSink;

    private final HttpUrlRequestListener mListener;

    private IOException mException;

    private HttpURLConnection mConnection;

    private long mOffset;

    private int mContentLength;

    private int mUploadContentLength;

    private long mContentLengthLimit;

    private boolean mCancelIfContentLengthOverLimit;

    private boolean mContentLengthOverLimit;

    private boolean mSkippingToOffset;

    private long mSize;

    private String mPostContentType;

    private byte[] mPostData;

    private ReadableByteChannel mPostDataChannel;

    private String mContentType;

    private int mHttpStatusCode;

    private String mHttpStatusText;

    private boolean mStarted;

    private boolean mCanceled;

    private String mMethod;

    private InputStream mResponseStream;

    private final Object mLock;

    private static ExecutorService sExecutorService;

    private static final Object sExecutorServiceLock = new Object();

    HttpUrlConnectionUrlRequest(Context context, String defaultUserAgent,
            String url, int requestPriority, Map<String, String> headers,
            HttpUrlRequestListener listener) {
        this(context, defaultUserAgent, url, requestPriority, headers,
                new ChunkedWritableByteChannel(), listener);
    }

    HttpUrlConnectionUrlRequest(Context context, String defaultUserAgent,
            String url, int requestPriority, Map<String, String> headers,
            WritableByteChannel sink, HttpUrlRequestListener listener) {
        if (context == null) {
            throw new NullPointerException("Context is required");
        }
        if (url == null) {
            throw new NullPointerException("URL is required");
        }
        mContext = context;
        mDefaultUserAgent = defaultUserAgent;
        mUrl = url;
        mHeaders = headers;
        mSink = sink;
        mListener = listener;
        mLock = new Object();
    }

    private static ExecutorService getExecutor() {
        synchronized (sExecutorServiceLock) {
            if (sExecutorService == null) {
                ThreadFactory threadFactory = new ThreadFactory() {
                    private final AtomicInteger mCount = new AtomicInteger(1);

                        @Override
                    public Thread newThread(Runnable r) {
                        Thread thread = new Thread(r,
                                "HttpUrlConnection #"
                                + mCount.getAndIncrement());
                        // Note that this thread is not doing actual networking.
                        // It's only a controller.
                        thread.setPriority(Thread.NORM_PRIORITY);
                        return thread;
                    }
                };
                sExecutorService = Executors.newCachedThreadPool(threadFactory);
            }
            return sExecutorService;
        }
    }

    @Override
    public String getUrl() {
        return mUrl;
    }

    @Override
    public void setOffset(long offset) {
        mOffset = offset;
    }

    @Override
    public void setContentLengthLimit(long limit, boolean cancelEarly) {
        mContentLengthLimit = limit;
        mCancelIfContentLengthOverLimit = cancelEarly;
    }

    @Override
    public void setUploadData(String contentType, byte[] data) {
        validateNotStarted();
        mPostContentType = contentType;
        mPostData = data;
        mPostDataChannel = null;
    }

    @Override
    public void setUploadChannel(String contentType,
            ReadableByteChannel channel, long contentLength) {
        validateNotStarted();
        if (contentLength > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                "Upload contentLength is too big.");
        }
        mUploadContentLength = (int) contentLength;
        mPostContentType = contentType;
        mPostDataChannel = channel;
        mPostData = null;
    }


    @Override
    public void setHttpMethod(String method) {
        validateNotStarted();
        mMethod = method;
    }

    @Override
    public void disableRedirects() {
        validateNotStarted();
        HttpURLConnection.setFollowRedirects(false);
    }

    @Override
    public void start() {
        getExecutor().execute(new Runnable() {
            @Override
            public void run() {
                startOnExecutorThread();
            }
        });
    }

    private void startOnExecutorThread() {
        boolean readingResponse = false;
        try {
            synchronized (mLock) {
                if (mCanceled) {
                    return;
                }
            }

            URL url = new URL(mUrl);
            mConnection = (HttpURLConnection) url.openConnection();
            // If configured, use the provided http verb.
            if (mMethod != null) {
                try {
                    mConnection.setRequestMethod(mMethod);
                } catch (ProtocolException e) {
                    // Since request hasn't started earlier, it
                    // must be an illegal HTTP verb.
                    throw new IllegalArgumentException(e);
                }
            }
            mConnection.setConnectTimeout(CONNECT_TIMEOUT);
            mConnection.setReadTimeout(READ_TIMEOUT);
            mConnection.setInstanceFollowRedirects(true);
            if (mHeaders != null) {
                for (Entry<String, String> header : mHeaders.entrySet()) {
                    mConnection.setRequestProperty(header.getKey(),
                            header.getValue());
                }
            }

            if (mOffset != 0) {
                mConnection.setRequestProperty("Range",
                        "bytes=" + mOffset + "-");
            }

            if (mConnection.getRequestProperty("User-Agent") == null) {
                mConnection.setRequestProperty("User-Agent", mDefaultUserAgent);
            }

            if (mPostData != null || mPostDataChannel != null) {
                uploadData();
            }

            InputStream stream = null;
            try {
                // We need to open the stream before asking for the response
                // code.
                stream = mConnection.getInputStream();
            } catch (FileNotFoundException ex) {
                // Ignore - the response has no body.
            }

            mHttpStatusCode = mConnection.getResponseCode();
            mHttpStatusText = mConnection.getResponseMessage();
            mContentType = mConnection.getContentType();
            mContentLength = mConnection.getContentLength();
            if (mContentLengthLimit > 0 && mContentLength > mContentLengthLimit
                    && mCancelIfContentLengthOverLimit) {
                onContentLengthOverLimit();
                return;
            }

            mListener.onResponseStarted(this);

            mResponseStream = isError(mHttpStatusCode) ? mConnection
                    .getErrorStream()
                    : stream;

            if (mResponseStream != null
                    && "gzip".equals(mConnection.getContentEncoding())) {
                mResponseStream = new GZIPInputStream(mResponseStream);
                mContentLength = -1;
            }

            if (mOffset != 0) {
                // The server may ignore the request for a byte range.
                if (mHttpStatusCode == HttpURLConnection.HTTP_OK) {
                    if (mContentLength != -1) {
                        mContentLength -= mOffset;
                    }
                    mSkippingToOffset = true;
                } else {
                    mSize = mOffset;
                }
            }

            if (mResponseStream != null) {
                readingResponse = true;
                readResponseAsync();
            }
        } catch (IOException e) {
            mException = e;
        } finally {
            if (mPostDataChannel != null) {
                try {
                    mPostDataChannel.close();
                } catch (IOException e) {
                    // Ignore
                }
            }

            // Don't call onRequestComplete yet if we are reading the response
            // on a separate thread
            if (!readingResponse) {
                mListener.onRequestComplete(this);
            }
        }
    }

    private void uploadData() throws IOException {
        mConnection.setDoOutput(true);
        if (!TextUtils.isEmpty(mPostContentType)) {
            mConnection.setRequestProperty("Content-Type", mPostContentType);
        }

        OutputStream uploadStream = null;
        try {
            if (mPostData != null) {
                mConnection.setFixedLengthStreamingMode(mPostData.length);
                uploadStream = mConnection.getOutputStream();
                uploadStream.write(mPostData);
            } else {
                mConnection.setFixedLengthStreamingMode(mUploadContentLength);
                uploadStream = mConnection.getOutputStream();
                byte[] bytes = new byte[MAX_CHUNK_SIZE];
                ByteBuffer byteBuffer = ByteBuffer.wrap(bytes);
                while (mPostDataChannel.read(byteBuffer) > 0) {
                    byteBuffer.flip();
                    uploadStream.write(bytes, 0, byteBuffer.limit());
                    byteBuffer.clear();
                }
            }
        } finally {
            if (uploadStream != null) {
                uploadStream.close();
            }
        }
    }

    private void readResponseAsync() {
        getExecutor().execute(new Runnable() {
            @Override
            public void run() {
                readResponse();
            }
        });
    }

    private void readResponse() {
        try {
            if (mResponseStream != null) {
                readResponseStream();
            }
        } catch (IOException e) {
            mException = e;
        } finally {
            try {
                mConnection.disconnect();
            } catch (ArrayIndexOutOfBoundsException t) {
                // Ignore it.
            }

            try {
                mSink.close();
            } catch (IOException e) {
                if (mException == null) {
                    mException = e;
                }
            }
        }
        mListener.onRequestComplete(this);
    }

    private void readResponseStream() throws IOException {
        byte[] buffer = new byte[MAX_CHUNK_SIZE];
        int size;
        while (!isCanceled() && (size = mResponseStream.read(buffer)) != -1) {
            int start = 0;
            int count = size;
            mSize += size;
            if (mSkippingToOffset) {
                if (mSize <= mOffset) {
                    continue;
                } else {
                    mSkippingToOffset = false;
                    start = (int) (mOffset - (mSize - size));
                    count -= start;
                }
            }

            if (mContentLengthLimit != 0 && mSize > mContentLengthLimit) {
                count -= (int) (mSize - mContentLengthLimit);
                if (count > 0) {
                    mSink.write(ByteBuffer.wrap(buffer, start, count));
                }
                onContentLengthOverLimit();
                return;
            }

            mSink.write(ByteBuffer.wrap(buffer, start, count));
        }
    }

    @Override
    public void cancel() {
        synchronized (mLock) {
            if (mCanceled) {
                return;
            }

            mCanceled = true;
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
        return "";
    }

    @Override
    public boolean wasCached() {
        return false;
    }

    @Override
    public int getHttpStatusCode() {
        int httpStatusCode = mHttpStatusCode;

        // If we have been able to successfully resume a previously interrupted
        // download,
        // the status code will be 206, not 200. Since the rest of the
        // application is
        // expecting 200 to indicate success, we need to fake it.
        if (httpStatusCode == HttpURLConnection.HTTP_PARTIAL) {
            httpStatusCode = HttpURLConnection.HTTP_OK;
        }
        return httpStatusCode;
    }

    @Override
    public String getHttpStatusText() {
        return mHttpStatusText;
    }

    @Override
    public IOException getException() {
        if (mException == null && mContentLengthOverLimit) {
            mException = new ResponseTooLargeException();
        }
        return mException;
    }

    private void onContentLengthOverLimit() {
        mContentLengthOverLimit = true;
        cancel();
    }

    private static boolean isError(int statusCode) {
        return (statusCode / 100) != 2;
    }

    /**
     * Returns the response as a ByteBuffer.
     */
    @Override
    public ByteBuffer getByteBuffer() {
        return ((ChunkedWritableByteChannel) mSink).getByteBuffer();
    }

    @Override
    public byte[] getResponseAsBytes() {
        return ((ChunkedWritableByteChannel) mSink).getBytes();
    }

    @Override
    public long getContentLength() {
        return mContentLength;
    }

    @Override
    public String getContentType() {
        return mContentType;
    }

    @Override
    public String getHeader(String name) {
        if (mConnection == null) {
            throw new IllegalStateException("Response headers not available");
        }
        Map<String, List<String>> headerFields = mConnection.getHeaderFields();
        if (headerFields != null) {
            List<String> headerValues = headerFields.get(name);
            if (headerValues != null) {
                return TextUtils.join(", ", headerValues);
            }
        }
        return null;
    }

    @Override
    public Map<String, List<String>> getAllHeaders() {
        if (mConnection == null) {
            throw new IllegalStateException("Response headers not available");
        }
        return mConnection.getHeaderFields();
    }

    private void validateNotStarted() {
        if (mStarted) {
            throw new IllegalStateException("Request already started");
        }
    }
}
