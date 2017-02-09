// Copyright 2014 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.net;

import android.content.Context;
import android.test.suitebuilder.annotation.SmallTest;

import org.chromium.base.PathUtils;
import org.chromium.base.test.util.Feature;
import org.chromium.net.test.EmbeddedTestServer;

import java.io.File;
import java.util.HashMap;

/**
 * Test for deprecated {@link HttpUrlRequest} API.
 */
@SuppressWarnings("deprecation")
public class CronetUrlTest extends CronetTestBase {
    private EmbeddedTestServer mTestServer;
    private String mUrl;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mTestServer = EmbeddedTestServer.createAndStartDefaultServer(getContext());
        mUrl = mTestServer.getURL("/echo?status=200");
    }

    @Override
    protected void tearDown() throws Exception {
        mTestServer.stopAndDestroyServer();
        super.tearDown();
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testLoadUrl() throws Exception {
        CronetTestFramework testFramework = startCronetTestFrameworkForLegacyApi(mUrl);

        // Make sure that the URL is set as expected.
        assertEquals(mUrl, testFramework.getUrl());
        assertEquals(200, testFramework.getHttpStatusCode());
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testInvalidUrl() throws Exception {
        CronetTestFramework testFramework = startCronetTestFrameworkForLegacyApi("127.0.0.1:8000");

        // The load should fail.
        assertEquals(0, testFramework.getHttpStatusCode());
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testPostData() throws Exception {
        String[] commandLineArgs = {CronetTestFramework.POST_DATA_KEY, "test",
                CronetTestFramework.LIBRARY_INIT_KEY, CronetTestFramework.LibraryInitType.LEGACY};
        CronetTestFramework testFramework =
                startCronetTestFrameworkWithUrlAndCommandLineArgs(mUrl, commandLineArgs);

        // Make sure that the URL is set as expected.
        assertEquals(mUrl, testFramework.getUrl());
        assertEquals(200, testFramework.getHttpStatusCode());
    }

    @SmallTest
    @Feature({"Cronet"})
    @OnlyRunNativeCronet // No NetLog from HttpURLConnection
    public void testNetLog() throws Exception {
        Context context = getContext();
        File directory = new File(PathUtils.getDataDirectory(context));
        File file = File.createTempFile("cronet", "json", directory);
        HttpUrlRequestFactory factory = HttpUrlRequestFactory.createFactory(
                context,
                new UrlRequestContextConfig().setLibraryName("cronet_tests"));
        // Start NetLog immediately after the request context is created to make
        // sure that the call won't crash the app even when the native request
        // context is not fully initialized. See crbug.com/470196.
        factory.startNetLogToFile(file.getPath(), false);
        // Starts a request.
        HashMap<String, String> headers = new HashMap<String, String>();
        TestHttpUrlRequestListener listener = new TestHttpUrlRequestListener();
        HttpUrlRequest request = factory.createRequest(
                mUrl, HttpUrlRequest.REQUEST_PRIORITY_MEDIUM, headers, listener);
        request.start();
        listener.blockForComplete();
        factory.stopNetLog();
        assertTrue(file.exists());
        assertTrue(file.length() != 0);
        assertTrue(file.delete());
        assertTrue(!file.exists());
    }

    static class BadHttpUrlRequestListener extends TestHttpUrlRequestListener {
        static final String THROW_TAG = "BadListener";

        public BadHttpUrlRequestListener() {
        }

        @Override
        public void onResponseStarted(HttpUrlRequest request) {
            throw new NullPointerException(THROW_TAG);
        }
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testCalledByNativeException() throws Exception {
        CronetTestFramework testFramework = startCronetTestFrameworkForLegacyApi(mUrl);

        HashMap<String, String> headers = new HashMap<String, String>();
        BadHttpUrlRequestListener listener = new BadHttpUrlRequestListener();

        // Create request with bad listener to trigger an exception.
        HttpUrlRequest request = testFramework.mRequestFactory.createRequest(
                mUrl, HttpUrlRequest.REQUEST_PRIORITY_MEDIUM, headers, listener);
        request.start();
        listener.blockForComplete();
        assertTrue(request.isCanceled());
        assertNotNull(request.getException());
        assertEquals(BadHttpUrlRequestListener.THROW_TAG,
                     request.getException().getCause().getMessage());
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testSetUploadDataWithNullContentType() throws Exception {
        CronetTestFramework testFramework = startCronetTestFrameworkForLegacyApi(mUrl);

        HashMap<String, String> headers = new HashMap<String, String>();
        BadHttpUrlRequestListener listener = new BadHttpUrlRequestListener();

        // Create request.
        HttpUrlRequest request = testFramework.mRequestFactory.createRequest(
                mUrl, HttpUrlRequest.REQUEST_PRIORITY_MEDIUM, headers, listener);
        byte[] uploadData = new byte[] {1, 2, 3};
        try {
            request.setUploadData(null, uploadData);
            fail("setUploadData should throw on null content type");
        } catch (NullPointerException e) {
            // Nothing to do here.
        }
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testLegacyLoadUrl() throws Exception {
        CronetEngine.Builder builder = new CronetEngine.Builder(getContext());
        builder.enableLegacyMode(true);

        CronetTestFramework testFramework = startCronetTestFrameworkForLegacyApi(mUrl);

        // Make sure that the URL is set as expected.
        assertEquals(mUrl, testFramework.getUrl());
        assertEquals(200, testFramework.getHttpStatusCode());
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testRequestHead() throws Exception {
        CronetTestFramework testFramework = startCronetTestFrameworkForLegacyApi(mUrl);

        HashMap<String, String> headers = new HashMap<String, String>();
        TestHttpUrlRequestListener listener = new TestHttpUrlRequestListener();

        // Create request.
        HttpUrlRequest request = testFramework.mRequestFactory.createRequest(
                mUrl, HttpUrlRequest.REQUEST_PRIORITY_MEDIUM, headers, listener);
        request.setHttpMethod("HEAD");
        request.start();
        listener.blockForComplete();
        assertEquals(200, listener.mHttpStatusCode);
        // HEAD requests do not get any response data and Content-Length must be
        // ignored.
        assertEquals(0, listener.mResponseAsBytes.length);
    }
}
