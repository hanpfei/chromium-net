// Copyright 2015 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.net;

import android.annotation.SuppressLint;
import android.support.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.ByteBuffer;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Class for bidirectional sending and receiving of data over HTTP/2 or QUIC connections.
 * Created using {@link BidirectionalStream.Builder}.
 *
 * Note: There are ordering restrictions on methods of {@link BidirectionalStream};
 * please see individual methods for description of restrictions.
 */
public abstract class BidirectionalStream {
    /**
     * Builder for {@link BidirectionalStream}s. Allows configuring stream before constructing
     * it via {@link Builder#build}.
     */
    public static class Builder {
        // All fields are temporary storage of BidirectionalStream configuration to be
        // copied to BidirectionalStream.

        // CronetEngine to create the stream.
        private final CronetEngine mCronetEngine;
        // URL to request.
        private final String mUrl;
        // Callback to receive progress callbacks.
        private final Callback mCallback;
        // Executor on which callbacks will be invoked.
        private final Executor mExecutor;
        // List of request headers, stored as header field name and value pairs.
        private final ArrayList<Map.Entry<String, String>> mRequestHeaders =
                new ArrayList<Map.Entry<String, String>>();

        // HTTP method for the request. Default to POST.
        private String mHttpMethod = "POST";
        // Priority of the stream. Default is medium.
        @StreamPriority private int mPriority = STREAM_PRIORITY_MEDIUM;

        // TODO(xunjieli): Remove mDisableAutoFlush and make flush() required as part of th API.
        private boolean mDisableAutoFlush;
        private boolean mDelayRequestHeadersUntilFirstFlush;

        /**
         * Creates a builder for {@link BidirectionalStream} objects. All callbacks for
         * generated {@code BidirectionalStream} objects will be invoked on
         * {@code executor}. {@code executor} must not run tasks on the
         * current thread, otherwise the networking operations may block and exceptions
         * may be thrown at shutdown time.
         *
         * @param url the URL for the generated stream
         * @param callback the {@link Callback} object that gets invoked upon different events
         *     occuring
         * @param executor the {@link Executor} on which {@code callback} methods will be invoked
         * @param cronetEngine the {@link CronetEngine} used to create the stream
         */
        public Builder(
                String url, Callback callback, Executor executor, CronetEngine cronetEngine) {
            if (url == null) {
                throw new NullPointerException("URL is required.");
            }
            if (callback == null) {
                throw new NullPointerException("Callback is required.");
            }
            if (executor == null) {
                throw new NullPointerException("Executor is required.");
            }
            if (cronetEngine == null) {
                throw new NullPointerException("CronetEngine is required.");
            }
            mUrl = url;
            mCallback = callback;
            mExecutor = executor;
            mCronetEngine = cronetEngine;
        }

        /**
         * Sets the HTTP method for the request. Returns builder to facilitate chaining.
         *
         * @param method the method to use for request. Default is 'POST'
         * @return the builder to facilitate chaining
         */
        public Builder setHttpMethod(String method) {
            if (method == null) {
                throw new NullPointerException("Method is required.");
            }
            mHttpMethod = method;
            return this;
        }

        /**
         * Adds a request header. Returns builder to facilitate chaining.
         *
         * @param header the header name
         * @param value the header value
         * @return the builder to facilitate chaining
         */
        public Builder addHeader(String header, String value) {
            if (header == null) {
                throw new NullPointerException("Invalid header name.");
            }
            if (value == null) {
                throw new NullPointerException("Invalid header value.");
            }
            mRequestHeaders.add(
                    new AbstractMap.SimpleImmutableEntry<String, String>(header, value));
            return this;
        }

        /** @hide */
        @IntDef({
                STREAM_PRIORITY_IDLE, STREAM_PRIORITY_LOWEST, STREAM_PRIORITY_LOW,
                STREAM_PRIORITY_MEDIUM, STREAM_PRIORITY_HIGHEST,
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface StreamPriority {}

        /**
         * Lowest stream priority. Passed to {@link #setPriority}.
         */
        public static final int STREAM_PRIORITY_IDLE = 0;
        /**
         * Very low stream priority. Passed to {@link #setPriority}.
         */
        public static final int STREAM_PRIORITY_LOWEST = 1;
        /**
         * Low stream priority. Passed to {@link #setPriority}.
         */
        public static final int STREAM_PRIORITY_LOW = 2;
        /**
         * Medium stream priority. Passed to {@link #setPriority}. This is the
         * default priority given to the stream.
         */
        public static final int STREAM_PRIORITY_MEDIUM = 3;
        /**
         * Highest stream priority. Passed to {@link #setPriority}.
         */
        public static final int STREAM_PRIORITY_HIGHEST = 4;

        /**
         * Sets priority of the stream which should be one of the
         * {@link #STREAM_PRIORITY_IDLE STREAM_PRIORITY_*} values.
         * The stream is given {@link #STREAM_PRIORITY_MEDIUM} priority if {@link
         * #setPriority} is not called.
         *
         * @param priority priority of the stream which should be one of the
         *         {@link #STREAM_PRIORITY_IDLE STREAM_PRIORITY_*} values.
         * @return the builder to facilitate chaining.
         */
        public Builder setPriority(@StreamPriority int priority) {
            mPriority = priority;
            return this;
        }

        /**
         * Disables or enables auto flush. By default, data is flushed after
         * every {@link #write write()}. If the auto flush is disabled, the
         * client should explicitly call {@link #flush flush()} to flush the data.
         *
         * @param disableAutoFlush if true, auto flush will be disabled.
         * @return the builder to facilitate chaining.
         */
        public Builder disableAutoFlush(boolean disableAutoFlush) {
            mDisableAutoFlush = disableAutoFlush;
            return this;
        }

        /**
         * Delays sending request headers until {@link BidirectionalStream#flush()}
         * is called. This flag is currently only respected when QUIC is negotiated.
         * When true, QUIC will send request header frame along with data frame(s)
         * as a single packet when possible.
         *
         * @param delayRequestHeadersUntilFirstFlush if true, sending request headers will
         *         be delayed until flush() is called.
         * @return the builder to facilitate chaining.
         */
        public Builder delayRequestHeadersUntilFirstFlush(
                boolean delayRequestHeadersUntilFirstFlush) {
            mDelayRequestHeadersUntilFirstFlush = delayRequestHeadersUntilFirstFlush;
            return this;
        }

        /**
         * Creates a {@link BidirectionalStream} using configuration from this
         * {@link Builder}. The returned {@code BidirectionalStream} can then be started
         * by calling {@link BidirectionalStream#start}.
         *
         * @return constructed {@link BidirectionalStream} using configuration from
         *         this {@link Builder}
         */
        @SuppressLint("WrongConstant") // TODO(jbudorick): Remove this after rolling to the N SDK.
        public BidirectionalStream build() {
            return mCronetEngine.createBidirectionalStream(mUrl, mCallback, mExecutor, mHttpMethod,
                    mRequestHeaders, mPriority, mDisableAutoFlush,
                    mDelayRequestHeadersUntilFirstFlush);
        }
    }

    /**
     * Callback class used to receive callbacks from a {@link BidirectionalStream}.
     */
    public abstract static class Callback {
        /**
         * Invoked when the stream is ready for reading and writing.
         * Consumer may call {@link BidirectionalStream#read read()} to start reading data.
         * Consumer may call {@link BidirectionalStream#write write()} to start writing data.
         *
         * @param stream the stream that is ready.
         */
        public abstract void onStreamReady(BidirectionalStream stream);

        /**
         * Invoked when initial response headers are received. Headers are available from
         * {@code info.}{@link UrlResponseInfo#getAllHeaders getAllHeaders()}.
         * Consumer may call {@link BidirectionalStream#read read()} to start reading.
         * Consumer may call {@link BidirectionalStream#write write()} to start writing or close the
         * stream.
         *
         * @param stream the stream on which response headers were received.
         * @param info the response information.
         */
        public abstract void onResponseHeadersReceived(
                BidirectionalStream stream, UrlResponseInfo info);

        /**
         * Invoked when data is read into the buffer passed to {@link BidirectionalStream#read
         * read()}. Only part of the buffer may be populated. To continue reading, call {@link
         * BidirectionalStream#read read()}. It may be invoked after {@code
         * onResponseTrailersReceived()}, if there was pending read data before trailers were
         * received.
         *
         * @param stream the stream on which the read completed
         * @param info the response information
         * @param buffer the buffer that was passed to {@link BidirectionalStream#read read()},
         *     now containing the received data. The buffer's limit is not changed.
         *     The buffer's position is set to the end of the received data. If position is not
         *     updated, it means the remote side has signaled that it will send no more data.
         * @param endOfStream if true, this is the last read data, remote will not send more data,
         *     and the read side is closed.
         *
         */
        public abstract void onReadCompleted(BidirectionalStream stream, UrlResponseInfo info,
                ByteBuffer buffer, boolean endOfStream);

        /**
         * Invoked when the entire ByteBuffer passed to {@link BidirectionalStream#write write()}
         * is sent. The buffer's position is updated to be the same as the buffer's limit.
         * The buffer's limit is not changed. To continue writing, call
         * {@link BidirectionalStream#write write()}.
         *
         * @param stream the stream on which the write completed
         * @param info the response information
         * @param buffer the buffer that was passed to {@link BidirectionalStream#write write()}.
         *     The buffer's position is set to the buffer's limit. The buffer's limit
         *     is not changed.
         * @param endOfStream the endOfStream flag that was passed to the corresponding
         *     {@link BidirectionalStream#write write()}. If true, the write side is closed.
         */
        public abstract void onWriteCompleted(BidirectionalStream stream, UrlResponseInfo info,
                ByteBuffer buffer, boolean endOfStream);

        /**
         * Invoked when trailers are received before closing the stream. Only invoked
         * when server sends trailers, which it may not. May be invoked while there is read data
         * remaining in local buffer.
         *
         * Default implementation takes no action.
         *
         * @param stream the stream on which response trailers were received
         * @param info the response information
         * @param trailers the trailers received
         */
        public void onResponseTrailersReceived(BidirectionalStream stream, UrlResponseInfo info,
                UrlResponseInfo.HeaderBlock trailers) {}

        /**
         * Invoked when there is no data to be read or written and the stream is closed successfully
         * remotely and locally. Once invoked, no further {@link BidirectionalStream.Callback}
         * methods will be invoked.
         *
         * @param stream the stream which is closed successfully
         * @param info the response information
         */
        public abstract void onSucceeded(BidirectionalStream stream, UrlResponseInfo info);

        /**
         * Invoked if the stream failed for any reason after {@link BidirectionalStream#start}.
         * <a href="https://tools.ietf.org/html/rfc7540#section-7">HTTP/2 error codes</a> are
         * mapped to {@link UrlRequestException#getCronetInternalErrorCode} codes. Once invoked,
         * no further {@link BidirectionalStream.Callback} methods will be invoked.
         *
         * @param stream the stream which has failed
         * @param info the response information. May be {@code null} if no response was
         *     received.
         * @param error information about the failure
         */
        public abstract void onFailed(
                BidirectionalStream stream, UrlResponseInfo info, CronetException error);

        /**
         * Invoked if the stream was canceled via {@link BidirectionalStream#cancel}. Once
         * invoked, no further {@link BidirectionalStream.Callback} methods will be invoked.
         * Default implementation takes no action.
         *
         * @param stream the stream that was canceled
         * @param info the response information. May be {@code null} if no response was
         *     received.
         */
        public void onCanceled(BidirectionalStream stream, UrlResponseInfo info) {}
    }

    /**
     * Starts the stream, all callbacks go to the {@code callback} argument passed to {@link
     * BidirectionalStream.Builder}'s constructor. Should only be called once.
     */
    public abstract void start();

    /**
     * Reads data from the stream into the provided buffer.
     * Can only be called at most once in response to each invocation of the
     * {@link Callback#onStreamReady onStreamReady()}/
     * {@link Callback#onResponseHeadersReceived onResponseHeadersReceived()} and {@link
     * Callback#onReadCompleted onReadCompleted()} methods of the {@link
     * Callback}. Each call will result in an invocation of one of the
     * {@link Callback Callback}'s {@link Callback#onReadCompleted onReadCompleted()}
     * method if data is read, or its {@link Callback#onFailed onFailed()} method if
     * there's an error.
     *
     * An attempt to read data into {@code buffer} starting at {@code
     * buffer.position()} is begun. At most {@code buffer.remaining()} bytes are
     * read. {@code buffer.position()} is updated upon invocation of {@link
     * Callback#onReadCompleted onReadCompleted()} to indicate how much data was read.
     *
     * @param buffer the {@link ByteBuffer} to read data into. Must be a
     *     direct ByteBuffer. The embedder must not read or modify buffer's
     *     position, limit, or data between its position and limit until
     *     {@link Callback#onReadCompleted onReadCompleted()}, {@link Callback#onCanceled
     *     onCanceled()}, or {@link Callback#onFailed onFailed()} are invoked.
     */
    public abstract void read(ByteBuffer buffer);

    /**
     * Attempts to write data from the provided buffer into the stream.
     * If auto flush is disabled, data will be sent only after {@link #flush flush()} is called.
     * Each call will result in an invocation of one of the
     * {@link Callback Callback}'s {@link Callback#onWriteCompleted onWriteCompleted()}
     * method if data is sent, or its {@link Callback#onFailed onFailed()} method if
     * there's an error.
     *
     * An attempt to write data from {@code buffer} starting at {@code buffer.position()}
     * is begun. {@code buffer.remaining()} bytes will be written.
     * {@link Callback#onWriteCompleted onWriteCompleted()} will be invoked only when the
     * full ByteBuffer is written.
     *
     * @param buffer the {@link ByteBuffer} to write data from. Must be a
     *     direct ByteBuffer. The embedder must not read or modify buffer's
     *     position, limit, or data between its position and limit until
     *     {@link Callback#onWriteCompleted onWriteCompleted()}, {@link Callback#onCanceled
     *     onCanceled()}, or {@link Callback#onFailed onFailed()} are invoked. Can be empty
     *     when {@code endOfStream} is {@code true}.
     * @param endOfStream if {@code true}, then {@code buffer} is the last buffer to be written,
     *     and once written, stream is closed from the client side, resulting in half-closed
     *     stream or a fully closed stream if the remote side has already closed.
     */
    public abstract void write(ByteBuffer buffer, boolean endOfStream);

    /**
     * Flushes pending writes. This method should not be invoked before {@link
     * Callback#onStreamReady onStreamReady()}. For previously delayed {@link
     * #write write()}s, a corresponding {@link Callback#onWriteCompleted onWriteCompleted()}
     * will be invoked when the buffer is sent.
     */
    public abstract void flush();

    /**
     * Cancels the stream. Can be called at any time after {@link #start}.
     * {@link Callback#onCanceled onCanceled()} will be invoked when cancelation
     * is complete and no further callback methods will be invoked. If the
     * stream has completed or has not started, calling {@code cancel()} has no
     * effect and {@code onCanceled()} will not be invoked. If the
     * {@link Executor} passed in during {@code BidirectionalStream} construction runs
     * tasks on a single thread, and {@code cancel()} is called on that thread,
     * no listener methods (besides {@code onCanceled()}) will be invoked after
     * {@code cancel()} is called. Otherwise, at most one callback method may be
     * invoked after {@code cancel()} has completed.
     */
    public abstract void cancel();

    /**
     * Returns {@code true} if the stream was successfully started and is now
     * done (succeeded, canceled, or failed).
     *
     * @return {@code true} if the stream was successfully started and is now
     *         done (completed, canceled, or failed), otherwise returns {@code false}
     *         to indicate stream is not yet started or is in progress.
     */
    public abstract boolean isDone();
}
