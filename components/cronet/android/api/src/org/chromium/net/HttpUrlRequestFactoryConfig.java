// Copyright 2014 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.net;

/**
 * A config for HttpUrlRequestFactory, which allows runtime configuration of
 * HttpUrlRequestFactory.
 * @deprecated Use {@link CronetEngine.Builder} instead.
 */
@Deprecated
public class HttpUrlRequestFactoryConfig extends UrlRequestContextConfig {

    /**
     * Default config enables SPDY, QUIC, in memory http cache.
     */
    public HttpUrlRequestFactoryConfig() {
        super();
    }
}
