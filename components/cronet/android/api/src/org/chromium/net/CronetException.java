// Copyright 2015 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.net;

/**
 * Exception reported from UrlRequest or BidirectionalStream.
 */
// TODO(mef): Will replace UrlRequestException soon.
public class CronetException extends UrlRequestException {
    /**
     * @hide only used by internal implementation.
     */
    public CronetException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * @hide only used by internal implementation.
     */
    public CronetException(String message, int errorCode, int cronetInternalErrorCode) {
        super(message, errorCode, cronetInternalErrorCode);
    }
}
