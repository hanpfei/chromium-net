// Copyright 2014 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.net.impl;

import android.content.Context;
import android.os.Build;

import org.chromium.base.annotations.UsedByReflection;
import org.chromium.net.CronetEngine;
import org.chromium.net.HttpUrlRequestFactory;
import org.chromium.net.HttpUrlRequestListener;

import java.nio.channels.WritableByteChannel;
import java.util.Map;

/**
 * Network request factory using the native http stack implementation.
 * @deprecated Use {@link CronetEngine} instead.
 */
@UsedByReflection("HttpUrlRequestFactory.java")
@Deprecated
public class ChromiumUrlRequestFactory extends HttpUrlRequestFactory {
    private ChromiumUrlRequestContext mRequestContext;

    @UsedByReflection("HttpUrlRequestFactory.java")
    public ChromiumUrlRequestFactory(Context context, CronetEngine.Builder config) {
        if (isEnabled()) {
            String userAgent = config.getUserAgent();
            if (userAgent == null) {
                // Cannot use config.getDefaultUserAgent() as config.mContext may be null.
                userAgent = new CronetEngine.Builder(context).getDefaultUserAgent();
            }
            mRequestContext = new ChromiumUrlRequestContext(context, userAgent, config);
        }
    }

    @Override
    public boolean isEnabled() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH;
    }

    @Override
    public String getName() {
        return "Chromium/" + ChromiumUrlRequestContext.getVersion();
    }

    @Override
    public ChromiumUrlRequest createRequest(String url, int requestPriority,
            Map<String, String> headers, HttpUrlRequestListener listener) {
        return new ChromiumUrlRequest(mRequestContext, url, requestPriority, headers, listener);
    }

    @Override
    public ChromiumUrlRequest createRequest(String url, int requestPriority,
            Map<String, String> headers, WritableByteChannel channel,
            HttpUrlRequestListener listener) {
        return new ChromiumUrlRequest(
                mRequestContext, url, requestPriority, headers, channel, listener);
    }

    @Override
    public void startNetLogToFile(String fileName, boolean logAll) {
        mRequestContext.startNetLogToFile(fileName, logAll);
    }

    @Override
    public void stopNetLog() {
        mRequestContext.stopNetLog();
    }

    public ChromiumUrlRequestContext getRequestContext() {
        return mRequestContext;
    }
}
