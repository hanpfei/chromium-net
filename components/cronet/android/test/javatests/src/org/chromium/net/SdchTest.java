// Copyright 2015 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.net;

import android.os.ConditionVariable;
import android.test.suitebuilder.annotation.SmallTest;

import org.chromium.base.test.util.Feature;
import org.chromium.net.CronetTestBase.OnlyRunNativeCronet;
import org.chromium.net.impl.ChromiumUrlRequestFactory;
import org.chromium.net.impl.CronetUrlRequestContext;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tests Sdch support.
 */
public class SdchTest extends CronetTestBase {
    private CronetTestFramework mTestFramework;

    private enum Sdch {
        ENABLED,
        DISABLED,
    }

    private enum Api {
        LEGACY,
        ASYNC,
    }

    @SuppressWarnings("deprecation")
    private void setUp(Sdch setting, Api api) {
        List<String> commandLineArgs = new ArrayList<String>();
        commandLineArgs.add(CronetTestFramework.CACHE_KEY);
        commandLineArgs.add(CronetTestFramework.CACHE_DISK);
        if (setting == Sdch.ENABLED) {
            commandLineArgs.add(CronetTestFramework.SDCH_KEY);
            commandLineArgs.add(CronetTestFramework.SDCH_ENABLE);
        }

        if (api == Api.LEGACY) {
            commandLineArgs.add(CronetTestFramework.LIBRARY_INIT_KEY);
            commandLineArgs.add(CronetTestFramework.LibraryInitType.LEGACY);
        } else {
            commandLineArgs.add(CronetTestFramework.LIBRARY_INIT_KEY);
            commandLineArgs.add(CronetTestFramework.LibraryInitType.CRONET);
        }

        String[] args = new String[commandLineArgs.size()];
        mTestFramework = startCronetTestFrameworkWithUrlAndCommandLineArgs(
                null, commandLineArgs.toArray(args));
        registerHostResolver(mTestFramework, api == Api.LEGACY);
        // Start NativeTestServer.
        assertTrue(NativeTestServer.startNativeTestServer(getContext()));
    }

    @Override
    protected void tearDown() throws Exception {
        NativeTestServer.shutdownNativeTestServer();
        super.tearDown();
    }

    @SmallTest
    @Feature({"Cronet"})
    @SuppressWarnings("deprecation")
    @OnlyRunNativeCronet
    public void testSdchEnabled_LegacyApi() throws Exception {
        setUp(Sdch.ENABLED, Api.LEGACY);
        String targetUrl = NativeTestServer.getSdchURL() + "/sdch/test";
        long contextAdapter =
                getContextAdapter((ChromiumUrlRequestFactory) mTestFramework.mRequestFactory);
        DictionaryAddedObserver observer =
                new DictionaryAddedObserver(targetUrl, contextAdapter, true /** Legacy Api */);

        // Make a request to /sdch/index which advertises the dictionary.
        TestHttpUrlRequestListener listener1 =
                startAndWaitForComplete_LegacyApi(mTestFramework.mRequestFactory,
                        NativeTestServer.getSdchURL() + "/sdch/index?q=LeQxM80O");
        assertEquals(200, listener1.mHttpStatusCode);
        assertEquals("This is an index page.\n", listener1.mResponseAsString);
        assertEquals(Arrays.asList("/sdch/dict/LeQxM80O"),
                listener1.mResponseHeaders.get("Get-Dictionary"));

        observer.waitForDictionaryAdded();

        // Make a request to fetch encoded response at /sdch/test.
        TestHttpUrlRequestListener listener2 =
                startAndWaitForComplete_LegacyApi(mTestFramework.mRequestFactory, targetUrl);
        assertEquals(200, listener2.mHttpStatusCode);
        assertEquals("The quick brown fox jumps over the lazy dog.\n", listener2.mResponseAsString);
    }

    @SmallTest
    @Feature({"Cronet"})
    @SuppressWarnings("deprecation")
    @OnlyRunNativeCronet
    public void testSdchDisabled_LegacyApi() throws Exception {
        setUp(Sdch.DISABLED, Api.LEGACY);
        // Make a request to /sdch/index.
        // Since Sdch is not enabled, no dictionary should be advertised.
        TestHttpUrlRequestListener listener =
                startAndWaitForComplete_LegacyApi(mTestFramework.mRequestFactory,
                        NativeTestServer.getSdchURL() + "/sdch/index?q=LeQxM80O");
        assertEquals(200, listener.mHttpStatusCode);
        assertEquals("This is an index page.\n", listener.mResponseAsString);
        assertEquals(null, listener.mResponseHeaders.get("Get-Dictionary"));
    }

    @SmallTest
    @Feature({"Cronet"})
    @SuppressWarnings("deprecation")
    @OnlyRunNativeCronet
    public void testDictionaryNotFound_LegacyApi() throws Exception {
        setUp(Sdch.ENABLED, Api.LEGACY);
        // Make a request to /sdch/index which advertises a bad dictionary that
        // does not exist.
        TestHttpUrlRequestListener listener1 =
                startAndWaitForComplete_LegacyApi(mTestFramework.mRequestFactory,
                        NativeTestServer.getSdchURL() + "/sdch/index?q=NotFound");
        assertEquals(200, listener1.mHttpStatusCode);
        assertEquals("This is an index page.\n", listener1.mResponseAsString);
        assertEquals(Arrays.asList("/sdch/dict/NotFound"),
                listener1.mResponseHeaders.get("Get-Dictionary"));

        // Make a request to fetch /sdch/test, and make sure request succeeds.
        TestHttpUrlRequestListener listener2 = startAndWaitForComplete_LegacyApi(
                mTestFramework.mRequestFactory, NativeTestServer.getSdchURL() + "/sdch/test");
        assertEquals(200, listener2.mHttpStatusCode);
        assertEquals("Sdch is not used.\n", listener2.mResponseAsString);
    }

    @SmallTest
    @Feature({"Cronet"})
    @OnlyRunNativeCronet
    public void testSdchEnabled() throws Exception {
        setUp(Sdch.ENABLED, Api.ASYNC);
        String targetUrl = NativeTestServer.getSdchURL() + "/sdch/test";
        long contextAdapter =
                getContextAdapter((CronetUrlRequestContext) mTestFramework.mCronetEngine);
        DictionaryAddedObserver observer =
                new DictionaryAddedObserver(targetUrl, contextAdapter, false /** Legacy Api */);

        // Make a request to /sdch which advertises the dictionary.
        TestUrlRequestCallback callback1 = startAndWaitForComplete(mTestFramework.mCronetEngine,
                NativeTestServer.getSdchURL() + "/sdch/index?q=LeQxM80O");
        assertEquals(200, callback1.mResponseInfo.getHttpStatusCode());
        assertEquals("This is an index page.\n", callback1.mResponseAsString);
        assertEquals(Arrays.asList("/sdch/dict/LeQxM80O"),
                callback1.mResponseInfo.getAllHeaders().get("Get-Dictionary"));

        observer.waitForDictionaryAdded();

        // Make a request to fetch encoded response at /sdch/test.
        TestUrlRequestCallback callback2 =
                startAndWaitForComplete(mTestFramework.mCronetEngine, targetUrl);
        assertEquals(200, callback2.mResponseInfo.getHttpStatusCode());
        assertEquals("The quick brown fox jumps over the lazy dog.\n", callback2.mResponseAsString);

        // Wait for a bit until SimpleCache finished closing entries before
        // calling shutdown on the CronetEngine.
        // TODO(xunjieli): Remove once crbug.com/486120 is fixed.
        Thread.sleep(5000);
        mTestFramework.mCronetEngine.shutdown();

        // Shutting down the context will make JsonPrefStore to flush pending
        // writes to disk.
        String dictUrl = NativeTestServer.getSdchURL() + "/sdch/dict/LeQxM80O";
        assertTrue(fileContainsString("local_prefs.json", dictUrl));

        // Test persistence.
        mTestFramework = startCronetTestFrameworkWithUrlAndCronetEngineBuilder(
                null, mTestFramework.getCronetEngineBuilder());
        CronetUrlRequestContext newContext = (CronetUrlRequestContext) mTestFramework.mCronetEngine;
        long newContextAdapter = getContextAdapter(newContext);
        registerHostResolver(mTestFramework);
        DictionaryAddedObserver newObserver =
                new DictionaryAddedObserver(targetUrl, newContextAdapter, false /** Legacy Api */);
        newObserver.waitForDictionaryAdded();

        // Make a request to fetch encoded response at /sdch/test.
        TestUrlRequestCallback callback3 = startAndWaitForComplete(newContext, targetUrl);
        assertEquals(200, callback3.mResponseInfo.getHttpStatusCode());
        assertEquals("The quick brown fox jumps over the lazy dog.\n", callback3.mResponseAsString);
    }

    @SmallTest
    @Feature({"Cronet"})
    @OnlyRunNativeCronet
    public void testSdchDisabled() throws Exception {
        setUp(Sdch.DISABLED, Api.ASYNC);
        // Make a request to /sdch.
        // Since Sdch is not enabled, no dictionary should be advertised.
        TestUrlRequestCallback callback = startAndWaitForComplete(mTestFramework.mCronetEngine,
                NativeTestServer.getSdchURL() + "/sdch/index?q=LeQxM80O");
        assertEquals(200, callback.mResponseInfo.getHttpStatusCode());
        assertEquals("This is an index page.\n", callback.mResponseAsString);
        assertEquals(null, callback.mResponseInfo.getAllHeaders().get("Get-Dictionary"));
    }

    @SmallTest
    @Feature({"Cronet"})
    @OnlyRunNativeCronet
    public void testDictionaryNotFound() throws Exception {
        setUp(Sdch.ENABLED, Api.ASYNC);
        // Make a request to /sdch/index which advertises a bad dictionary that
        // does not exist.
        TestUrlRequestCallback callback1 = startAndWaitForComplete(mTestFramework.mCronetEngine,
                NativeTestServer.getSdchURL() + "/sdch/index?q=NotFound");
        assertEquals(200, callback1.mResponseInfo.getHttpStatusCode());
        assertEquals("This is an index page.\n", callback1.mResponseAsString);
        assertEquals(Arrays.asList("/sdch/dict/NotFound"),
                callback1.mResponseInfo.getAllHeaders().get("Get-Dictionary"));

        // Make a request to fetch /sdch/test, and make sure Sdch encoding is not used.
        TestUrlRequestCallback callback2 = startAndWaitForComplete(
                mTestFramework.mCronetEngine, NativeTestServer.getSdchURL() + "/sdch/test");
        assertEquals(200, callback2.mResponseInfo.getHttpStatusCode());
        assertEquals("Sdch is not used.\n", callback2.mResponseAsString);
    }

    private static class DictionaryAddedObserver extends SdchObserver {
        ConditionVariable mBlock = new ConditionVariable();

        public DictionaryAddedObserver(String targetUrl, long contextAdapter, boolean isLegacyAPI) {
            super(targetUrl, contextAdapter, isLegacyAPI);
        }

        @Override
        public void onDictionaryAdded() {
            mBlock.open();
        }

        public void waitForDictionaryAdded() {
            if (!mDictionaryAlreadyPresent) {
                mBlock.block();
                mBlock.close();
            }
        }
    }

    @SuppressWarnings("deprecation")
    private long getContextAdapter(ChromiumUrlRequestFactory factory) {
        return factory.getRequestContext().getUrlRequestContextAdapter();
    }

    private long getContextAdapter(CronetUrlRequestContext requestContext) {
        return requestContext.getUrlRequestContextAdapter();
    }

    @SuppressWarnings("deprecation")
    private TestHttpUrlRequestListener startAndWaitForComplete_LegacyApi(
            HttpUrlRequestFactory factory, String url) throws Exception {
        Map<String, String> headers = new HashMap<String, String>();
        TestHttpUrlRequestListener listener = new TestHttpUrlRequestListener();
        HttpUrlRequest request = factory.createRequest(
                url, HttpUrlRequest.REQUEST_PRIORITY_MEDIUM, headers, listener);
        request.start();
        listener.blockForComplete();
        return listener;
    }

    private TestUrlRequestCallback startAndWaitForComplete(CronetEngine cronetEngine, String url)
            throws Exception {
        TestUrlRequestCallback callback = new TestUrlRequestCallback();
        UrlRequest.Builder builder =
                new UrlRequest.Builder(url, callback, callback.getExecutor(), cronetEngine);
        builder.build().start();
        callback.blockForDone();
        return callback;
    }

    // Returns whether a file contains a particular string.
    private boolean fileContainsString(String filename, String content) throws IOException {
        BufferedReader reader = new BufferedReader(new FileReader(
                CronetTestFramework.getTestStorage(getContext()) + "/prefs/" + filename));
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.contains(content)) {
                reader.close();
                return true;
            }
        }
        reader.close();
        return false;
    }
}
