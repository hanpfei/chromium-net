// Copyright 2014 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.net;

import android.content.Context;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.nio.channels.WritableByteChannel;
import java.util.Map;

/**
 * A factory for {@link HttpUrlRequest}'s, which uses the best HTTP stack
 * available on the current platform.
 * @deprecated Use {@link CronetEngine} instead.
 */
@Deprecated
public abstract class HttpUrlRequestFactory {
    private static final String TAG = "HttpUrlRequestFactory";

    private static final String CHROMIUM_URL_REQUEST_FACTORY =
            "org.chromium.net.impl.ChromiumUrlRequestFactory";

    public static HttpUrlRequestFactory createFactory(
            Context context, CronetEngine.Builder config) {
        HttpUrlRequestFactory factory = null;
        if (!config.legacyMode()) {
            factory = createChromiumFactory(context, config);
        }
        if (factory == null) {
            // Default to HttpUrlConnection-based networking.
            factory = new HttpUrlConnectionUrlRequestFactory(context, config);
        }
        Log.i(TAG, "Using network stack: " + factory.getName());
        return factory;
    }

    /**
     * Returns true if the factory is enabled.
     */
    public abstract boolean isEnabled();

    /**
     * Returns a human-readable name of the factory.
     */
    public abstract String getName();

    /**
     * Creates a new request intended for full-response buffering.
     */
    public abstract HttpUrlRequest createRequest(String url,
            int requestPriority, Map<String, String> headers,
            HttpUrlRequestListener listener);

    /**
     * Creates a new request intended for streaming.
     */
    public abstract HttpUrlRequest createRequest(String url,
            int requestPriority, Map<String, String> headers,
            WritableByteChannel channel, HttpUrlRequestListener listener);

    /**
     * Starts NetLog logging to a file named |fileName| in the
     * application temporary directory. |fileName| must not be empty. Log may
     * contain user's personal information (PII). If the file exists it is
     * truncated before starting. If actively logging the call is ignored.
     * @param fileName The complete file path. It must not be empty. If file
     *            exists, it is truncated before starting.
     * @param logAll {@code true} to also include all transferred bytes in the
     *            log.
     */
    public abstract void startNetLogToFile(String fileName, boolean logAll);

    /**
     * Stops NetLog logging and flushes file to disk. If a logging session is
     * not in progress this call is ignored.
     */
    public abstract void stopNetLog();

    private static HttpUrlRequestFactory createChromiumFactory(
            Context context, CronetEngine.Builder config) {
        HttpUrlRequestFactory factory = null;
        try {
            Class<? extends HttpUrlRequestFactory> factoryClass =
                    HttpUrlRequestFactory.class.getClassLoader()
                            .loadClass(CHROMIUM_URL_REQUEST_FACTORY)
                            .asSubclass(HttpUrlRequestFactory.class);
            Constructor<? extends HttpUrlRequestFactory> constructor =
                    factoryClass.getConstructor(Context.class, CronetEngine.Builder.class);
            HttpUrlRequestFactory chromiumFactory =
                    constructor.newInstance(context, config);
            if (chromiumFactory.isEnabled()) {
                factory = chromiumFactory;
            }
        } catch (ClassNotFoundException e) {
            // Leave as null
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Cannot instantiate: " + CHROMIUM_URL_REQUEST_FACTORY,
                    e);
        }
        return factory;
    }
}
