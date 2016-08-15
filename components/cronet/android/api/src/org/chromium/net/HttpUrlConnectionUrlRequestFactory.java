// Copyright 2014 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.net;

import android.content.Context;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.channels.WritableByteChannel;
import java.util.Map;

/**
 * Network request using {@link java.net.HttpURLConnection}.
 * @deprecated Use {@link CronetEngine} instead.
 */
@Deprecated
class HttpUrlConnectionUrlRequestFactory extends HttpUrlRequestFactory {

    private final Context mContext;
    private final String mDefaultUserAgent;

    public HttpUrlConnectionUrlRequestFactory(Context context, CronetEngine.Builder config) {
        mContext = context;
        String userAgent = config.getUserAgent();
        if (userAgent == null) {
            // Cannot use config.getDefaultUserAgent() as config.mContext may be null.
            userAgent = new CronetEngine.Builder(mContext).getDefaultUserAgent();
        }
        mDefaultUserAgent = userAgent;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public String getName() {
        return "HttpUrlConnection/" + ApiVersion.getVersion();
    }

    @Override
    public HttpUrlRequest createRequest(String url, int requestPriority,
            Map<String, String> headers, HttpUrlRequestListener listener) {
        return new HttpUrlConnectionUrlRequest(mContext, mDefaultUserAgent, url,
                requestPriority, headers, listener);
    }

    @Override
    public HttpUrlRequest createRequest(String url, int requestPriority,
            Map<String, String> headers, WritableByteChannel channel,
            HttpUrlRequestListener listener) {
        return new HttpUrlConnectionUrlRequest(mContext, mDefaultUserAgent, url,
                requestPriority, headers, channel, listener);
    }

    @Override
    public void startNetLogToFile(String fileName, boolean logAll) {
        try {
            PrintWriter out = new PrintWriter(fileName);
            out.println("NetLog is not supported by " + getName());
            out.close();
        } catch (IOException e) {
            // Ignore any exceptions.
        }
    }

    @Override
    public void stopNetLog() {
    }
}
