// Copyright 2014 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.net;

import android.test.AndroidTestCase;

import org.chromium.base.PathUtils;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.AnnotatedElement;
import java.net.URL;

/**
 * Base test class for all CronetTest based tests.
 */
public class CronetTestBase extends AndroidTestCase {
    private static final String PRIVATE_DATA_DIRECTORY_SUFFIX = "cronet_test";
    private static final String LOOPBACK_ADDRESS = "127.0.0.1";

    private CronetTestFramework mCronetTestFramework;
    // {@code true} when test is being run against system HttpURLConnection implementation.
    private boolean mTestingSystemHttpURLConnection;
    private boolean mTestingJavaImpl = false;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        PathUtils.setPrivateDataDirectorySuffix(PRIVATE_DATA_DIRECTORY_SUFFIX, getContext());
        CronetTestFramework.prepareTestStorage(getContext());
    }

    /**
     * Starts the CronetTest framework.
     */
    protected CronetTestFramework startCronetTestFramework() {
        return startCronetTestFrameworkWithUrlAndCronetEngineBuilder(null, null);
    }

    /**
     * Starts the CronetTest framework and loads the given URL. The URL can be
     * null.
     */
    protected CronetTestFramework startCronetTestFrameworkWithUrl(String url) {
        return startCronetTestFrameworkWithUrlAndCronetEngineBuilder(url, null);
    }

    /**
     * Starts the CronetTest framework using the provided CronetEngine.Builder
     * and loads the given URL. The URL can be null.
     */
    protected CronetTestFramework startCronetTestFrameworkWithUrlAndCronetEngineBuilder(
            String url, CronetEngine.Builder builder) {
        mCronetTestFramework = new CronetTestFramework(url, null, getContext(), builder);
        return mCronetTestFramework;
    }

    /**
     * Starts the CronetTest framework appending the provided command line
     * arguments and loads the given URL. The URL can be null.
     */
    protected CronetTestFramework startCronetTestFrameworkWithUrlAndCommandLineArgs(
            String url, String[] commandLineArgs) {
        mCronetTestFramework = new CronetTestFramework(url, commandLineArgs, getContext(), null);
        return mCronetTestFramework;
    }

    // Helper method to tell the framework to skip library init during construction.
    protected CronetTestFramework startCronetTestFrameworkAndSkipLibraryInit() {
        String[] commandLineArgs = {
                CronetTestFramework.LIBRARY_INIT_KEY, CronetTestFramework.LibraryInitType.NONE};
        mCronetTestFramework =
                startCronetTestFrameworkWithUrlAndCommandLineArgs(null, commandLineArgs);
        return mCronetTestFramework;
    }

    /**
     * Starts the CronetTest framework for the legacy API.
     * @param url if non-null, a request will be made with that url.
     */
    protected CronetTestFramework startCronetTestFrameworkForLegacyApi(String url) {
        String[] commandLineArgs = {
                CronetTestFramework.LIBRARY_INIT_KEY, CronetTestFramework.LibraryInitType.LEGACY};
        mCronetTestFramework =
                startCronetTestFrameworkWithUrlAndCommandLineArgs(url, commandLineArgs);
        return mCronetTestFramework;
    }

    /**
     * Returns {@code true} when test is being run against system HttpURLConnection implementation.
     */
    protected boolean testingSystemHttpURLConnection() {
        return mTestingSystemHttpURLConnection;
    }

    /**
     * Returns {@code true} when test is being run against the java implementation of CronetEngine.
     */
    protected boolean testingJavaImpl() {
        return mTestingJavaImpl;
    }

    @Override
    protected void runTest() throws Throwable {
        mTestingSystemHttpURLConnection = false;
        mTestingJavaImpl = false;
        String packageName = getClass().getPackage().getName();
        if (packageName.equals("org.chromium.net.urlconnection")) {
            try {
                AnnotatedElement method = getClass().getMethod(getName(), (Class[]) null);
                if (method.isAnnotationPresent(CompareDefaultWithCronet.class)) {
                    // Run with the default HttpURLConnection implementation first.
                    mTestingSystemHttpURLConnection = true;
                    super.runTest();
                    // Use Cronet's implementation, and run the same test.
                    mTestingSystemHttpURLConnection = false;
                    URL.setURLStreamHandlerFactory(mCronetTestFramework.mStreamHandlerFactory);
                    super.runTest();
                } else if (method.isAnnotationPresent(OnlyRunCronetHttpURLConnection.class)) {
                    // Run only with Cronet's implementation.
                    URL.setURLStreamHandlerFactory(mCronetTestFramework.mStreamHandlerFactory);
                    super.runTest();
                } else {
                    // For all other tests.
                    super.runTest();
                }
            } catch (Throwable e) {
                throw new Throwable("CronetTestBase#runTest failed.", e);
            }
        } else if (packageName.equals("org.chromium.net")) {
            try {
                AnnotatedElement method = getClass().getMethod(getName(), (Class[]) null);
                super.runTest();
                if (!method.isAnnotationPresent(OnlyRunNativeCronet.class)) {
                    if (mCronetTestFramework != null) {
                        mCronetTestFramework.mCronetEngine =
                                new JavaCronetEngine(UserAgent.from(getContext()));
                    }
                    mTestingJavaImpl = true;
                    super.runTest();
                }
            } catch (Throwable e) {
                throw new Throwable("CronetTestBase#runTest failed.", e);
            }
        } else {
            super.runTest();
        }
    }

    /**
     * Registers test host resolver for testing with the new API.
     */
    protected void registerHostResolver(CronetTestFramework framework) {
        registerHostResolver(framework, false);
    }

    /**
     * Registers test host resolver.
     *
     * @param isLegacyAPI true if the test should use the legacy API.
     */
    protected void registerHostResolver(CronetTestFramework framework, boolean isLegacyAPI) {
        if (isLegacyAPI) {
            CronetTestUtil.registerHostResolverProc(framework.mRequestFactory, LOOPBACK_ADDRESS);
        } else {
            CronetTestUtil.registerHostResolverProc(framework.mCronetEngine, LOOPBACK_ADDRESS);
        }
    }

    void assertResponseEquals(UrlResponseInfo expected, UrlResponseInfo actual) {
        assertEquals(expected.getAllHeaders(), actual.getAllHeaders());
        assertEquals(expected.getAllHeadersAsList(), actual.getAllHeadersAsList());
        assertEquals(expected.getHttpStatusCode(), actual.getHttpStatusCode());
        assertEquals(expected.getHttpStatusText(), actual.getHttpStatusText());
        assertEquals(expected.getUrlChain(), actual.getUrlChain());
        assertEquals(expected.getUrl(), actual.getUrl());
        // Transferred bytes and proxy server are not supported in pure java
        if (!(mCronetTestFramework.mCronetEngine instanceof JavaCronetEngine)) {
            assertEquals(expected.getReceivedBytesCount(), actual.getReceivedBytesCount());
            assertEquals(expected.getProxyServer(), actual.getProxyServer());
            // This is a place where behavior intentionally differs between native and java
            assertEquals(expected.getNegotiatedProtocol(), actual.getNegotiatedProtocol());
        }
    }

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    public @interface CompareDefaultWithCronet {
    }

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    public @interface OnlyRunCronetHttpURLConnection {
    }

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    public @interface OnlyRunNativeCronet {}
}
