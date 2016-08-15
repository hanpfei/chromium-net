// Copyright 2014 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.net;

/**
 * Callback interface.
 * @deprecated Use {@link UrlRequest.Callback} instead.
 */
@Deprecated
public interface HttpUrlRequestListener {
    /**
     * A callback invoked when the first chunk of the response has arrived and
     * response headers have been read.  The listener can only call request
     * getHeader, getContentType and getContentLength. This method will always
     * be called before {@code onRequestComplete}.
     */
    void onResponseStarted(HttpUrlRequest request);

    /**
     * The listener should completely process the response in the callback
     * method. Immediately after the callback, the request object will be
     * recycled.
     */
    void onRequestComplete(HttpUrlRequest request);
}
