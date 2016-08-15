// Copyright 2016 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.net;

/**
 * Subclass of {@link UrlRequestException} which contains a detailed
 * <a href="https://www.chromium.org/quic">QUIC</a> error code from <a
 * href=https://cs.chromium.org/chromium/src/net/quic/quic_protocol.h?type=cs&q=%22enum+QuicErrorCode+%7B%22+file:src/net/quic/quic_protocol.h>
 * QuicErrorCode</a>. An instance of {@code QuicException} is passed to {@code onFailed} callbacks
 * when the error code is {@link UrlRequestException#ERROR_QUIC_PROTOCOL_FAILED
 * UrlRequestException.ERROR_QUIC_PROTOCOL_FAILED}.
 */
public class QuicException extends CronetException {
    private final int mQuicDetailedErrorCode;

    /**
     * Constructs an exception with a specific error.
     *
     * @param message explanation of failure.
     * @param netErrorCode Error code from
     * <a href=https://chromium.googlesource.com/chromium/src/+/master/net/base/net_error_list.h>
     * this list</a>.
     * @param quicDetailedErrorCode Detailed <a href="https://www.chromium.org/quic">QUIC</a> error
     * code from <a
     * href=https://cs.chromium.org/chromium/src/net/quic/quic_protocol.h?type=cs&q=%22enum+QuicErrorCode+%7B%22+file:src/net/quic/quic_protocol.h>
     * QuicErrorCode</a>.
     */
    public QuicException(String message, int netErrorCode, int quicDetailedErrorCode) {
        super(message, ERROR_QUIC_PROTOCOL_FAILED, netErrorCode);
        mQuicDetailedErrorCode = quicDetailedErrorCode;
    }

    /**
     * Returns the <a href="https://www.chromium.org/quic">QUIC</a> error code, which is a value
     * from <a
     * href=https://cs.chromium.org/chromium/src/net/quic/quic_protocol.h?type=cs&q=%22enum+QuicErrorCode+%7B%22+file:src/net/quic/quic_protocol.h>
     * QuicErrorCode</a>.
     */
    public int getQuicDetailedErrorCode() {
        return mQuicDetailedErrorCode;
    }
}
