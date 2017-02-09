// Copyright 2014 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.net;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.util.List;
import java.util.Map;

/**
 * HTTP request (GET or POST).
 * @deprecated Use {@link UrlRequest} instead.
 */
@Deprecated
public interface HttpUrlRequest {

    public static final int REQUEST_PRIORITY_IDLE = 0;

    public static final int REQUEST_PRIORITY_LOWEST = 1;

    public static final int REQUEST_PRIORITY_LOW = 2;

    public static final int REQUEST_PRIORITY_MEDIUM = 3;

    public static final int REQUEST_PRIORITY_HIGHEST = 4;

    /**
     * Returns the URL associated with this request.
     */
    String getUrl();

    /**
     * Requests a range starting at the given offset to the end of the resource.
     * The server may or may not honor the offset request. The client must check
     * the HTTP status code before processing the response.
     */
    void setOffset(long offset);

    /**
     * Limits the size of the download.
     *
     * @param limit Maximum size of the downloaded response (post gzip)
     * @param cancelEarly If true, cancel the download as soon as the size of
     *            the response is known. If false, download {@code responseSize}
     *            bytes and then cancel.
     */
    void setContentLengthLimit(long limit, boolean cancelEarly);

    /**
     * Sets data to upload as part of a POST request.
     *
     * @param contentType MIME type of the post content or null if this is not a
     *            POST.
     * @param data The content that needs to be uploaded if this is a POST
     *            request.
     */
    void setUploadData(String contentType, byte[] data);

    /**
     * Sets a readable byte channel to upload as part of a POST request.
     *
     * <p>Once {@link #start()} is called, this channel is guaranteed to be
     * closed, either when the upload completes, or when it is canceled.
     *
     * @param contentType MIME type of the post content or null if this is not a
     *            POST.
     * @param channel The channel to read to read upload data from if this is a
     *            POST request.
     * @param contentLength The length of data to upload.
     */
    void setUploadChannel(String contentType, ReadableByteChannel channel,
                          long contentLength);

    /**
     * Sets the HTTP method verb to use for this request.
     *
     * <p>The default when this method is not called is "GET" if the request has
     * no body or "POST" if it does.
     *
     * @param method "GET", "POST", etc. Must be all uppercase.
     */
    void setHttpMethod(String method);

    /**
     * Disables redirect for this request.
     */
    void disableRedirects();

    /**
     * Start executing the request.
     * <p>
     * If this is a streaming upload request using a ReadableByteChannel, the
     * call will block while the request is uploaded.
     */
    void start();

    /**
     * Cancel the request in progress.
     */
    void cancel();

    /**
     * Returns {@code true} if the request has been canceled.
     */
    boolean isCanceled();

    /**
     * Returns protocol (e.g. "quic/1+spdy/3") negotiated with server. Returns
     * empty string if no protocol was negotiated, or the protocol is not known.
     * Returns empty when using plain http or https. Must be called after
     * onResponseStarted but before request is recycled.
     */
    String getNegotiatedProtocol();

    /**
     * Returns whether the response is serviced from the cache.
     */
    boolean wasCached();

    /**
     * Returns the entire response as a ByteBuffer.
     */
    ByteBuffer getByteBuffer();

    /**
     * Returns the entire response as a byte array.
     */
    byte[] getResponseAsBytes();

    /**
     * Returns the expected content length. It is not guaranteed to be correct
     * and may be -1 if the content length is unknown.
     */
    long getContentLength();

    /**
     * Returns the content MIME type if known or {@code null} otherwise.
     */
    String getContentType();

    /**
     * Returns the HTTP status code. It may be 0 if the request has not started
     * or failed before getting the status code from the server. If the status
     * code is 206 (partial response) after {@link #setOffset} is called, the
     * method returns 200.
     */
    int getHttpStatusCode();

    /**
     * Returns the HTTP status text of the status line. For example, if the
     * request has a "HTTP/1.1 200 OK" response, this method returns "OK". It
     * returns null if the request has not started.
     */
    String getHttpStatusText();

    /**
     * Returns the response header value for the given name or {@code null} if
     * not found.
     */
    String getHeader(String name);

    /**
     * Returns an unmodifiable map of the response-header fields and values.
     * The null key is mapped to the HTTP status line for compatibility with
     * HttpUrlConnection.
     */
    Map<String, List<String>> getAllHeaders();

    /**
     * Returns the exception that occurred while executing the request of null
     * if the request was successful.
     */
    IOException getException();
}
