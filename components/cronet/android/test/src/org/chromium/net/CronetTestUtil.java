// Copyright 2014 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.net;

import android.os.ConditionVariable;

import org.chromium.base.annotations.CalledByNative;
import org.chromium.base.annotations.JNINamespace;
import org.chromium.net.impl.ChromiumUrlRequestFactory;
import org.chromium.net.impl.CronetUrlRequest;
import org.chromium.net.impl.CronetUrlRequestContext;

/**
 * Utilities for Cronet testing
 */
@JNINamespace("cronet")
public class CronetTestUtil {
    private static final ConditionVariable sHostResolverBlock = new ConditionVariable();

    /**
     * Registers customized DNS mapping for testing host names used by test servers, namely:
     * <ul>
     * <li>{@link QuicTestServer#getServerHost}</li>
     * <li>{@link NativeTestServer#getSdchURL}</li>'s host
     * </ul>
     * @param cronetEngine {@link CronetEngine} that this mapping should apply to.
     * @param destination host to map to (e.g. 127.0.0.1)
     */
    public static void registerHostResolverProc(CronetEngine cronetEngine, String destination) {
        long contextAdapter =
                ((CronetUrlRequestContext) cronetEngine).getUrlRequestContextAdapter();
        nativeRegisterHostResolverProc(contextAdapter, false, destination);
        sHostResolverBlock.block();
        sHostResolverBlock.close();
    }

    /**
     * Registers customized DNS mapping for testing host names used by test servers.
     * @param requestFactory {@link HttpUrlRequestFactory} that this mapping should apply to.
     * @param destination host to map to (e.g. 127.0.0.1)
     */
    public static void registerHostResolverProc(
            HttpUrlRequestFactory requestFactory, String destination) {
        long contextAdapter = ((ChromiumUrlRequestFactory) requestFactory)
                                      .getRequestContext()
                                      .getUrlRequestContextAdapter();
        nativeRegisterHostResolverProc(contextAdapter, true, destination);
        sHostResolverBlock.block();
        sHostResolverBlock.close();
    }

    /**
     * Returns the value of load flags in |urlRequest|.
     * @param urlRequest is the UrlRequest object of interest.
     */
    public static int getLoadFlags(UrlRequest urlRequest) {
        return nativeGetLoadFlags(((CronetUrlRequest) urlRequest).getUrlRequestAdapterForTesting());
    }

    @CalledByNative
    private static void onHostResolverProcRegistered() {
        sHostResolverBlock.open();
    }

    private static native void nativeRegisterHostResolverProc(
            long contextAdapter, boolean isLegacyAPI, String destination);

    private static native int nativeGetLoadFlags(long urlRequest);
}
