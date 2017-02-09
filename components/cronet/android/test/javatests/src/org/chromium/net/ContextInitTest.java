// Copyright 2014 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.net;

import android.content.ContextWrapper;
import android.test.suitebuilder.annotation.SmallTest;

import org.chromium.base.test.util.Feature;
import org.chromium.net.test.EmbeddedTestServer;

import java.util.HashMap;

/**
 * Tests that make sure ChromiumUrlRequestContext initialization will not
 * affect embedders' ability to make requests.
 */
@SuppressWarnings("deprecation")
public class ContextInitTest extends CronetTestBase {
    private EmbeddedTestServer mTestServer;
    private String mUrl;
    private String mUrl404;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mTestServer = EmbeddedTestServer.createAndStartDefaultServer(getContext());
        mUrl = mTestServer.getURL("/echo?status=200");
        mUrl404 = mTestServer.getURL("/echo?status=404");
    }

    @Override
    protected void tearDown() throws Exception {
        mTestServer.stopAndDestroyServer();
        super.tearDown();
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testInitFactoryAndStartRequest() {
        CronetTestFramework testFramework = startCronetTestFrameworkAndSkipLibraryInit();

        // Immediately make a request after initializing the factory.
        HttpUrlRequestFactory factory = testFramework.initRequestFactory();
        TestHttpUrlRequestListener listener = makeRequest(factory, mUrl);
        listener.blockForComplete();
        assertEquals(200, listener.mHttpStatusCode);
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testInitFactoryStartRequestAndCancel() {
        CronetTestFramework testFramework = startCronetTestFrameworkAndSkipLibraryInit();

        // Make a request and cancel it after initializing the factory.
        HttpUrlRequestFactory factory = testFramework.initRequestFactory();
        HashMap<String, String> headers = new HashMap<String, String>();
        TestHttpUrlRequestListener listener = new TestHttpUrlRequestListener();
        HttpUrlRequest request = factory.createRequest(
                mUrl, HttpUrlRequest.REQUEST_PRIORITY_MEDIUM, headers, listener);
        request.start();
        request.cancel();
        listener.blockForComplete();
        assertEquals(0, listener.mHttpStatusCode);
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testInitFactoryStartTwoRequests() throws Exception {
        CronetTestFramework testFramework = startCronetTestFrameworkAndSkipLibraryInit();

        // Make two request right after initializing the factory.
        int[] statusCodes = {0, 0};
        String[] urls = {mUrl, mUrl404};
        HttpUrlRequestFactory factory = testFramework.initRequestFactory();
        for (int i = 0; i < 2; i++) {
            TestHttpUrlRequestListener listener = makeRequest(factory, urls[i]);
            listener.blockForComplete();
            statusCodes[i] = listener.mHttpStatusCode;
        }
        assertEquals(200, statusCodes[0]);
        assertEquals(404, statusCodes[1]);
    }

    class RequestThread extends Thread {
        public TestHttpUrlRequestListener mCallback;

        final CronetTestFramework mTestFramework;
        final String mUrl;

        public RequestThread(CronetTestFramework testFramework, String url) {
            mTestFramework = testFramework;
            mUrl = url;
        }

        @Override
        public void run() {
            HttpUrlRequestFactory factory = mTestFramework.initRequestFactory();
            mCallback = makeRequest(factory, mUrl);
            mCallback.blockForComplete();
        }
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testInitTwoFactoriesSimultaneously() throws Exception {
        final CronetTestFramework testFramework = startCronetTestFrameworkAndSkipLibraryInit();

        RequestThread thread1 = new RequestThread(testFramework, mUrl);
        RequestThread thread2 = new RequestThread(testFramework, mUrl404);

        thread1.start();
        thread2.start();
        thread1.join();
        thread2.join();
        assertEquals(200, thread1.mCallback.mHttpStatusCode);
        assertEquals(404, thread2.mCallback.mHttpStatusCode);
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testInitTwoFactoriesInSequence() throws Exception {
        final CronetTestFramework testFramework = startCronetTestFrameworkAndSkipLibraryInit();

        RequestThread thread1 = new RequestThread(testFramework, mUrl);
        RequestThread thread2 = new RequestThread(testFramework, mUrl404);

        thread1.start();
        thread1.join();
        thread2.start();
        thread2.join();
        assertEquals(200, thread1.mCallback.mHttpStatusCode);
        assertEquals(404, thread2.mCallback.mHttpStatusCode);
    }

    // Helper function to make a request.
    private TestHttpUrlRequestListener makeRequest(
            HttpUrlRequestFactory factory, String url) {
        HashMap<String, String> headers = new HashMap<String, String>();
        TestHttpUrlRequestListener listener = new TestHttpUrlRequestListener();
        HttpUrlRequest request = factory.createRequest(
                url, HttpUrlRequest.REQUEST_PRIORITY_MEDIUM, headers, listener);
        request.start();
        return listener;
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testInitDifferentContexts() throws Exception {
        // Test that concurrently instantiating ChromiumUrlRequestContext's upon
        // various different versions of the same Android Context does not cause
        // crashes like crbug.com/453845
        final CronetTestFramework testFramework = startCronetTestFramework();
        HttpUrlRequestFactory firstFactory = HttpUrlRequestFactory.createFactory(
                getContext(), testFramework.getCronetEngineBuilder());
        HttpUrlRequestFactory secondFactory = HttpUrlRequestFactory.createFactory(
                getContext().getApplicationContext(), testFramework.getCronetEngineBuilder());
        HttpUrlRequestFactory thirdFactory = HttpUrlRequestFactory.createFactory(
                new ContextWrapper(getContext()), testFramework.getCronetEngineBuilder());
        // Meager attempt to extend lifetimes to ensure they're concurrently
        // alive.
        firstFactory.getName();
        secondFactory.getName();
        thirdFactory.getName();
    }
}
