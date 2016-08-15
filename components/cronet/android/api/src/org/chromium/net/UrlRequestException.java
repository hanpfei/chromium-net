// Copyright 2014 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.net;

import java.io.IOException;

/**
 * Exception passed to {@link UrlRequest.Callback#onFailed UrlRequest.Callback.onFailed()} when:
 * <ul>
 * <li>{@link UrlRequest.Callback} or {@link UploadDataProvider} method throws an exception. In this
 *     case {@link IOException#getCause getCause()} can be used to find the thrown exception.
 *     {@link #getErrorCode} will return {@link #ERROR_LISTENER_EXCEPTION_THROWN}.
 * <li>Cronet fails to process a network request. In this case
 *     {@link #getErrorCode} and {@link #getCronetInternalErrorCode} can be used to get more
 *     information about the specific type of failure. If {@link #getErrorCode}
 *     returns {@link #ERROR_QUIC_PROTOCOL_FAILED}, this exception can be cast to a
 *     {@link QuicException} which can provide further details.
 * </ul>
 */
public class UrlRequestException extends IOException {
    /**
     * Error code indicating this class wraps an exception thrown by {@link UrlRequest.Callback} or
     * {@link UploadDataProvider}. Wrapped exception can be retrieved using
     * {@link IOException#getCause}.
     */
    public static final int ERROR_LISTENER_EXCEPTION_THROWN =
            UrlRequestError.LISTENER_EXCEPTION_THROWN;
    /**
     * Error code indicating the host being sent the request could not be resolved to an IP address.
     */
    public static final int ERROR_HOSTNAME_NOT_RESOLVED = UrlRequestError.HOSTNAME_NOT_RESOLVED;
    /**
     * Error code indicating the device was not connected to any network.
     */
    public static final int ERROR_INTERNET_DISCONNECTED = UrlRequestError.INTERNET_DISCONNECTED;
    /**
     * Error code indicating that as the request was processed the network configuration changed.
     */
    public static final int ERROR_NETWORK_CHANGED = UrlRequestError.NETWORK_CHANGED;
    /**
     * Error code indicating a timeout expired. Timeouts expiring while attempting to connect will
     * be reported as the more specific {@link #ERROR_CONNECTION_TIMED_OUT}.
     */
    public static final int ERROR_TIMED_OUT = UrlRequestError.TIMED_OUT;
    /**
     * Error code indicating the connection was closed unexpectedly.
     */
    public static final int ERROR_CONNECTION_CLOSED = UrlRequestError.CONNECTION_CLOSED;
    /**
     * Error code indicating the connection attempt timed out.
     */
    public static final int ERROR_CONNECTION_TIMED_OUT = UrlRequestError.CONNECTION_TIMED_OUT;
    /**
     * Error code indicating the connection attempt was refused.
     */
    public static final int ERROR_CONNECTION_REFUSED = UrlRequestError.CONNECTION_REFUSED;
    /**
     * Error code indicating the connection was unexpectedly reset.
     */
    public static final int ERROR_CONNECTION_RESET = UrlRequestError.CONNECTION_RESET;
    /**
     * Error code indicating the IP address being contacted is unreachable, meaning there is no
     * route to the specified host or network.
     */
    public static final int ERROR_ADDRESS_UNREACHABLE = UrlRequestError.ADDRESS_UNREACHABLE;
    /**
     * Error code indicating an error related to the <a href="https://www.chromium.org/quic">
     * QUIC</a> protocol. When {@link #getErrorCode} returns this code, this exception can be cast
     * to {@link QuicException} for more information.
     */
    public static final int ERROR_QUIC_PROTOCOL_FAILED = UrlRequestError.QUIC_PROTOCOL_FAILED;
    /**
     * Error code indicating another type of error was encountered.
     * {@link #getCronetInternalErrorCode} can be consulted to get a more specific cause.
     */
    public static final int ERROR_OTHER = UrlRequestError.OTHER;

    // Error code, one of ERROR_*
    private final int mErrorCode;
    // Cronet internal error code.
    private final int mCronetInternalErrorCode;

    /**
     * Constructs an exception with a specific error.
     *
     * @param message explanation of failure.
     * @param errorCode error code, one of {@link #ERROR_LISTENER_EXCEPTION_THROWN ERROR_*}.
     * @param cronetInternalErrorCode Cronet internal error code, one of
     * <a href=https://chromium.googlesource.com/chromium/src/+/master/net/base/net_error_list.h>
     * these</a>.
     */
    public UrlRequestException(String message, int errorCode, int cronetInternalErrorCode) {
        super(message, null);
        mErrorCode = errorCode;
        mCronetInternalErrorCode = cronetInternalErrorCode;
    }

    /**
     * Constructs an exception that wraps {@code cause} thrown by a {@link UrlRequest.Callback}.
     *
     * @param message explanation of failure.
     * @param cause exception thrown by {@link UrlRequest.Callback} that's being wrapped.
     */
    public UrlRequestException(String message, Throwable cause) {
        super(message, cause);
        mErrorCode = ERROR_LISTENER_EXCEPTION_THROWN;
        mCronetInternalErrorCode = 0;
    }

    /**
     * Returns error code, one of {@link #ERROR_LISTENER_EXCEPTION_THROWN ERROR_*}.
     *
     * @return error code, one of {@link #ERROR_LISTENER_EXCEPTION_THROWN ERROR_*}.
     */
    public int getErrorCode() {
        return mErrorCode;
    }

    /**
     * Returns a Cronet internal error code. This may provide more specific error
     * diagnosis than {@link #getErrorCode}, but the constant values are not exposed to Java and
     * may change over time. See
     * <a href=https://chromium.googlesource.com/chromium/src/+/master/net/base/net_error_list.h>
     * here</a> for the lastest list of values.
     *
     * @return Cronet internal error code.
     */
    public int getCronetInternalErrorCode() {
        return mCronetInternalErrorCode;
    }

    /**
     * Returns {@code true} if retrying this request right away might succeed, {@code false}
     * otherwise. For example returns {@code true} when {@link #getErrorCode} returns
     * {@link #ERROR_NETWORK_CHANGED} because trying the request might succeed using the new
     * network configuration, but {@code false} when {@code getErrorCode()} returns
     * {@link #ERROR_INTERNET_DISCONNECTED} because retrying the request right away will
     * encounter the same failure (instead retrying should be delayed until device regains
     * network connectivity). Returns {@code false} when {@code getErrorCode()} returns
     * {@link #ERROR_LISTENER_EXCEPTION_THROWN}.
     *
     * @return {@code true} if retrying this request right away might succeed, {@code false}
     *         otherwise.
     */
    public boolean immediatelyRetryable() {
        switch (mErrorCode) {
            case ERROR_LISTENER_EXCEPTION_THROWN:
            case ERROR_HOSTNAME_NOT_RESOLVED:
            case ERROR_INTERNET_DISCONNECTED:
            case ERROR_CONNECTION_REFUSED:
            case ERROR_ADDRESS_UNREACHABLE:
            case ERROR_OTHER:
            default:
                return false;
            case ERROR_NETWORK_CHANGED:
            case ERROR_TIMED_OUT:
            case ERROR_CONNECTION_CLOSED:
            case ERROR_CONNECTION_TIMED_OUT:
            case ERROR_CONNECTION_RESET:
                return true;
        }
    }
}
