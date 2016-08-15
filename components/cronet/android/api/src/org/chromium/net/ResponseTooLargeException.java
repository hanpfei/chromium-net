// Copyright 2014 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.net;

import java.io.IOException;

/**
 * Exception returned if a {@code HttpUrlRequest} attempts to download a
 * response that exceeds the user-specified limit.
 * @deprecated This is no longer used in the async API.
 */
@SuppressWarnings("serial")
@Deprecated
public class ResponseTooLargeException extends IOException {
    public ResponseTooLargeException() {
    }
}
