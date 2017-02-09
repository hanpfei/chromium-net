// Copyright 2015 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.net.impl;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.chromium.base.ContextUtils;
import org.chromium.base.Log;
import org.chromium.base.VisibleForTesting;
import org.chromium.base.annotations.JNINamespace;
import org.chromium.net.CronetEngine;
import org.chromium.net.NetworkChangeNotifier;

/**
 * CronetLibraryLoader loads and initializes native library on main thread.
 */
@JNINamespace("cronet")
@VisibleForTesting
public class CronetLibraryLoader {
    // Synchronize initialization.
    private static final Object sLoadLock = new Object();
    private static final String TAG = "CronetLibraryLoader";
    // Has library loading commenced?  Setting guarded by sLoadLock.
    private static volatile boolean sInitStarted = false;
    // Has ensureMainThreadInitialized() completed?  Only accessed on main thread.
    private static boolean sMainThreadInitDone = false;

    /**
     * Ensure that native library is loaded and initialized. Can be called from
     * any thread, the load and initialization is performed on main thread.
     */
    public static void ensureInitialized(
            final Context context, final CronetEngine.Builder builder) {
        synchronized (sLoadLock) {
            if (sInitStarted) {
                return;
            }
            sInitStarted = true;
            ContextUtils.initApplicationContext(context.getApplicationContext());
            if (builder.libraryLoader() != null) {
                builder.libraryLoader().loadLibrary(builder.libraryName());
            } else {
                System.loadLibrary(builder.libraryName());
            }
            ContextUtils.initApplicationContextForNative();
            if (!ImplVersion.CRONET_VERSION.equals(nativeGetCronetVersion())) {
                throw new RuntimeException(String.format("Expected Cronet version number %s, "
                                + "actual version number %s.",
                        ImplVersion.CRONET_VERSION, nativeGetCronetVersion()));
            }
            Log.i(TAG, "Cronet version: %s, arch: %s", ImplVersion.CRONET_VERSION,
                    System.getProperty("os.arch"));
            // Init native Chromium CronetEngine on Main UI thread.
            Runnable task = new Runnable() {
                @Override
                public void run() {
                    ensureInitializedOnMainThread(context);
                }
            };
            // Run task immediately or post it to the UI thread.
            if (Looper.getMainLooper() == Looper.myLooper()) {
                task.run();
            } else {
                // The initOnMainThread will complete on the main thread prior
                // to other tasks posted to the main thread.
                new Handler(Looper.getMainLooper()).post(task);
            }
        }
    }

    /**
     * Ensure that the main thread initialization has completed. Can only be called from
     * the main thread. Ensures that the NetworkChangeNotifier is initialzied and the
     * main thread native MessageLoop is initialized.
     */
    static void ensureInitializedOnMainThread(Context context) {
        assert sInitStarted;
        assert Looper.getMainLooper() == Looper.myLooper();
        if (sMainThreadInitDone) {
            return;
        }
        NetworkChangeNotifier.init(context);
        // Registers to always receive network notifications. Note
        // that this call is fine for Cronet because Cronet
        // embedders do not have API access to create network change
        // observers. Existing observers in the net stack do not
        // perform expensive work.
        NetworkChangeNotifier.registerToReceiveNotificationsAlways();
        // registerToReceiveNotificationsAlways() is called before the native
        // NetworkChangeNotifierAndroid is created, so as to avoid receiving
        // the undesired initial network change observer notification, which
        // will cause active requests to fail with ERR_NETWORK_CHANGED.
        nativeCronetInitOnMainThread();
        sMainThreadInitDone = true;
    }

    // Native methods are implemented in cronet_library_loader.cc.
    private static native void nativeCronetInitOnMainThread();
    private static native String nativeGetCronetVersion();
}
