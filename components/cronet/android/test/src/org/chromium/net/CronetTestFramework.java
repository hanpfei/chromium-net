// Copyright 2014 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.net;

import android.content.Context;
import android.os.ConditionVariable;
import android.os.Environment;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertTrue;

import org.chromium.base.Log;
import org.chromium.base.PathUtils;
import org.chromium.base.annotations.SuppressFBWarnings;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.net.URLStreamHandlerFactory;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.util.HashMap;

/**
 * Framework for testing Cronet.
 */
@SuppressFBWarnings("URF_UNREAD_PUBLIC_OR_PROTECTED_FIELD")
public class CronetTestFramework {
    private static final String TAG = "CronetTestFramework";

    public static final String COMMAND_LINE_ARGS_KEY = "commandLineArgs";
    public static final String POST_DATA_KEY = "postData";
    public static final String CACHE_KEY = "cache";
    public static final String SDCH_KEY = "sdch";
    public static final String LIBRARY_INIT_KEY = "libraryInit";

    // Uses disk cache.
    public static final String CACHE_DISK = "disk";

    // Uses disk cache but does not store http data.
    public static final String CACHE_DISK_NO_HTTP = "diskNoHttp";

    // Uses in-memory cache.
    public static final String CACHE_IN_MEMORY = "memory";

    // Enables Sdch.
    public static final String SDCH_ENABLE = "enable";

    /**
     * Library init type strings to use along with {@link LIBRARY_INIT_KEY}.
     * If unspecified, {@link LibraryInitType.CRONET} will be used.
     */
    public static final class LibraryInitType {
        // Initializes Cronet Async API.
        public static final String CRONET = "cronet";
        // Initializes Cronet legacy API.
        public static final String LEGACY = "legacy";
        // Initializes Cronet HttpURLConnection API.
        public static final String HTTP_URL_CONNECTION = "http_url_connection";
        // Do not initialize.
        public static final String NONE = "none";

        private LibraryInitType() {}
    }

    public URLStreamHandlerFactory mStreamHandlerFactory;
    public CronetEngine mCronetEngine;
    @SuppressWarnings("deprecation")
    HttpUrlRequestFactory mRequestFactory;

    private final String[] mCommandLine;
    private final Context mContext;

    private String mUrl;
    private int mHttpStatusCode = 0;

    // CronetEngine.Builder used for this activity.
    private CronetEngine.Builder mCronetEngineBuilder;

    @SuppressWarnings("deprecation")
    private class TestHttpUrlRequestListener implements HttpUrlRequestListener {
        private final ConditionVariable mComplete = new ConditionVariable();

        public TestHttpUrlRequestListener() {}

        @Override
        public void onResponseStarted(HttpUrlRequest request) {
            mHttpStatusCode = request.getHttpStatusCode();
        }

        @Override
        public void onRequestComplete(HttpUrlRequest request) {
            mComplete.open();
        }

        public void blockForComplete() {
            mComplete.block();
        }
    }

    // TODO(crbug.com/547160): Fix this findbugs error and remove the suppression.
    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public CronetTestFramework(
            String appUrl, String[] commandLine, Context context, CronetEngine.Builder builder) {
        mCommandLine = commandLine;
        mContext = context;

        // Print out extra arguments passed in starting this activity.
        if (commandLine != null) {
            assertEquals(0, commandLine.length % 2);
            for (int i = 0; i < commandLine.length / 2; i++) {
                Log.i(TAG, "Cronet commandLine %s = %s", commandLine[i * 2],
                        commandLine[i * 2 + 1]);
            }
        }

        // Initializes CronetEngine.Builder from commandLine args.
        mCronetEngineBuilder = initializeCronetEngineBuilderWithPresuppliedBuilder(builder);

        String initString = getCommandLineArg(LIBRARY_INIT_KEY);

        if (initString == null) {
            initString = LibraryInitType.CRONET;
        }

        switch (initString) {
            case LibraryInitType.NONE:
                break;
            case LibraryInitType.LEGACY:
                mRequestFactory = initRequestFactory();
                if (appUrl != null) {
                    startWithURL(appUrl);
                }
                break;
            case LibraryInitType.HTTP_URL_CONNECTION:
                mCronetEngine = initCronetEngine();
                mStreamHandlerFactory = mCronetEngine.createURLStreamHandlerFactory();
                break;
            default:
                mCronetEngine = initCronetEngine();
                // Start collecting metrics.
                mCronetEngine.getGlobalMetricsDeltas();
                break;
        }
    }

    /**
     * Prepares the path for the test storage (http cache, QUIC server info).
     */
    public static void prepareTestStorage(Context context) {
        File storage = new File(getTestStorageDirectory(context));
        if (storage.exists()) {
            assertTrue(recursiveDelete(storage));
        }
        ensureTestStorageExists(context);
    }

    /**
     * Returns the path for the test storage (http cache, QUIC server info).
     * NOTE: Does not ensure it exists; tests should use {@link #getTestStorage}.
     */
    private static String getTestStorageDirectory(Context context) {
        return PathUtils.getDataDirectory(context) + "/test_storage";
    }

    /**
     * Ensures test storage directory exists, i.e. creates one if it does not exist.
     */
    private static void ensureTestStorageExists(Context context) {
        File storage = new File(getTestStorageDirectory(context));
        if (!storage.exists()) {
            assertTrue(storage.mkdir());
        }
    }

    /**
     * Returns the path for the test storage (http cache, QUIC server info).
     * Also ensures it exists.
     */
    static String getTestStorage(Context context) {
        ensureTestStorageExists(context);
        return getTestStorageDirectory(context);
    }

    @SuppressFBWarnings("NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE")
    private static boolean recursiveDelete(File path) {
        if (path.isDirectory()) {
            for (File c : path.listFiles()) {
                if (!recursiveDelete(c)) {
                    return false;
                }
            }
        }
        return path.delete();
    }

    CronetEngine.Builder getCronetEngineBuilder() {
        return mCronetEngineBuilder;
    }

    private CronetEngine.Builder initializeCronetEngineBuilderWithPresuppliedBuilder(
            CronetEngine.Builder builder) {
        return createCronetEngineBuilderWithPresuppliedBuilder(mContext, builder);
    }

    CronetEngine.Builder createCronetEngineBuilder(Context context) {
        return createCronetEngineBuilderWithPresuppliedBuilder(context, null);
    }

    private CronetEngine.Builder createCronetEngineBuilderWithPresuppliedBuilder(
            Context context, CronetEngine.Builder cronetEngineBuilder) {
        if (cronetEngineBuilder == null) {
            cronetEngineBuilder = new CronetEngine.Builder(context);
            cronetEngineBuilder.enableHttp2(true).enableQuic(true);
        }

        String cacheString = getCommandLineArg(CACHE_KEY);
        if (CACHE_DISK.equals(cacheString)) {
            cronetEngineBuilder.setStoragePath(getTestStorage(context));
            cronetEngineBuilder.enableHttpCache(CronetEngine.Builder.HTTP_CACHE_DISK, 1000 * 1024);
        } else if (CACHE_DISK_NO_HTTP.equals(cacheString)) {
            cronetEngineBuilder.setStoragePath(getTestStorage(context));
            cronetEngineBuilder.enableHttpCache(
                    CronetEngine.Builder.HTTP_CACHE_DISK_NO_HTTP, 1000 * 1024);
        } else if (CACHE_IN_MEMORY.equals(cacheString)) {
            cronetEngineBuilder.enableHttpCache(
                    CronetEngine.Builder.HTTP_CACHE_IN_MEMORY, 100 * 1024);
        }

        String sdchString = getCommandLineArg(SDCH_KEY);
        if (SDCH_ENABLE.equals(sdchString)) {
            cronetEngineBuilder.enableSDCH(true);
        }

        // Setting this here so it isn't overridden on the command line
        cronetEngineBuilder.setLibraryName("cronet_tests");
        return cronetEngineBuilder;
    }

    // Helper function to initialize Cronet engine. Also used in testing.
    public CronetEngine initCronetEngine() {
        return mCronetEngineBuilder.build();
    }

    // Helper function to initialize request factory. Also used in testing.
    @SuppressWarnings("deprecation")
    public HttpUrlRequestFactory initRequestFactory() {
        return HttpUrlRequestFactory.createFactory(mContext, mCronetEngineBuilder);
    }

    private String getCommandLineArg(String key) {
        if (mCommandLine != null) {
            for (int i = 0; i < mCommandLine.length; ++i) {
                if (mCommandLine[i].equals(key)) {
                    return mCommandLine[++i];
                }
            }
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    private void applyCommandLineToHttpUrlRequest(HttpUrlRequest request) {
        String postData = getCommandLineArg(POST_DATA_KEY);
        if (postData != null) {
            InputStream dataStream = new ByteArrayInputStream(postData.getBytes());
            ReadableByteChannel dataChannel = Channels.newChannel(dataStream);
            request.setUploadChannel("text/plain", dataChannel, postData.length());
            request.setHttpMethod("POST");
        }
    }

    @SuppressWarnings("deprecation")
    public void startWithURL(String url) {
        Log.i(TAG, "Cronet started: %s", url);
        mUrl = url;

        HashMap<String, String> headers = new HashMap<String, String>();
        TestHttpUrlRequestListener listener = new TestHttpUrlRequestListener();
        HttpUrlRequest request = mRequestFactory.createRequest(
                url, HttpUrlRequest.REQUEST_PRIORITY_MEDIUM, headers, listener);
        applyCommandLineToHttpUrlRequest(request);
        request.start();
        listener.blockForComplete();
    }

    public String getUrl() {
        return mUrl;
    }

    public int getHttpStatusCode() {
        return mHttpStatusCode;
    }

    public void startNetLog() {
        if (mRequestFactory != null) {
            mRequestFactory.startNetLogToFile(Environment.getExternalStorageDirectory().getPath()
                            + "/cronet_sample_netlog_old_api.json",
                    false);
        }
        if (mCronetEngine != null) {
            mCronetEngine.startNetLogToFile(Environment.getExternalStorageDirectory().getPath()
                            + "/cronet_sample_netlog_new_api.json",
                    false);
        }
    }

    public void stopNetLog() {
        if (mRequestFactory != null) {
            mRequestFactory.stopNetLog();
        }
        if (mCronetEngine != null) {
            mCronetEngine.stopNetLog();
        }
    }
}
