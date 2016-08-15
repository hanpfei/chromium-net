// Copyright 2014 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.net;

import android.os.ConditionVariable;
import android.test.MoreAsserts;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.Log;

import org.chromium.base.test.util.Feature;
import org.chromium.base.test.util.FlakyTest;
import org.chromium.net.TestUrlRequestCallback.FailureType;
import org.chromium.net.TestUrlRequestCallback.ResponseStep;
import org.chromium.net.impl.CronetUrlRequest;
import org.chromium.net.test.FailurePhase;

import java.io.IOException;
import java.net.ConnectException;
import java.nio.ByteBuffer;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Test functionality of CronetUrlRequest.
 */
public class CronetUrlRequestTest extends CronetTestBase {
    // URL used for base tests.
    private static final String TEST_URL = "http://127.0.0.1:8000";

    private CronetTestFramework mTestFramework;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mTestFramework = startCronetTestFramework();
        assertTrue(NativeTestServer.startNativeTestServer(getContext()));
        // Add url interceptors after native application context is initialized.
        MockUrlRequestJobFactory.setUp();
    }

    @Override
    protected void tearDown() throws Exception {
        NativeTestServer.shutdownNativeTestServer();
        mTestFramework.mCronetEngine.shutdown();
        super.tearDown();
    }

    private TestUrlRequestCallback startAndWaitForComplete(String url) throws Exception {
        TestUrlRequestCallback callback = new TestUrlRequestCallback();
        // Create request.
        UrlRequest.Builder builder = new UrlRequest.Builder(
                url, callback, callback.getExecutor(), mTestFramework.mCronetEngine);
        UrlRequest urlRequest = builder.build();
        urlRequest.start();
        callback.blockForDone();
        assertTrue(urlRequest.isDone());
        return callback;
    }

    private void checkResponseInfo(UrlResponseInfo responseInfo, String expectedUrl,
            int expectedHttpStatusCode, String expectedHttpStatusText) {
        assertEquals(expectedUrl, responseInfo.getUrl());
        assertEquals(
                expectedUrl, responseInfo.getUrlChain().get(responseInfo.getUrlChain().size() - 1));
        assertEquals(expectedHttpStatusCode, responseInfo.getHttpStatusCode());
        assertEquals(expectedHttpStatusText, responseInfo.getHttpStatusText());
        assertFalse(responseInfo.wasCached());
        assertTrue(responseInfo.toString().length() > 0);
    }

    private void checkResponseInfoHeader(
            UrlResponseInfo responseInfo, String headerName, String headerValue) {
        Map<String, List<String>> responseHeaders =
                responseInfo.getAllHeaders();
        List<String> header = responseHeaders.get(headerName);
        assertNotNull(header);
        assertTrue(header.contains(headerValue));
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testBuilderChecks() throws Exception {
        TestUrlRequestCallback callback = new TestUrlRequestCallback();
        try {
            new UrlRequest.Builder(
                    null, callback, callback.getExecutor(), mTestFramework.mCronetEngine);
            fail("URL not null-checked");
        } catch (NullPointerException e) {
            assertEquals("URL is required.", e.getMessage());
        }
        try {
            new UrlRequest.Builder(NativeTestServer.getRedirectURL(), null, callback.getExecutor(),
                    mTestFramework.mCronetEngine);
            fail("Callback not null-checked");
        } catch (NullPointerException e) {
            assertEquals("Callback is required.", e.getMessage());
        }
        try {
            new UrlRequest.Builder(NativeTestServer.getRedirectURL(), callback, null,
                    mTestFramework.mCronetEngine);
            fail("Executor not null-checked");
        } catch (NullPointerException e) {
            assertEquals("Executor is required.", e.getMessage());
        }
        try {
            new UrlRequest.Builder(
                    NativeTestServer.getRedirectURL(), callback, callback.getExecutor(), null);
            fail("CronetEngine not null-checked");
        } catch (NullPointerException e) {
            assertEquals("CronetEngine is required.", e.getMessage());
        }
        // Verify successful creation doesn't throw.
        new UrlRequest.Builder(NativeTestServer.getRedirectURL(), callback, callback.getExecutor(),
                mTestFramework.mCronetEngine);
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testSimpleGet() throws Exception {
        String url = NativeTestServer.getEchoMethodURL();
        TestUrlRequestCallback callback = startAndWaitForComplete(url);
        assertEquals(200, callback.mResponseInfo.getHttpStatusCode());
        // Default method is 'GET'.
        assertEquals("GET", callback.mResponseAsString);
        assertEquals(0, callback.mRedirectCount);
        assertEquals(callback.mResponseStep, ResponseStep.ON_SUCCEEDED);
        UrlResponseInfo urlResponseInfo = createUrlResponseInfo(new String[] {url}, "OK", 200, 86,
                "Connection", "close", "Content-Length", "3", "Content-Type", "text/plain");
        assertResponseEquals(urlResponseInfo, callback.mResponseInfo);
        checkResponseInfo(callback.mResponseInfo, NativeTestServer.getEchoMethodURL(), 200, "OK");
    }

    UrlResponseInfo createUrlResponseInfo(
            String[] urls, String message, int statusCode, int receivedBytes, String... headers) {
        ArrayList<Map.Entry<String, String>> headersList = new ArrayList<>();
        for (int i = 0; i < headers.length; i += 2) {
            headersList.add(new AbstractMap.SimpleImmutableEntry<String, String>(
                    headers[i], headers[i + 1]));
        }
        UrlResponseInfo unknown = new UrlResponseInfo(
                Arrays.asList(urls), statusCode, message, headersList, false, "unknown", ":0");
        unknown.setReceivedBytesCount(receivedBytes);
        return unknown;
    }

    void runConnectionMigrationTest(boolean disableConnectionMigration) {
        // URLRequest load flags at net/base/load_flags_list.h.
        int connectionMigrationLoadFlag = 1 << 18;
        TestUrlRequestCallback callback = new TestUrlRequestCallback();
        callback.setAutoAdvance(false);
        // Create builder, start a request, and check if default load_flags are set correctly.
        UrlRequest.Builder builder =
                new UrlRequest.Builder(NativeTestServer.getFileURL("/success.txt"), callback,
                        callback.getExecutor(), mTestFramework.mCronetEngine);
        // Disable connection migration.
        if (disableConnectionMigration) builder.disableConnectionMigration();
        UrlRequest urlRequest = builder.build();
        urlRequest.start();
        callback.waitForNextStep();
        int loadFlags = CronetTestUtil.getLoadFlags(urlRequest);
        if (disableConnectionMigration) {
            assertEquals(connectionMigrationLoadFlag, loadFlags & connectionMigrationLoadFlag);
        } else {
            assertEquals(0, loadFlags & connectionMigrationLoadFlag);
        }
        callback.setAutoAdvance(true);
        callback.startNextRead(urlRequest);
        callback.blockForDone();
    }

    /**
     * Tests that disabling connection migration sets the URLRequest load flag correctly.
     */
    @SmallTest
    @Feature({"Cronet"})
    @OnlyRunNativeCronet
    public void testLoadFlagsWithConnectionMigration() throws Exception {
        runConnectionMigrationTest(/*disableConnectionMigration=*/false);
        runConnectionMigrationTest(/*disableConnectionMigration=*/true);
    }

    /**
     * Tests a redirect by running it step-by-step. Also tests that delaying a
     * request works as expected. To make sure there are no unexpected pending
     * messages, does a GET between UrlRequest.Callback callbacks.
     */
    @SmallTest
    @Feature({"Cronet"})
    public void testRedirectAsync() throws Exception {
        // Start the request and wait to see the redirect.
        TestUrlRequestCallback callback = new TestUrlRequestCallback();
        callback.setAutoAdvance(false);
        UrlRequest.Builder builder = new UrlRequest.Builder(NativeTestServer.getRedirectURL(),
                callback, callback.getExecutor(), mTestFramework.mCronetEngine);
        UrlRequest urlRequest = builder.build();
        urlRequest.start();
        callback.waitForNextStep();

        // Check the redirect.
        assertEquals(ResponseStep.ON_RECEIVED_REDIRECT, callback.mResponseStep);
        assertEquals(1, callback.mRedirectResponseInfoList.size());
        checkResponseInfo(callback.mRedirectResponseInfoList.get(0),
                NativeTestServer.getRedirectURL(), 302, "Found");
        assertEquals(1, callback.mRedirectResponseInfoList.get(0).getUrlChain().size());
        assertEquals(NativeTestServer.getSuccessURL(), callback.mRedirectUrlList.get(0));
        checkResponseInfoHeader(
                callback.mRedirectResponseInfoList.get(0), "redirect-header", "header-value");

        UrlResponseInfo expected =
                createUrlResponseInfo(new String[] {NativeTestServer.getRedirectURL()}, "Found",
                        302, 74, "Location", "/success.txt", "redirect-header", "header-value");
        assertResponseEquals(expected, callback.mRedirectResponseInfoList.get(0));

        // Wait for an unrelated request to finish. The request should not
        // advance until followRedirect is invoked.
        testSimpleGet();
        assertEquals(ResponseStep.ON_RECEIVED_REDIRECT, callback.mResponseStep);
        assertEquals(1, callback.mRedirectResponseInfoList.size());

        // Follow the redirect and wait for the next set of headers.
        urlRequest.followRedirect();
        callback.waitForNextStep();

        assertEquals(ResponseStep.ON_RESPONSE_STARTED, callback.mResponseStep);
        assertEquals(1, callback.mRedirectResponseInfoList.size());
        assertEquals(200, callback.mResponseInfo.getHttpStatusCode());
        checkResponseInfo(callback.mResponseInfo, NativeTestServer.getSuccessURL(), 200, "OK");
        assertEquals(2, callback.mResponseInfo.getUrlChain().size());
        assertEquals(
                NativeTestServer.getRedirectURL(), callback.mResponseInfo.getUrlChain().get(0));
        assertEquals(NativeTestServer.getSuccessURL(), callback.mResponseInfo.getUrlChain().get(1));

        // Wait for an unrelated request to finish. The request should not
        // advance until read is invoked.
        testSimpleGet();
        assertEquals(ResponseStep.ON_RESPONSE_STARTED, callback.mResponseStep);

        // One read should get all the characters, but best not to depend on
        // how much is actually read from the socket at once.
        while (!callback.isDone()) {
            callback.startNextRead(urlRequest);
            callback.waitForNextStep();
            String response = callback.mResponseAsString;
            ResponseStep step = callback.mResponseStep;
            if (!callback.isDone()) {
                assertEquals(ResponseStep.ON_READ_COMPLETED, step);
            }
            // Should not receive any messages while waiting for another get,
            // as the next read has not been started.
            testSimpleGet();
            assertEquals(response, callback.mResponseAsString);
            assertEquals(step, callback.mResponseStep);
        }
        assertEquals(ResponseStep.ON_SUCCEEDED, callback.mResponseStep);
        assertEquals(NativeTestServer.SUCCESS_BODY, callback.mResponseAsString);

        UrlResponseInfo urlResponseInfo = createUrlResponseInfo(
                new String[] {NativeTestServer.getRedirectURL(), NativeTestServer.getSuccessURL()},
                "OK", 200, 260, "Content-Type", "text/plain", "Access-Control-Allow-Origin", "*",
                "header-name", "header-value", "multi-header-name", "header-value1",
                "multi-header-name", "header-value2");

        assertResponseEquals(urlResponseInfo, callback.mResponseInfo);
        // Make sure there are no other pending messages, which would trigger
        // asserts in TestUrlRequestCallback.
        testSimpleGet();
    }

    /**
     * Tests onRedirectReceived after cancel doesn't cause a crash.
     */
    @SmallTest
    @Feature({"Cronet"})
    public void testOnRedirectReceivedAfterCancel() throws Exception {
        final AtomicBoolean failedExpectation = new AtomicBoolean();
        TestUrlRequestCallback callback = new TestUrlRequestCallback() {
            @Override
            public void onRedirectReceived(
                    UrlRequest request, UrlResponseInfo info, String newLocationUrl) {
                assertEquals(0, mRedirectCount);
                failedExpectation.compareAndSet(false, 0 != mRedirectCount);
                super.onRedirectReceived(request, info, newLocationUrl);
                // Cancel the request, so the second redirect will not be received.
                request.cancel();
            }

            @Override
            public void onResponseStarted(UrlRequest request, UrlResponseInfo info) {
                failedExpectation.set(true);
                fail();
            }

            @Override
            public void onReadCompleted(
                    UrlRequest request, UrlResponseInfo info, ByteBuffer byteBuffer) {
                failedExpectation.set(true);
                fail();
            }

            @Override
            public void onSucceeded(UrlRequest request, UrlResponseInfo info) {
                failedExpectation.set(true);
                fail();
            }

            @Override
            public void onFailed(
                    UrlRequest request, UrlResponseInfo info, UrlRequestException error) {
                failedExpectation.set(true);
                fail();
            }

            @Override
            public void onCanceled(UrlRequest request, UrlResponseInfo info) {
                assertEquals(1, mRedirectCount);
                failedExpectation.compareAndSet(false, 1 != mRedirectCount);
                super.onCanceled(request, info);
            }
        };

        UrlRequest.Builder builder = new UrlRequest.Builder(NativeTestServer.getMultiRedirectURL(),
                callback, callback.getExecutor(), mTestFramework.mCronetEngine);

        final UrlRequest urlRequest = builder.build();
        urlRequest.start();
        callback.blockForDone();
        assertFalse(failedExpectation.get());
        // Check that only one redirect is received.
        assertEquals(1, callback.mRedirectCount);
        // Check that onCanceled is called.
        assertTrue(callback.mOnCanceledCalled);
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testNotFound() throws Exception {
        String url = NativeTestServer.getFileURL("/notfound.html");
        TestUrlRequestCallback callback = startAndWaitForComplete(url);
        checkResponseInfo(callback.mResponseInfo, url, 404, "Not Found");
        assertEquals("<!DOCTYPE html>\n<html>\n<head>\n<title>Not found</title>\n"
                        + "<p>Test page loaded.</p>\n</head>\n</html>\n",
                callback.mResponseAsString);
        assertEquals(0, callback.mRedirectCount);
        assertEquals(callback.mResponseStep, ResponseStep.ON_SUCCEEDED);
    }

    // Checks that UrlRequest.Callback.onFailed is only called once in the case
    // of ERR_CONTENT_LENGTH_MISMATCH, which has an unusual failure path.
    // See http://crbug.com/468803.
    @SmallTest
    @Feature({"Cronet"})
    @OnlyRunNativeCronet // No canonical exception to assert on
    public void testContentLengthMismatchFailsOnce() throws Exception {
        String url = NativeTestServer.getFileURL(
                "/content_length_mismatch.html");
        TestUrlRequestCallback callback = startAndWaitForComplete(url);
        assertEquals(200, callback.mResponseInfo.getHttpStatusCode());
        // The entire response body will be read before the error is returned.
        // This is because the network stack returns data as it's read from the
        // socket, and the socket close message which triggers the error will
        // only be passed along after all data has been read.
        assertEquals("Response that lies about content length.", callback.mResponseAsString);
        assertNotNull(callback.mError);
        assertEquals("Exception in CronetUrlRequest: net::ERR_CONTENT_LENGTH_MISMATCH",
                callback.mError.getMessage());
        // Wait for a couple round trips to make sure there are no pending
        // onFailed messages. This test relies on checks in
        // TestUrlRequestCallback catching a second onFailed call.
        testSimpleGet();
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testSetHttpMethod() throws Exception {
        TestUrlRequestCallback callback = new TestUrlRequestCallback();
        String methodName = "HEAD";
        UrlRequest.Builder builder = new UrlRequest.Builder(NativeTestServer.getEchoMethodURL(),
                callback, callback.getExecutor(), mTestFramework.mCronetEngine);
        // Try to set 'null' method.
        try {
            builder.setHttpMethod(null);
            fail("Exception not thrown");
        } catch (NullPointerException e) {
            assertEquals("Method is required.", e.getMessage());
        }

        builder.setHttpMethod(methodName);
        builder.build().start();
        callback.blockForDone();
        assertEquals(200, callback.mResponseInfo.getHttpStatusCode());
        assertEquals(0, callback.mHttpResponseDataLength);
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testBadMethod() throws Exception {
        TestUrlRequestCallback callback = new TestUrlRequestCallback();
        UrlRequest.Builder builder = new UrlRequest.Builder(
                TEST_URL, callback, callback.getExecutor(), mTestFramework.mCronetEngine);
        try {
            builder.setHttpMethod("bad:method!");
            builder.build().start();
            fail("IllegalArgumentException not thrown.");
        } catch (IllegalArgumentException e) {
            assertEquals("Invalid http method bad:method!",
                    e.getMessage());
        }
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testBadHeaderName() throws Exception {
        TestUrlRequestCallback callback = new TestUrlRequestCallback();
        UrlRequest.Builder builder = new UrlRequest.Builder(
                TEST_URL, callback, callback.getExecutor(), mTestFramework.mCronetEngine);
        try {
            builder.addHeader("header:name", "headervalue");
            builder.build().start();
            fail("IllegalArgumentException not thrown.");
        } catch (IllegalArgumentException e) {
            assertEquals("Invalid header header:name=headervalue",
                    e.getMessage());
        }
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testAcceptEncodingIgnored() throws Exception {
        TestUrlRequestCallback callback = new TestUrlRequestCallback();
        UrlRequest.Builder builder = new UrlRequest.Builder(NativeTestServer.getEchoAllHeadersURL(),
                callback, callback.getExecutor(), mTestFramework.mCronetEngine);
        // This line should eventually throw an exception, once callers have migrated
        builder.addHeader("accept-encoding", "foozip");
        builder.build().start();
        callback.blockForDone();
        assertFalse(callback.mResponseAsString.contains("foozip"));
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testBadHeaderValue() throws Exception {
        TestUrlRequestCallback callback = new TestUrlRequestCallback();
        UrlRequest.Builder builder = new UrlRequest.Builder(
                TEST_URL, callback, callback.getExecutor(), mTestFramework.mCronetEngine);
        try {
            builder.addHeader("headername", "bad header\r\nvalue");
            builder.build().start();
            fail("IllegalArgumentException not thrown.");
        } catch (IllegalArgumentException e) {
            assertEquals("Invalid header headername=bad header\r\nvalue",
                    e.getMessage());
        }
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testAddHeader() throws Exception {
        TestUrlRequestCallback callback = new TestUrlRequestCallback();
        String headerName = "header-name";
        String headerValue = "header-value";
        UrlRequest.Builder builder =
                new UrlRequest.Builder(NativeTestServer.getEchoHeaderURL(headerName), callback,
                        callback.getExecutor(), mTestFramework.mCronetEngine);

        builder.addHeader(headerName, headerValue);
        builder.build().start();
        callback.blockForDone();
        assertEquals(200, callback.mResponseInfo.getHttpStatusCode());
        assertEquals(headerValue, callback.mResponseAsString);
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testMultiRequestHeaders() throws Exception {
        TestUrlRequestCallback callback = new TestUrlRequestCallback();
        String headerName = "header-name";
        String headerValue1 = "header-value1";
        String headerValue2 = "header-value2";
        UrlRequest.Builder builder = new UrlRequest.Builder(NativeTestServer.getEchoAllHeadersURL(),
                callback, callback.getExecutor(), mTestFramework.mCronetEngine);
        builder.addHeader(headerName, headerValue1);
        builder.addHeader(headerName, headerValue2);
        builder.build().start();
        callback.blockForDone();
        assertEquals(200, callback.mResponseInfo.getHttpStatusCode());
        String headers = callback.mResponseAsString;
        Pattern pattern = Pattern.compile(headerName + ":\\s(.*)\\r\\n");
        Matcher matcher = pattern.matcher(headers);
        List<String> actualValues = new ArrayList<String>();
        while (matcher.find()) {
            actualValues.add(matcher.group(1));
        }
        assertEquals(1, actualValues.size());
        assertEquals("header-value2", actualValues.get(0));
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testCustomUserAgent() throws Exception {
        TestUrlRequestCallback callback = new TestUrlRequestCallback();
        String userAgentName = "User-Agent";
        String userAgentValue = "User-Agent-Value";
        UrlRequest.Builder builder =
                new UrlRequest.Builder(NativeTestServer.getEchoHeaderURL(userAgentName), callback,
                        callback.getExecutor(), mTestFramework.mCronetEngine);
        builder.addHeader(userAgentName, userAgentValue);
        builder.build().start();
        callback.blockForDone();
        assertEquals(200, callback.mResponseInfo.getHttpStatusCode());
        assertEquals(userAgentValue, callback.mResponseAsString);
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testDefaultUserAgent() throws Exception {
        TestUrlRequestCallback callback = new TestUrlRequestCallback();
        String headerName = "User-Agent";
        UrlRequest.Builder builder =
                new UrlRequest.Builder(NativeTestServer.getEchoHeaderURL(headerName), callback,
                        callback.getExecutor(), mTestFramework.mCronetEngine);
        builder.build().start();
        callback.blockForDone();
        assertEquals(200, callback.mResponseInfo.getHttpStatusCode());
        assertTrue("Default User-Agent should contain Cronet/n.n.n.n but is "
                        + callback.mResponseAsString,
                Pattern.matches(
                        ".+Cronet/\\d+\\.\\d+\\.\\d+\\.\\d+.+", callback.mResponseAsString));
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testMockSuccess() throws Exception {
        TestUrlRequestCallback callback = startAndWaitForComplete(NativeTestServer.getSuccessURL());
        assertEquals(200, callback.mResponseInfo.getHttpStatusCode());
        assertEquals(0, callback.mRedirectResponseInfoList.size());
        assertTrue(callback.mHttpResponseDataLength != 0);
        assertEquals(callback.mResponseStep, ResponseStep.ON_SUCCEEDED);
        Map<String, List<String>> responseHeaders = callback.mResponseInfo.getAllHeaders();
        assertEquals("header-value", responseHeaders.get("header-name").get(0));
        List<String> multiHeader = responseHeaders.get("multi-header-name");
        assertEquals(2, multiHeader.size());
        assertEquals("header-value1", multiHeader.get(0));
        assertEquals("header-value2", multiHeader.get(1));
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testResponseHeadersList() throws Exception {
        TestUrlRequestCallback callback = startAndWaitForComplete(NativeTestServer.getSuccessURL());
        assertEquals(200, callback.mResponseInfo.getHttpStatusCode());
        List<Map.Entry<String, String>> responseHeaders =
                callback.mResponseInfo.getAllHeadersAsList();

        MoreAsserts.assertContentsInOrder(responseHeaders,
                new AbstractMap.SimpleEntry<>("Content-Type", "text/plain"),
                new AbstractMap.SimpleEntry<>("Access-Control-Allow-Origin", "*"),
                new AbstractMap.SimpleEntry<>("header-name", "header-value"),
                new AbstractMap.SimpleEntry<>("multi-header-name", "header-value1"),
                new AbstractMap.SimpleEntry<>("multi-header-name", "header-value2"));
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testMockMultiRedirect() throws Exception {
        TestUrlRequestCallback callback =
                startAndWaitForComplete(NativeTestServer.getMultiRedirectURL());
        UrlResponseInfo mResponseInfo = callback.mResponseInfo;
        assertEquals(2, callback.mRedirectCount);
        assertEquals(200, mResponseInfo.getHttpStatusCode());
        assertEquals(2, callback.mRedirectResponseInfoList.size());

        // Check first redirect (multiredirect.html -> redirect.html)
        UrlResponseInfo firstExpectedResponseInfo = createUrlResponseInfo(
                new String[] {NativeTestServer.getMultiRedirectURL()}, "Found", 302, 77, "Location",
                "/redirect.html", "redirect-header0", "header-value");
        UrlResponseInfo firstRedirectResponseInfo = callback.mRedirectResponseInfoList.get(0);
        assertResponseEquals(firstExpectedResponseInfo, firstRedirectResponseInfo);

        // Check second redirect (redirect.html -> success.txt)
        UrlResponseInfo secondExpectedResponseInfo = createUrlResponseInfo(
                new String[] {NativeTestServer.getMultiRedirectURL(),
                        NativeTestServer.getRedirectURL(), NativeTestServer.getSuccessURL()},
                "OK", 200, 337, "Content-Type", "text/plain", "Access-Control-Allow-Origin", "*",
                "header-name", "header-value", "multi-header-name", "header-value1",
                "multi-header-name", "header-value2");

        assertResponseEquals(secondExpectedResponseInfo, mResponseInfo);
        assertTrue(callback.mHttpResponseDataLength != 0);
        assertEquals(2, callback.mRedirectCount);
        assertEquals(callback.mResponseStep, ResponseStep.ON_SUCCEEDED);
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testMockNotFound() throws Exception {
        TestUrlRequestCallback callback =
                startAndWaitForComplete(NativeTestServer.getNotFoundURL());
        UrlResponseInfo expected = createUrlResponseInfo(
                new String[] {NativeTestServer.getNotFoundURL()}, "Not Found", 404, 121);
        assertResponseEquals(expected, callback.mResponseInfo);
        assertTrue(callback.mHttpResponseDataLength != 0);
        assertEquals(0, callback.mRedirectCount);
        assertFalse(callback.mOnErrorCalled);
        assertEquals(callback.mResponseStep, ResponseStep.ON_SUCCEEDED);
    }

    @SmallTest
    @Feature({"Cronet"})
    @OnlyRunNativeCronet // Java impl doesn't support MockUrlRequestJobFactory
    public void testMockStartAsyncError() throws Exception {
        final int arbitraryNetError = -3;
        TestUrlRequestCallback callback =
                startAndWaitForComplete(MockUrlRequestJobFactory.getMockUrlWithFailure(
                        FailurePhase.START, arbitraryNetError));
        assertNull(callback.mResponseInfo);
        assertNotNull(callback.mError);
        assertEquals(arbitraryNetError, callback.mError.getCronetInternalErrorCode());
        assertEquals(0, callback.mRedirectCount);
        assertTrue(callback.mOnErrorCalled);
        assertEquals(callback.mResponseStep, ResponseStep.NOTHING);
    }

    @SmallTest
    @Feature({"Cronet"})
    @OnlyRunNativeCronet // Java impl doesn't support MockUrlRequestJobFactory
    public void testMockReadDataSyncError() throws Exception {
        final int arbitraryNetError = -4;
        TestUrlRequestCallback callback =
                startAndWaitForComplete(MockUrlRequestJobFactory.getMockUrlWithFailure(
                        FailurePhase.READ_SYNC, arbitraryNetError));
        assertEquals(200, callback.mResponseInfo.getHttpStatusCode());
        assertEquals(0, callback.mResponseInfo.getReceivedBytesCount());
        assertNotNull(callback.mError);
        assertEquals(arbitraryNetError, callback.mError.getCronetInternalErrorCode());
        assertEquals(0, callback.mRedirectCount);
        assertTrue(callback.mOnErrorCalled);
        assertEquals(callback.mResponseStep, ResponseStep.ON_RESPONSE_STARTED);
    }

    @SmallTest
    @Feature({"Cronet"})
    @OnlyRunNativeCronet // Java impl doesn't support MockUrlRequestJobFactory
    public void testMockReadDataAsyncError() throws Exception {
        final int arbitraryNetError = -5;
        TestUrlRequestCallback callback =
                startAndWaitForComplete(MockUrlRequestJobFactory.getMockUrlWithFailure(
                        FailurePhase.READ_ASYNC, arbitraryNetError));
        assertEquals(200, callback.mResponseInfo.getHttpStatusCode());
        assertEquals(0, callback.mResponseInfo.getReceivedBytesCount());
        assertNotNull(callback.mError);
        assertEquals(arbitraryNetError, callback.mError.getCronetInternalErrorCode());
        assertEquals(0, callback.mRedirectCount);
        assertTrue(callback.mOnErrorCalled);
        assertEquals(callback.mResponseStep, ResponseStep.ON_RESPONSE_STARTED);
    }

    /**
     * Tests that request continues when client certificate is requested.
     */
    @SmallTest
    @Feature({"Cronet"})
    @OnlyRunNativeCronet
    public void testMockClientCertificateRequested() throws Exception {
        TestUrlRequestCallback callback = startAndWaitForComplete(
                MockUrlRequestJobFactory.getMockUrlForClientCertificateRequest());
        assertNotNull(callback.mResponseInfo);
        assertEquals(200, callback.mResponseInfo.getHttpStatusCode());
        assertEquals("data", callback.mResponseAsString);
        assertEquals(0, callback.mRedirectCount);
        assertNull(callback.mError);
        assertFalse(callback.mOnErrorCalled);
    }

    /**
     * Tests that an SSL cert error will be reported via {@link UrlRequest#onFailed}.
     */
    @SmallTest
    @Feature({"Cronet"})
    @OnlyRunNativeCronet // Java impl doesn't support MockUrlRequestJobFactory
    public void testMockSSLCertificateError() throws Exception {
        TestUrlRequestCallback callback = startAndWaitForComplete(
                MockUrlRequestJobFactory.getMockUrlForSSLCertificateError());
        assertNull(callback.mResponseInfo);
        assertNotNull(callback.mError);
        assertTrue(callback.mOnErrorCalled);
        assertEquals(-201, callback.mError.getCronetInternalErrorCode());
        assertEquals("Exception in CronetUrlRequest: net::ERR_CERT_DATE_INVALID",
                callback.mError.getMessage());
        assertEquals(callback.mResponseStep, ResponseStep.NOTHING);
    }

    /**
     * Checks that the buffer is updated correctly, when starting at an offset.
     */
    @SmallTest
    @Feature({"Cronet"})
    public void testSimpleGetBufferUpdates() throws Exception {
        TestUrlRequestCallback callback = new TestUrlRequestCallback();
        callback.setAutoAdvance(false);
        // Since the default method is "GET", the expected response body is also
        // "GET".
        UrlRequest.Builder builder = new UrlRequest.Builder(NativeTestServer.getEchoMethodURL(),
                callback, callback.getExecutor(), mTestFramework.mCronetEngine);
        UrlRequest urlRequest = builder.build();
        urlRequest.start();
        callback.waitForNextStep();

        ByteBuffer readBuffer = ByteBuffer.allocateDirect(5);
        readBuffer.put("FOR".getBytes());
        assertEquals(3, readBuffer.position());

        // Read first two characters of the response ("GE"). It's theoretically
        // possible to need one read per character, though in practice,
        // shouldn't happen.
        while (callback.mResponseAsString.length() < 2) {
            assertFalse(callback.isDone());
            callback.startNextRead(urlRequest, readBuffer);
            callback.waitForNextStep();
        }

        // Make sure the two characters were read.
        assertEquals("GE", callback.mResponseAsString);

        // Check the contents of the entire buffer. The first 3 characters
        // should not have been changed, and the last two should be the first
        // two characters from the response.
        assertEquals("FORGE", bufferContentsToString(readBuffer, 0, 5));
        // The limit and position should be 5.
        assertEquals(5, readBuffer.limit());
        assertEquals(5, readBuffer.position());

        assertEquals(ResponseStep.ON_READ_COMPLETED, callback.mResponseStep);

        // Start reading from position 3. Since the only remaining character
        // from the response is a "T", when the read completes, the buffer
        // should contain "FORTE", with a position() of 4 and a limit() of 5.
        readBuffer.position(3);
        callback.startNextRead(urlRequest, readBuffer);
        callback.waitForNextStep();

        // Make sure all three characters of the response have now been read.
        assertEquals("GET", callback.mResponseAsString);

        // Check the entire contents of the buffer. Only the third character
        // should have been modified.
        assertEquals("FORTE", bufferContentsToString(readBuffer, 0, 5));

        // Make sure position and limit were updated correctly.
        assertEquals(4, readBuffer.position());
        assertEquals(5, readBuffer.limit());

        assertEquals(ResponseStep.ON_READ_COMPLETED, callback.mResponseStep);

        // One more read attempt. The request should complete.
        readBuffer.position(1);
        readBuffer.limit(5);
        callback.startNextRead(urlRequest, readBuffer);
        callback.waitForNextStep();

        assertEquals(200, callback.mResponseInfo.getHttpStatusCode());
        assertEquals("GET", callback.mResponseAsString);
        checkResponseInfo(callback.mResponseInfo, NativeTestServer.getEchoMethodURL(), 200, "OK");

        // Check that buffer contents were not modified.
        assertEquals("FORTE", bufferContentsToString(readBuffer, 0, 5));

        // Position should not have been modified, since nothing was read.
        assertEquals(1, readBuffer.position());
        // Limit should be unchanged as always.
        assertEquals(5, readBuffer.limit());

        assertEquals(ResponseStep.ON_SUCCEEDED, callback.mResponseStep);

        // Make sure there are no other pending messages, which would trigger
        // asserts in TestUrlRequestCallback.
        testSimpleGet();
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testBadBuffers() throws Exception {
        TestUrlRequestCallback callback = new TestUrlRequestCallback();
        callback.setAutoAdvance(false);
        UrlRequest.Builder builder = new UrlRequest.Builder(NativeTestServer.getEchoMethodURL(),
                callback, callback.getExecutor(), mTestFramework.mCronetEngine);
        UrlRequest urlRequest = builder.build();
        urlRequest.start();
        callback.waitForNextStep();

        // Try to read using a full buffer.
        try {
            ByteBuffer readBuffer = ByteBuffer.allocateDirect(4);
            readBuffer.put("full".getBytes());
            urlRequest.read(readBuffer);
            fail("Exception not thrown");
        } catch (IllegalArgumentException e) {
            assertEquals("ByteBuffer is already full.",
                    e.getMessage());
        }

        // Try to read using a non-direct buffer.
        try {
            ByteBuffer readBuffer = ByteBuffer.allocate(5);
            urlRequest.read(readBuffer);
            fail("Exception not thrown");
        } catch (IllegalArgumentException e) {
            assertEquals("byteBuffer must be a direct ByteBuffer.",
                    e.getMessage());
        }

        // Finish the request with a direct ByteBuffer.
        callback.setAutoAdvance(true);
        ByteBuffer readBuffer = ByteBuffer.allocateDirect(5);
        urlRequest.read(readBuffer);
        callback.blockForDone();
        assertEquals(200, callback.mResponseInfo.getHttpStatusCode());
        assertEquals("GET", callback.mResponseAsString);
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testUnexpectedReads() throws Exception {
        final TestUrlRequestCallback callback = new TestUrlRequestCallback();
        callback.setAutoAdvance(false);
        final UrlRequest urlRequest =
                new UrlRequest.Builder(NativeTestServer.getRedirectURL(), callback,
                                      callback.getExecutor(), mTestFramework.mCronetEngine)
                        .build();

        // Try to read before starting request.
        try {
            callback.startNextRead(urlRequest);
            fail("Exception not thrown");
        } catch (IllegalStateException e) {
        }

        // Verify reading right after start throws an assertion. Both must be
        // invoked on the Executor thread, to prevent receiving data until after
        // startNextRead has been invoked.
        Runnable startAndRead = new Runnable() {
            @Override
            public void run() {
                urlRequest.start();
                try {
                    callback.startNextRead(urlRequest);
                    fail("Exception not thrown");
                } catch (IllegalStateException e) {
                }
            }
        };
        callback.getExecutor().execute(startAndRead);
        callback.waitForNextStep();

        assertEquals(callback.mResponseStep, ResponseStep.ON_RECEIVED_REDIRECT);
        // Try to read after the redirect.
        try {
            callback.startNextRead(urlRequest);
            fail("Exception not thrown");
        } catch (IllegalStateException e) {
        }
        urlRequest.followRedirect();
        callback.waitForNextStep();

        assertEquals(callback.mResponseStep, ResponseStep.ON_RESPONSE_STARTED);
        assertEquals(200, callback.mResponseInfo.getHttpStatusCode());

        while (!callback.isDone()) {
            Runnable readTwice = new Runnable() {
                @Override
                public void run() {
                    callback.startNextRead(urlRequest);
                    // Try to read again before the last read completes.
                    try {
                        callback.startNextRead(urlRequest);
                        fail("Exception not thrown");
                    } catch (IllegalStateException e) {
                    }
                }
            };
            callback.getExecutor().execute(readTwice);
            callback.waitForNextStep();
        }

        assertEquals(callback.mResponseStep, ResponseStep.ON_SUCCEEDED);
        assertEquals(NativeTestServer.SUCCESS_BODY, callback.mResponseAsString);

        // Try to read after request is complete.
        try {
            callback.startNextRead(urlRequest);
            fail("Exception not thrown");
        } catch (IllegalStateException e) {
        }
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testUnexpectedFollowRedirects() throws Exception {
        final TestUrlRequestCallback callback = new TestUrlRequestCallback();
        callback.setAutoAdvance(false);
        final UrlRequest urlRequest =
                new UrlRequest.Builder(NativeTestServer.getRedirectURL(), callback,
                                      callback.getExecutor(), mTestFramework.mCronetEngine)
                        .build();

        // Try to follow a redirect before starting the request.
        try {
            urlRequest.followRedirect();
            fail("Exception not thrown");
        } catch (IllegalStateException e) {
        }

        // Try to follow a redirect just after starting the request. Has to be
        // done on the executor thread to avoid a race.
        Runnable startAndRead = new Runnable() {
            @Override
            public void run() {
                urlRequest.start();
                try {
                    urlRequest.followRedirect();
                    fail("Exception not thrown");
                } catch (IllegalStateException e) {
                }
            }
        };
        callback.getExecutor().execute(startAndRead);
        callback.waitForNextStep();

        assertEquals(callback.mResponseStep, ResponseStep.ON_RECEIVED_REDIRECT);
        // Try to follow the redirect twice. Second attempt should fail.
        Runnable followRedirectTwice = new Runnable() {
            @Override
            public void run() {
                urlRequest.followRedirect();
                try {
                    urlRequest.followRedirect();
                    fail("Exception not thrown");
                } catch (IllegalStateException e) {
                }
            }
        };
        callback.getExecutor().execute(followRedirectTwice);
        callback.waitForNextStep();

        assertEquals(callback.mResponseStep, ResponseStep.ON_RESPONSE_STARTED);
        assertEquals(200, callback.mResponseInfo.getHttpStatusCode());

        while (!callback.isDone()) {
            try {
                urlRequest.followRedirect();
                fail("Exception not thrown");
            } catch (IllegalStateException e) {
            }
            callback.startNextRead(urlRequest);
            callback.waitForNextStep();
        }

        assertEquals(callback.mResponseStep, ResponseStep.ON_SUCCEEDED);
        assertEquals(NativeTestServer.SUCCESS_BODY, callback.mResponseAsString);

        // Try to follow redirect after request is complete.
        try {
            urlRequest.followRedirect();
            fail("Exception not thrown");
        } catch (IllegalStateException e) {
        }
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testUploadSetDataProvider() throws Exception {
        TestUrlRequestCallback callback = new TestUrlRequestCallback();
        UrlRequest.Builder builder = new UrlRequest.Builder(NativeTestServer.getEchoBodyURL(),
                callback, callback.getExecutor(), mTestFramework.mCronetEngine);

        try {
            builder.setUploadDataProvider(null, callback.getExecutor());
            fail("Exception not thrown");
        } catch (NullPointerException e) {
            assertEquals("Invalid UploadDataProvider.", e.getMessage());
        }

        TestUploadDataProvider dataProvider = new TestUploadDataProvider(
                TestUploadDataProvider.SuccessCallbackMode.SYNC, callback.getExecutor());
        builder.setUploadDataProvider(dataProvider, callback.getExecutor());
        try {
            builder.build().start();
            fail("Exception not thrown");
        } catch (IllegalArgumentException e) {
        }
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testUploadEmptyBodySync() throws Exception {
        TestUrlRequestCallback callback = new TestUrlRequestCallback();
        UrlRequest.Builder builder = new UrlRequest.Builder(NativeTestServer.getEchoBodyURL(),
                callback, callback.getExecutor(), mTestFramework.mCronetEngine);

        TestUploadDataProvider dataProvider = new TestUploadDataProvider(
                TestUploadDataProvider.SuccessCallbackMode.SYNC, callback.getExecutor());
        builder.setUploadDataProvider(dataProvider, callback.getExecutor());
        builder.addHeader("Content-Type", "useless/string");
        builder.build().start();
        callback.blockForDone();

        assertEquals(0, dataProvider.getUploadedLength());
        assertEquals(0, dataProvider.getNumReadCalls());
        assertEquals(0, dataProvider.getNumRewindCalls());

        assertEquals(200, callback.mResponseInfo.getHttpStatusCode());
        assertEquals("", callback.mResponseAsString);
        dataProvider.assertClosed();
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testUploadSync() throws Exception {
        TestUrlRequestCallback callback = new TestUrlRequestCallback();
        UrlRequest.Builder builder = new UrlRequest.Builder(NativeTestServer.getEchoBodyURL(),
                callback, callback.getExecutor(), mTestFramework.mCronetEngine);

        TestUploadDataProvider dataProvider = new TestUploadDataProvider(
                TestUploadDataProvider.SuccessCallbackMode.SYNC, callback.getExecutor());
        dataProvider.addRead("test".getBytes());
        builder.setUploadDataProvider(dataProvider, callback.getExecutor());
        builder.addHeader("Content-Type", "useless/string");
        builder.build().start();
        callback.blockForDone();
        dataProvider.assertClosed();

        assertEquals(4, dataProvider.getUploadedLength());
        assertEquals(1, dataProvider.getNumReadCalls());
        assertEquals(0, dataProvider.getNumRewindCalls());

        assertEquals(200, callback.mResponseInfo.getHttpStatusCode());
        assertEquals("test", callback.mResponseAsString);
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testUploadMultiplePiecesSync() throws Exception {
        TestUrlRequestCallback callback = new TestUrlRequestCallback();
        UrlRequest.Builder builder = new UrlRequest.Builder(NativeTestServer.getEchoBodyURL(),
                callback, callback.getExecutor(), mTestFramework.mCronetEngine);

        TestUploadDataProvider dataProvider = new TestUploadDataProvider(
                TestUploadDataProvider.SuccessCallbackMode.SYNC, callback.getExecutor());
        dataProvider.addRead("Y".getBytes());
        dataProvider.addRead("et ".getBytes());
        dataProvider.addRead("another ".getBytes());
        dataProvider.addRead("test".getBytes());

        builder.setUploadDataProvider(dataProvider, callback.getExecutor());
        builder.addHeader("Content-Type", "useless/string");
        builder.build().start();
        callback.blockForDone();
        dataProvider.assertClosed();

        assertEquals(16, dataProvider.getUploadedLength());
        assertEquals(4, dataProvider.getNumReadCalls());
        assertEquals(0, dataProvider.getNumRewindCalls());

        assertEquals(200, callback.mResponseInfo.getHttpStatusCode());
        assertEquals("Yet another test", callback.mResponseAsString);
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testUploadMultiplePiecesAsync() throws Exception {
        TestUrlRequestCallback callback = new TestUrlRequestCallback();
        UrlRequest.Builder builder = new UrlRequest.Builder(NativeTestServer.getEchoBodyURL(),
                callback, callback.getExecutor(), mTestFramework.mCronetEngine);

        TestUploadDataProvider dataProvider = new TestUploadDataProvider(
                TestUploadDataProvider.SuccessCallbackMode.ASYNC, callback.getExecutor());
        dataProvider.addRead("Y".getBytes());
        dataProvider.addRead("et ".getBytes());
        dataProvider.addRead("another ".getBytes());
        dataProvider.addRead("test".getBytes());

        builder.setUploadDataProvider(dataProvider, callback.getExecutor());
        builder.addHeader("Content-Type", "useless/string");
        builder.build().start();
        callback.blockForDone();
        dataProvider.assertClosed();

        assertEquals(16, dataProvider.getUploadedLength());
        assertEquals(4, dataProvider.getNumReadCalls());
        assertEquals(0, dataProvider.getNumRewindCalls());

        assertEquals(200, callback.mResponseInfo.getHttpStatusCode());
        assertEquals("Yet another test", callback.mResponseAsString);
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testUploadChangesDefaultMethod() throws Exception {
        TestUrlRequestCallback callback = new TestUrlRequestCallback();
        UrlRequest.Builder builder = new UrlRequest.Builder(NativeTestServer.getEchoMethodURL(),
                callback, callback.getExecutor(), mTestFramework.mCronetEngine);

        TestUploadDataProvider dataProvider = new TestUploadDataProvider(
                TestUploadDataProvider.SuccessCallbackMode.SYNC, callback.getExecutor());
        dataProvider.addRead("test".getBytes());
        builder.setUploadDataProvider(dataProvider, callback.getExecutor());
        builder.addHeader("Content-Type", "useless/string");
        builder.build().start();
        callback.blockForDone();
        dataProvider.assertClosed();

        assertEquals(200, callback.mResponseInfo.getHttpStatusCode());
        assertEquals("POST", callback.mResponseAsString);
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testUploadWithSetMethod() throws Exception {
        TestUrlRequestCallback callback = new TestUrlRequestCallback();
        UrlRequest.Builder builder = new UrlRequest.Builder(NativeTestServer.getEchoMethodURL(),
                callback, callback.getExecutor(), mTestFramework.mCronetEngine);

        final String method = "PUT";
        builder.setHttpMethod(method);

        TestUploadDataProvider dataProvider = new TestUploadDataProvider(
                TestUploadDataProvider.SuccessCallbackMode.SYNC, callback.getExecutor());
        dataProvider.addRead("test".getBytes());
        builder.setUploadDataProvider(dataProvider, callback.getExecutor());
        builder.addHeader("Content-Type", "useless/string");
        builder.build().start();
        callback.blockForDone();
        dataProvider.assertClosed();

        assertEquals(200, callback.mResponseInfo.getHttpStatusCode());
        assertEquals("PUT", callback.mResponseAsString);
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testUploadRedirectSync() throws Exception {
        TestUrlRequestCallback callback = new TestUrlRequestCallback();
        UrlRequest.Builder builder =
                new UrlRequest.Builder(NativeTestServer.getRedirectToEchoBody(), callback,
                        callback.getExecutor(), mTestFramework.mCronetEngine);

        TestUploadDataProvider dataProvider = new TestUploadDataProvider(
                TestUploadDataProvider.SuccessCallbackMode.SYNC, callback.getExecutor());
        dataProvider.addRead("test".getBytes());
        builder.setUploadDataProvider(dataProvider, callback.getExecutor());
        builder.addHeader("Content-Type", "useless/string");
        builder.build().start();
        callback.blockForDone();
        dataProvider.assertClosed();

        // 1 read call before the rewind, 1 after.
        assertEquals(2, dataProvider.getNumReadCalls());
        assertEquals(1, dataProvider.getNumRewindCalls());

        assertEquals(200, callback.mResponseInfo.getHttpStatusCode());
        assertEquals("test", callback.mResponseAsString);
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testUploadRedirectAsync() throws Exception {
        TestUrlRequestCallback callback = new TestUrlRequestCallback();
        UrlRequest.Builder builder =
                new UrlRequest.Builder(NativeTestServer.getRedirectToEchoBody(), callback,
                        callback.getExecutor(), mTestFramework.mCronetEngine);

        TestUploadDataProvider dataProvider = new TestUploadDataProvider(
                TestUploadDataProvider.SuccessCallbackMode.ASYNC, callback.getExecutor());
        dataProvider.addRead("test".getBytes());
        builder.setUploadDataProvider(dataProvider, callback.getExecutor());
        builder.addHeader("Content-Type", "useless/string");
        builder.build().start();
        dataProvider.assertClosed();
        callback.blockForDone();

        // 1 read call before the rewind, 1 after.
        assertEquals(2, dataProvider.getNumReadCalls());
        assertEquals(1, dataProvider.getNumRewindCalls());

        assertEquals(200, callback.mResponseInfo.getHttpStatusCode());
        assertEquals("test", callback.mResponseAsString);
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testUploadWithBadLength() throws Exception {
        TestUrlRequestCallback callback = new TestUrlRequestCallback();
        UrlRequest.Builder builder = new UrlRequest.Builder(NativeTestServer.getEchoBodyURL(),
                callback, callback.getExecutor(), mTestFramework.mCronetEngine);

        TestUploadDataProvider dataProvider = new TestUploadDataProvider(
                TestUploadDataProvider.SuccessCallbackMode.SYNC, callback.getExecutor()) {
            @Override
            public long getLength() throws IOException {
                return 1;
            }

            @Override
            public void read(UploadDataSink uploadDataSink, ByteBuffer byteBuffer)
                    throws IOException {
                byteBuffer.put("12".getBytes());
                uploadDataSink.onReadSucceeded(false);
            }
        };
        builder.setUploadDataProvider(dataProvider, callback.getExecutor());
        builder.addHeader("Content-Type", "useless/string");
        builder.build().start();
        callback.blockForDone();
        dataProvider.assertClosed();

        assertEquals("Exception received from UploadDataProvider", callback.mError.getMessage());
        assertEquals("Read upload data length 2 exceeds expected length 1",
                callback.mError.getCause().getMessage());
        assertEquals(null, callback.mResponseInfo);
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testUploadWithBadLengthBufferAligned() throws Exception {
        TestUrlRequestCallback callback = new TestUrlRequestCallback();
        UrlRequest.Builder builder = new UrlRequest.Builder(NativeTestServer.getEchoBodyURL(),
                callback, callback.getExecutor(), mTestFramework.mCronetEngine);

        TestUploadDataProvider dataProvider = new TestUploadDataProvider(
                TestUploadDataProvider.SuccessCallbackMode.SYNC, callback.getExecutor()) {
            @Override
            public long getLength() throws IOException {
                return 8191;
            }

            @Override
            public void read(UploadDataSink uploadDataSink, ByteBuffer byteBuffer)
                    throws IOException {
                byteBuffer.put("0123456789abcdef".getBytes());
                uploadDataSink.onReadSucceeded(false);
            }
        };
        dataProvider.addRead("test".getBytes());
        builder.setUploadDataProvider(dataProvider, callback.getExecutor());
        builder.addHeader("Content-Type", "useless/string");
        builder.build().start();
        callback.blockForDone();
        dataProvider.assertClosed();
        assertEquals("Exception received from UploadDataProvider", callback.mError.getMessage());
        assertEquals("Read upload data length 8192 exceeds expected length 8191",
                callback.mError.getCause().getMessage());
        assertEquals(null, callback.mResponseInfo);
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testUploadReadFailSync() throws Exception {
        TestUrlRequestCallback callback = new TestUrlRequestCallback();
        UrlRequest.Builder builder = new UrlRequest.Builder(NativeTestServer.getEchoBodyURL(),
                callback, callback.getExecutor(), mTestFramework.mCronetEngine);

        TestUploadDataProvider dataProvider = new TestUploadDataProvider(
                TestUploadDataProvider.SuccessCallbackMode.SYNC, callback.getExecutor());
        dataProvider.setReadFailure(0, TestUploadDataProvider.FailMode.CALLBACK_SYNC);
        // This will never be read, but if the length is 0, read may never be
        // called.
        dataProvider.addRead("test".getBytes());
        builder.setUploadDataProvider(dataProvider, callback.getExecutor());
        builder.addHeader("Content-Type", "useless/string");
        builder.build().start();
        callback.blockForDone();
        dataProvider.assertClosed();

        assertEquals(1, dataProvider.getNumReadCalls());
        assertEquals(0, dataProvider.getNumRewindCalls());

        assertEquals("Exception received from UploadDataProvider", callback.mError.getMessage());
        assertEquals("Sync read failure", callback.mError.getCause().getMessage());
        assertEquals(null, callback.mResponseInfo);
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testUploadLengthFailSync() throws Exception {
        TestUrlRequestCallback callback = new TestUrlRequestCallback();
        UrlRequest.Builder builder = new UrlRequest.Builder(NativeTestServer.getEchoBodyURL(),
                callback, callback.getExecutor(), mTestFramework.mCronetEngine);

        TestUploadDataProvider dataProvider = new TestUploadDataProvider(
                TestUploadDataProvider.SuccessCallbackMode.SYNC, callback.getExecutor());
        dataProvider.setLengthFailure();
        // This will never be read, but if the length is 0, read may never be
        // called.
        dataProvider.addRead("test".getBytes());
        builder.setUploadDataProvider(dataProvider, callback.getExecutor());
        builder.addHeader("Content-Type", "useless/string");
        builder.build().start();
        callback.blockForDone();
        dataProvider.assertClosed();

        assertEquals(0, dataProvider.getNumReadCalls());
        assertEquals(0, dataProvider.getNumRewindCalls());

        assertEquals("Exception received from UploadDataProvider", callback.mError.getMessage());
        assertEquals("Sync length failure", callback.mError.getCause().getMessage());
        assertEquals(null, callback.mResponseInfo);
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testUploadReadFailAsync() throws Exception {
        TestUrlRequestCallback callback = new TestUrlRequestCallback();
        UrlRequest.Builder builder = new UrlRequest.Builder(NativeTestServer.getEchoBodyURL(),
                callback, callback.getExecutor(), mTestFramework.mCronetEngine);

        TestUploadDataProvider dataProvider = new TestUploadDataProvider(
                TestUploadDataProvider.SuccessCallbackMode.SYNC, callback.getExecutor());
        dataProvider.setReadFailure(0, TestUploadDataProvider.FailMode.CALLBACK_ASYNC);
        // This will never be read, but if the length is 0, read may never be
        // called.
        dataProvider.addRead("test".getBytes());
        builder.setUploadDataProvider(dataProvider, callback.getExecutor());
        builder.addHeader("Content-Type", "useless/string");
        builder.build().start();
        callback.blockForDone();
        dataProvider.assertClosed();

        assertEquals(1, dataProvider.getNumReadCalls());
        assertEquals(0, dataProvider.getNumRewindCalls());

        assertEquals("Exception received from UploadDataProvider", callback.mError.getMessage());
        assertEquals("Async read failure", callback.mError.getCause().getMessage());
        assertEquals(null, callback.mResponseInfo);
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testUploadReadFailThrown() throws Exception {
        TestUrlRequestCallback callback = new TestUrlRequestCallback();
        UrlRequest.Builder builder = new UrlRequest.Builder(NativeTestServer.getEchoBodyURL(),
                callback, callback.getExecutor(), mTestFramework.mCronetEngine);

        TestUploadDataProvider dataProvider = new TestUploadDataProvider(
                TestUploadDataProvider.SuccessCallbackMode.SYNC, callback.getExecutor());
        dataProvider.setReadFailure(0, TestUploadDataProvider.FailMode.THROWN);
        // This will never be read, but if the length is 0, read may never be
        // called.
        dataProvider.addRead("test".getBytes());
        builder.setUploadDataProvider(dataProvider, callback.getExecutor());
        builder.addHeader("Content-Type", "useless/string");
        builder.build().start();
        callback.blockForDone();
        dataProvider.assertClosed();

        assertEquals(1, dataProvider.getNumReadCalls());
        assertEquals(0, dataProvider.getNumRewindCalls());

        assertEquals("Exception received from UploadDataProvider", callback.mError.getMessage());
        assertEquals("Thrown read failure", callback.mError.getCause().getMessage());
        assertEquals(null, callback.mResponseInfo);
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testUploadRewindFailSync() throws Exception {
        TestUrlRequestCallback callback = new TestUrlRequestCallback();
        UrlRequest.Builder builder =
                new UrlRequest.Builder(NativeTestServer.getRedirectToEchoBody(), callback,
                        callback.getExecutor(), mTestFramework.mCronetEngine);

        TestUploadDataProvider dataProvider = new TestUploadDataProvider(
                TestUploadDataProvider.SuccessCallbackMode.SYNC, callback.getExecutor());
        dataProvider.setRewindFailure(TestUploadDataProvider.FailMode.CALLBACK_SYNC);
        dataProvider.addRead("test".getBytes());
        builder.setUploadDataProvider(dataProvider, callback.getExecutor());
        builder.addHeader("Content-Type", "useless/string");
        builder.build().start();
        callback.blockForDone();
        dataProvider.assertClosed();

        assertEquals(1, dataProvider.getNumReadCalls());
        assertEquals(1, dataProvider.getNumRewindCalls());

        assertEquals("Exception received from UploadDataProvider", callback.mError.getMessage());
        assertEquals("Sync rewind failure", callback.mError.getCause().getMessage());
        assertEquals(null, callback.mResponseInfo);
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testUploadRewindFailAsync() throws Exception {
        TestUrlRequestCallback callback = new TestUrlRequestCallback();
        UrlRequest.Builder builder =
                new UrlRequest.Builder(NativeTestServer.getRedirectToEchoBody(), callback,
                        callback.getExecutor(), mTestFramework.mCronetEngine);

        TestUploadDataProvider dataProvider = new TestUploadDataProvider(
                TestUploadDataProvider.SuccessCallbackMode.ASYNC, callback.getExecutor());
        dataProvider.setRewindFailure(TestUploadDataProvider.FailMode.CALLBACK_ASYNC);
        dataProvider.addRead("test".getBytes());
        builder.setUploadDataProvider(dataProvider, callback.getExecutor());
        builder.addHeader("Content-Type", "useless/string");
        builder.build().start();
        callback.blockForDone();
        dataProvider.assertClosed();

        assertEquals(1, dataProvider.getNumReadCalls());
        assertEquals(1, dataProvider.getNumRewindCalls());

        assertEquals("Exception received from UploadDataProvider", callback.mError.getMessage());
        assertEquals("Async rewind failure", callback.mError.getCause().getMessage());
        assertEquals(null, callback.mResponseInfo);
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testUploadRewindFailThrown() throws Exception {
        TestUrlRequestCallback callback = new TestUrlRequestCallback();
        UrlRequest.Builder builder =
                new UrlRequest.Builder(NativeTestServer.getRedirectToEchoBody(), callback,
                        callback.getExecutor(), mTestFramework.mCronetEngine);

        TestUploadDataProvider dataProvider = new TestUploadDataProvider(
                TestUploadDataProvider.SuccessCallbackMode.SYNC, callback.getExecutor());
        dataProvider.setRewindFailure(TestUploadDataProvider.FailMode.THROWN);
        dataProvider.addRead("test".getBytes());
        builder.setUploadDataProvider(dataProvider, callback.getExecutor());
        builder.addHeader("Content-Type", "useless/string");
        builder.build().start();
        callback.blockForDone();
        dataProvider.assertClosed();

        assertEquals(1, dataProvider.getNumReadCalls());
        assertEquals(1, dataProvider.getNumRewindCalls());

        assertEquals("Exception received from UploadDataProvider", callback.mError.getMessage());
        assertEquals("Thrown rewind failure", callback.mError.getCause().getMessage());
        assertEquals(null, callback.mResponseInfo);
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testUploadChunked() throws Exception {
        TestUrlRequestCallback callback = new TestUrlRequestCallback();
        UrlRequest.Builder builder = new UrlRequest.Builder(NativeTestServer.getEchoBodyURL(),
                callback, callback.getExecutor(), mTestFramework.mCronetEngine);

        TestUploadDataProvider dataProvider = new TestUploadDataProvider(
                TestUploadDataProvider.SuccessCallbackMode.SYNC, callback.getExecutor());
        dataProvider.addRead("test hello".getBytes());
        dataProvider.setChunked(true);
        builder.setUploadDataProvider(dataProvider, callback.getExecutor());
        builder.addHeader("Content-Type", "useless/string");

        assertEquals(-1, dataProvider.getUploadedLength());

        builder.build().start();
        callback.blockForDone();
        dataProvider.assertClosed();

        // 1 read call for one data chunk.
        assertEquals(1, dataProvider.getNumReadCalls());
        assertEquals("test hello", callback.mResponseAsString);
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testUploadChunkedLastReadZeroLengthBody() throws Exception {
        TestUrlRequestCallback callback = new TestUrlRequestCallback();
        UrlRequest.Builder builder = new UrlRequest.Builder(NativeTestServer.getEchoBodyURL(),
                callback, callback.getExecutor(), mTestFramework.mCronetEngine);

        TestUploadDataProvider dataProvider = new TestUploadDataProvider(
                TestUploadDataProvider.SuccessCallbackMode.SYNC, callback.getExecutor());
        // Add 3 reads. The last read has a 0-length body.
        dataProvider.addRead("hello there".getBytes());
        dataProvider.addRead("!".getBytes());
        dataProvider.addRead("".getBytes());
        dataProvider.setChunked(true);
        builder.setUploadDataProvider(dataProvider, callback.getExecutor());
        builder.addHeader("Content-Type", "useless/string");

        assertEquals(-1, dataProvider.getUploadedLength());

        builder.build().start();
        callback.blockForDone();
        dataProvider.assertClosed();

        // 2 read call for the first two data chunks, and 1 for final chunk.
        assertEquals(3, dataProvider.getNumReadCalls());
        assertEquals("hello there!", callback.mResponseAsString);
    }

    // Test where an upload fails without ever initializing the
    // UploadDataStream, because it can't connect to the server.
    @SmallTest
    @Feature({"Cronet"})
    public void testUploadFailsWithoutInitializingStream() throws Exception {
        TestUrlRequestCallback callback = new TestUrlRequestCallback();
        // The port for PTP will always refuse a TCP connection
        UrlRequest.Builder builder = new UrlRequest.Builder("http://127.0.0.1:319", callback,
                callback.getExecutor(), mTestFramework.mCronetEngine);

        TestUploadDataProvider dataProvider = new TestUploadDataProvider(
                TestUploadDataProvider.SuccessCallbackMode.SYNC, callback.getExecutor());
        dataProvider.addRead("test".getBytes());
        builder.setUploadDataProvider(dataProvider, callback.getExecutor());
        builder.addHeader("Content-Type", "useless/string");
        builder.build().start();
        callback.blockForDone();
        dataProvider.assertClosed();

        assertNull(callback.mResponseInfo);
        if (testingJavaImpl()) {
            Throwable cause = callback.mError.getCause();
            assertTrue("Exception was: " + cause, cause instanceof ConnectException);
        } else {
            assertEquals("Exception in CronetUrlRequest: net::ERR_CONNECTION_REFUSED",
                    callback.mError.getMessage());
        }
    }

    private void throwOrCancel(FailureType failureType, ResponseStep failureStep,
            boolean expectResponseInfo, boolean expectError) {
        if (Log.isLoggable("TESTING", Log.VERBOSE)) {
            Log.v("TESTING", "Testing " + failureType + " during " + failureStep);
        }
        TestUrlRequestCallback callback = new TestUrlRequestCallback();
        callback.setFailure(failureType, failureStep);
        UrlRequest.Builder builder = new UrlRequest.Builder(NativeTestServer.getRedirectURL(),
                callback, callback.getExecutor(), mTestFramework.mCronetEngine);
        UrlRequest urlRequest = builder.build();
        urlRequest.start();
        callback.blockForDone();
        assertEquals(1, callback.mRedirectCount);
        assertEquals(callback.mResponseStep, failureStep);
        assertTrue(urlRequest.isDone());
        assertEquals(expectResponseInfo, callback.mResponseInfo != null);
        assertEquals(expectError, callback.mError != null);
        assertEquals(expectError, callback.mOnErrorCalled);
        assertEquals(failureType == FailureType.CANCEL_SYNC
                        || failureType == FailureType.CANCEL_ASYNC
                        || failureType == FailureType.CANCEL_ASYNC_WITHOUT_PAUSE,
                callback.mOnCanceledCalled);
    }

    /*
    @SmallTest
    @Feature({"Cronet"})
    */
    @FlakyTest(message = "https://crbug.com/592444")
    public void testFailures() throws Exception {
        throwOrCancel(FailureType.CANCEL_SYNC, ResponseStep.ON_RECEIVED_REDIRECT,
                false, false);
        throwOrCancel(FailureType.CANCEL_ASYNC, ResponseStep.ON_RECEIVED_REDIRECT,
                false, false);
        throwOrCancel(FailureType.CANCEL_ASYNC_WITHOUT_PAUSE, ResponseStep.ON_RECEIVED_REDIRECT,
                false, false);
        throwOrCancel(FailureType.THROW_SYNC, ResponseStep.ON_RECEIVED_REDIRECT,
                false, true);

        throwOrCancel(FailureType.CANCEL_SYNC, ResponseStep.ON_RESPONSE_STARTED,
                true, false);
        throwOrCancel(FailureType.CANCEL_ASYNC, ResponseStep.ON_RESPONSE_STARTED,
                true, false);
        throwOrCancel(FailureType.CANCEL_ASYNC_WITHOUT_PAUSE, ResponseStep.ON_RESPONSE_STARTED,
                true, false);
        throwOrCancel(FailureType.THROW_SYNC, ResponseStep.ON_RESPONSE_STARTED,
                true, true);

        throwOrCancel(FailureType.CANCEL_SYNC, ResponseStep.ON_READ_COMPLETED,
                true, false);
        throwOrCancel(FailureType.CANCEL_ASYNC, ResponseStep.ON_READ_COMPLETED,
                true, false);
        throwOrCancel(FailureType.CANCEL_ASYNC_WITHOUT_PAUSE, ResponseStep.ON_READ_COMPLETED,
                true, false);
        throwOrCancel(FailureType.THROW_SYNC, ResponseStep.ON_READ_COMPLETED,
                true, true);
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testThrowON_SUCCEEDED() {
        TestUrlRequestCallback callback = new TestUrlRequestCallback();
        callback.setFailure(FailureType.THROW_SYNC, ResponseStep.ON_SUCCEEDED);
        UrlRequest.Builder builder = new UrlRequest.Builder(NativeTestServer.getRedirectURL(),
                callback, callback.getExecutor(), mTestFramework.mCronetEngine);
        UrlRequest urlRequest = builder.build();
        urlRequest.start();
        callback.blockForDone();
        assertEquals(1, callback.mRedirectCount);
        assertEquals(callback.mResponseStep, ResponseStep.ON_SUCCEEDED);
        assertTrue(urlRequest.isDone());
        assertNotNull(callback.mResponseInfo);
        assertNull(callback.mError);
        assertFalse(callback.mOnErrorCalled);
    }

    @SmallTest
    @Feature({"Cronet"})
    @OnlyRunNativeCronet // No destroyed callback for tests
    public void testExecutorShutdown() {
        TestUrlRequestCallback callback = new TestUrlRequestCallback();

        callback.setAutoAdvance(false);
        UrlRequest.Builder builder = new UrlRequest.Builder(NativeTestServer.getEchoBodyURL(),
                callback, callback.getExecutor(), mTestFramework.mCronetEngine);
        CronetUrlRequest urlRequest = (CronetUrlRequest) builder.build();
        urlRequest.start();
        callback.waitForNextStep();
        assertFalse(callback.isDone());
        assertFalse(urlRequest.isDone());

        final ConditionVariable requestDestroyed = new ConditionVariable(false);
        urlRequest.setOnDestroyedCallbackForTesting(new Runnable() {
            @Override
            public void run() {
                requestDestroyed.open();
            }
        });

        // Shutdown the executor, so posting the task will throw an exception.
        callback.shutdownExecutor();
        ByteBuffer readBuffer = ByteBuffer.allocateDirect(5);
        urlRequest.read(readBuffer);
        // Callback will never be called again because executor is shutdown,
        // but request will be destroyed from network thread.
        requestDestroyed.block();

        assertFalse(callback.isDone());
        assertTrue(urlRequest.isDone());
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testUploadExecutorShutdown() throws Exception {
        class HangingUploadDataProvider extends UploadDataProvider {
            UploadDataSink mUploadDataSink;
            ByteBuffer mByteBuffer;
            ConditionVariable mReadCalled = new ConditionVariable(false);

            @Override
            public long getLength() {
                return 69;
            }

            @Override
            public void read(final UploadDataSink uploadDataSink,
                             final ByteBuffer byteBuffer) {
                mUploadDataSink = uploadDataSink;
                mByteBuffer = byteBuffer;
                mReadCalled.open();
            }

            @Override
            public void rewind(final UploadDataSink uploadDataSink) {
            }
        }

        TestUrlRequestCallback callback = new TestUrlRequestCallback();
        UrlRequest.Builder builder = new UrlRequest.Builder(NativeTestServer.getEchoBodyURL(),
                callback, callback.getExecutor(), mTestFramework.mCronetEngine);

        ExecutorService uploadExecutor = Executors.newSingleThreadExecutor();
        HangingUploadDataProvider dataProvider = new HangingUploadDataProvider();
        builder.setUploadDataProvider(dataProvider, uploadExecutor);
        builder.addHeader("Content-Type", "useless/string");
        UrlRequest urlRequest = builder.build();
        urlRequest.start();
        // Wait for read to be called on executor.
        dataProvider.mReadCalled.block();
        // Shutdown the executor, so posting next task will throw an exception.
        uploadExecutor.shutdown();
        // Continue the upload.
        dataProvider.mByteBuffer.putInt(42);
        dataProvider.mUploadDataSink.onReadSucceeded(false);
        // Callback.onFailed will be called on request executor even though upload
        // executor is shutdown.
        callback.blockForDone();
        assertTrue(callback.isDone());
        assertTrue(callback.mOnErrorCalled);
        assertEquals("Exception received from UploadDataProvider", callback.mError.getMessage());
        assertTrue(urlRequest.isDone());
    }

    /**
     * A TestUrlRequestCallback that shuts down executor upon receiving onSucceeded callback.
     */
    private static class QuitOnSuccessCallback extends TestUrlRequestCallback {
        @Override
        public void onSucceeded(UrlRequest request, UrlResponseInfo info) {
            // Stop accepting new tasks.
            shutdownExecutor();
            super.onSucceeded(request, info);
        }
    }

    @SmallTest
    @Feature({"Cronet"})
    @OnlyRunNativeCronet // No adapter to destroy in pure java
    // Regression test for crbug.com/564946.
    public void testDestroyUploadDataStreamAdapterOnSucceededCallback() throws Exception {
        TestUrlRequestCallback callback = new QuitOnSuccessCallback();
        UrlRequest.Builder builder = new UrlRequest.Builder(NativeTestServer.getEchoBodyURL(),
                callback, callback.getExecutor(), mTestFramework.mCronetEngine);

        TestUploadDataProvider dataProvider = new TestUploadDataProvider(
                TestUploadDataProvider.SuccessCallbackMode.SYNC, callback.getExecutor());
        builder.setUploadDataProvider(dataProvider, callback.getExecutor());
        builder.addHeader("Content-Type", "useless/string");
        CronetUrlRequest request = (CronetUrlRequest) builder.build();
        final ConditionVariable uploadDataStreamAdapterDestroyed = new ConditionVariable();
        request.setOnDestroyedUploadCallbackForTesting(new Runnable() {
            @Override
            public void run() {
                uploadDataStreamAdapterDestroyed.open();
            }
        });

        request.start();
        uploadDataStreamAdapterDestroyed.block();

        assertEquals(200, callback.mResponseInfo.getHttpStatusCode());
        assertEquals("", callback.mResponseAsString);
    }

    /*
     * Verifies error codes are passed through correctly.
     */
    @SmallTest
    @Feature({"Cronet"})
    @OnlyRunNativeCronet // Java impl doesn't support MockUrlRequestJobFactory
    public void testErrorCodes() throws Exception {
        checkSpecificErrorCode(
                -105, UrlRequestException.ERROR_HOSTNAME_NOT_RESOLVED, "NAME_NOT_RESOLVED", false);
        checkSpecificErrorCode(-106, UrlRequestException.ERROR_INTERNET_DISCONNECTED,
                "INTERNET_DISCONNECTED", false);
        checkSpecificErrorCode(
                -21, UrlRequestException.ERROR_NETWORK_CHANGED, "NETWORK_CHANGED", true);
        checkSpecificErrorCode(
                -100, UrlRequestException.ERROR_CONNECTION_CLOSED, "CONNECTION_CLOSED", true);
        checkSpecificErrorCode(
                -102, UrlRequestException.ERROR_CONNECTION_REFUSED, "CONNECTION_REFUSED", false);
        checkSpecificErrorCode(
                -101, UrlRequestException.ERROR_CONNECTION_RESET, "CONNECTION_RESET", true);
        checkSpecificErrorCode(
                -118, UrlRequestException.ERROR_CONNECTION_TIMED_OUT, "CONNECTION_TIMED_OUT", true);
        checkSpecificErrorCode(-7, UrlRequestException.ERROR_TIMED_OUT, "TIMED_OUT", true);
        checkSpecificErrorCode(
                -109, UrlRequestException.ERROR_ADDRESS_UNREACHABLE, "ADDRESS_UNREACHABLE", false);
        checkSpecificErrorCode(-2, UrlRequestException.ERROR_OTHER, "FAILED", false);
    }

    /*
     * Verifies no cookies are saved or sent by default.
     */
    @SmallTest
    @Feature({"Cronet"})
    public void testCookiesArentSavedOrSent() throws Exception {
        // Make a request to a url that sets the cookie
        String url = NativeTestServer.getFileURL("/set_cookie.html");
        TestUrlRequestCallback callback = startAndWaitForComplete(url);
        assertEquals(200, callback.mResponseInfo.getHttpStatusCode());
        assertEquals("A=B", callback.mResponseInfo.getAllHeaders().get("Set-Cookie").get(0));

        // Make a request that check that cookie header isn't sent.
        String headerName = "Cookie";
        String url2 = NativeTestServer.getEchoHeaderURL(headerName);
        TestUrlRequestCallback callback2 = startAndWaitForComplete(url2);
        assertEquals(200, callback2.mResponseInfo.getHttpStatusCode());
        assertEquals("Header not found. :(", callback2.mResponseAsString);
    }

    @SmallTest
    @Feature({"Cronet"})
    @OnlyRunNativeCronet
    public void testQuicErrorCode() throws Exception {
        TestUrlRequestCallback callback =
                startAndWaitForComplete(MockUrlRequestJobFactory.getMockUrlWithFailure(
                        FailurePhase.START, NetError.ERR_QUIC_PROTOCOL_ERROR));
        assertNull(callback.mResponseInfo);
        assertNotNull(callback.mError);
        assertEquals(
                UrlRequestException.ERROR_QUIC_PROTOCOL_FAILED, callback.mError.getErrorCode());
        assertTrue(callback.mError instanceof QuicException);
        QuicException quicException = (QuicException) callback.mError;
        // 1 is QUIC_INTERNAL_ERROR
        assertEquals(1, quicException.getQuicDetailedErrorCode());
    }

    private void checkSpecificErrorCode(int netError, int errorCode, String name,
            boolean immediatelyRetryable) throws Exception {
        TestUrlRequestCallback callback = startAndWaitForComplete(
                MockUrlRequestJobFactory.getMockUrlWithFailure(FailurePhase.START, netError));
        assertNull(callback.mResponseInfo);
        assertNotNull(callback.mError);
        assertEquals(netError, callback.mError.getCronetInternalErrorCode());
        assertEquals(errorCode, callback.mError.getErrorCode());
        assertEquals(
                "Exception in CronetUrlRequest: net::ERR_" + name, callback.mError.getMessage());
        assertEquals(0, callback.mRedirectCount);
        assertTrue(callback.mOnErrorCalled);
        assertEquals(callback.mResponseStep, ResponseStep.NOTHING);
    }

    // Returns the contents of byteBuffer, from its position() to its limit(),
    // as a String. Does not modify byteBuffer's position().
    private String bufferContentsToString(ByteBuffer byteBuffer, int start, int end) {
        // Use a duplicate to avoid modifying byteBuffer.
        ByteBuffer duplicate = byteBuffer.duplicate();
        duplicate.position(start);
        duplicate.limit(end);
        byte[] contents = new byte[duplicate.remaining()];
        duplicate.get(contents);
        return new String(contents);
    }
}
