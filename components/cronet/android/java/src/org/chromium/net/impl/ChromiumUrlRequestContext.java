// Copyright 2014 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.net.impl;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;

import org.chromium.base.VisibleForTesting;
import org.chromium.base.annotations.CalledByNative;
import org.chromium.base.annotations.JNINamespace;
import org.chromium.net.CronetEngine;

/**
 * Provides context for the native HTTP operations.
 * @deprecated Use {@link CronetEngine} instead.
 */
@JNINamespace("cronet")
@Deprecated
public class ChromiumUrlRequestContext {
    private static final int LOG_NONE = 3; // LOG(FATAL), no VLOG.
    private static final int LOG_DEBUG = -1; // LOG(FATAL...INFO), VLOG(1)
    private static final int LOG_VERBOSE = -2; // LOG(FATAL...INFO), VLOG(2)
    static final String LOG_TAG = "ChromiumNetwork";

    /**
     * Native adapter object, owned by ChromiumUrlRequestContext.
     */
    private long mChromiumUrlRequestContextAdapter;

    /**
     * Constructor.
     */
    protected ChromiumUrlRequestContext(
            final Context context, String userAgent, CronetEngine.Builder config) {
        CronetLibraryLoader.ensureInitialized(context, config);
        mChromiumUrlRequestContextAdapter = nativeCreateRequestContextAdapter(userAgent,
                getLoggingLevel(),
                CronetUrlRequestContext.createNativeUrlRequestContextConfig(context, config));
        if (mChromiumUrlRequestContextAdapter == 0) {
            throw new NullPointerException("Context Adapter creation failed");
        }
        // Post a task to UI thread to init native Chromium URLRequestContext.
        // TODO(xunjieli): This constructor is not supposed to be invoked on
        // the main thread. Consider making the following code into a blocking
        // API to handle the case where we are already on main thread.
        Runnable task = new Runnable() {
            public void run() {
                nativeInitRequestContextOnMainThread(mChromiumUrlRequestContextAdapter);
            }
        };
        new Handler(Looper.getMainLooper()).post(task);
    }

    /**
     * Returns the version of this network stack formatted as N.N.N.N/X where
     * N.N.N.N is the version of Chromium and X is the revision number.
     */
    public static String getVersion() {
        return ImplVersion.getVersion();
    }

    /**
     * Initializes statistics recorder.
     */
    public void initializeStatistics() {
        nativeInitializeStatistics();
    }

    /**
     * Gets current statistics recorded since |initializeStatistics| with
     * |filter| as a substring as JSON text (an empty |filter| will include all
     * registered histograms).
     */
    public String getStatisticsJSON(String filter) {
        return nativeGetStatisticsJSON(filter);
    }

    /**
     * Starts NetLog logging to a file. The NetLog capture mode is either
     * NetLogCaptureMode::Default() or NetLogCaptureMode::IncludeSocketBytes().
     * The IncludeSocketBytes() mode includes basic events, user cookies,
     * credentials and all transferred bytes in the log.
     * @param fileName The complete file path. It must not be empty. If file
     *            exists, it is truncated before starting. If actively logging,
     *            this method is ignored.
     * @param logAll {@code true} to use the
     *            NetLogCaptureMode::IncludeSocketBytes() logging level. If
     *            false, NetLogCaptureMode::Default() is used instead.
     */
    public void startNetLogToFile(String fileName, boolean logAll) {
        nativeStartNetLogToFile(mChromiumUrlRequestContextAdapter, fileName, logAll);
    }

    /**
     * Stops NetLog logging and flushes file to disk. If a logging session is
     * not in progress, this call is ignored.
     */
    public void stopNetLog() {
        nativeStopNetLog(mChromiumUrlRequestContextAdapter);
    }

    @CalledByNative
    private void initNetworkThread() {
        Thread.currentThread().setName("ChromiumNet");
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
    }

    @Override
    protected void finalize() throws Throwable {
        if (mChromiumUrlRequestContextAdapter != 0) {
            nativeReleaseRequestContextAdapter(mChromiumUrlRequestContextAdapter);
        }
        super.finalize();
    }

    @VisibleForTesting
    public long getUrlRequestContextAdapter() {
        return mChromiumUrlRequestContextAdapter;
    }

    /**
     * @return loggingLevel see {@link #LOG_NONE}, {@link #LOG_DEBUG} and
     *         {@link #LOG_VERBOSE}.
     */
    private int getLoggingLevel() {
        int loggingLevel;
        if (Log.isLoggable(LOG_TAG, Log.VERBOSE)) {
            loggingLevel = LOG_VERBOSE;
        } else if (Log.isLoggable(LOG_TAG, Log.DEBUG)) {
            loggingLevel = LOG_DEBUG;
        } else {
            loggingLevel = LOG_NONE;
        }
        return loggingLevel;
    }

    // Returns an instance ChromiumUrlRequestContextAdapter to be stored in
    // mChromiumUrlRequestContextAdapter.
    private native long nativeCreateRequestContextAdapter(
            String userAgent, int loggingLevel, long config);

    private native void nativeReleaseRequestContextAdapter(long chromiumUrlRequestContextAdapter);

    private native void nativeInitializeStatistics();

    private native String nativeGetStatisticsJSON(String filter);

    private native void nativeStartNetLogToFile(
            long chromiumUrlRequestContextAdapter, String fileName, boolean logAll);

    private native void nativeStopNetLog(long chromiumUrlRequestContextAdapter);

    private native void nativeInitRequestContextOnMainThread(long chromiumUrlRequestContextAdapter);
}
