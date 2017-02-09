// Copyright 2015 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.net;

import android.os.ConditionVariable;
import android.test.suitebuilder.annotation.SmallTest;

import org.chromium.base.test.util.DisabledTest;
import org.chromium.base.test.util.Feature;
import org.chromium.net.CronetTestBase.OnlyRunNativeCronet;
import org.chromium.net.TestBidirectionalStreamCallback.FailureType;
import org.chromium.net.TestBidirectionalStreamCallback.ResponseStep;
import org.chromium.net.impl.CronetBidirectionalStream;

import java.nio.ByteBuffer;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Test functionality of BidirectionalStream interface.
 */
public class BidirectionalStreamTest extends CronetTestBase {
    private CronetTestFramework mTestFramework;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        // Load library first to create MockCertVerifier.
        System.loadLibrary("cronet_tests");
        CronetEngine.Builder builder = new CronetEngine.Builder(getContext());
        builder.setMockCertVerifierForTesting(QuicTestServer.createMockCertVerifier());

        mTestFramework = startCronetTestFrameworkWithUrlAndCronetEngineBuilder(null, builder);
        assertTrue(Http2TestServer.startHttp2TestServer(
                getContext(), QuicTestServer.getServerCert(), QuicTestServer.getServerCertKey()));
    }

    @Override
    protected void tearDown() throws Exception {
        assertTrue(Http2TestServer.shutdownHttp2TestServer());
        if (mTestFramework.mCronetEngine != null) {
            mTestFramework.mCronetEngine.shutdown();
        }
        super.tearDown();
    }

    private static void checkResponseInfo(UrlResponseInfo responseInfo, String expectedUrl,
            int expectedHttpStatusCode, String expectedHttpStatusText) {
        assertEquals(expectedUrl, responseInfo.getUrl());
        assertEquals(
                expectedUrl, responseInfo.getUrlChain().get(responseInfo.getUrlChain().size() - 1));
        assertEquals(expectedHttpStatusCode, responseInfo.getHttpStatusCode());
        assertEquals(expectedHttpStatusText, responseInfo.getHttpStatusText());
        assertFalse(responseInfo.wasCached());
        assertTrue(responseInfo.toString().length() > 0);
    }

    private static String createLongString(String base, int repetition) {
        StringBuilder builder = new StringBuilder(base.length() * repetition);
        for (int i = 0; i < repetition; ++i) {
            builder.append(i);
            builder.append(base);
        }
        return builder.toString();
    }

    private static UrlResponseInfo createUrlResponseInfo(
            String[] urls, String message, int statusCode, int receivedBytes, String... headers) {
        ArrayList<Map.Entry<String, String>> headersList = new ArrayList<>();
        for (int i = 0; i < headers.length; i += 2) {
            headersList.add(new AbstractMap.SimpleImmutableEntry<String, String>(
                    headers[i], headers[i + 1]));
        }
        UrlResponseInfo urlResponseInfo = new UrlResponseInfo(
                Arrays.asList(urls), statusCode, message, headersList, false, "h2", null);
        urlResponseInfo.setReceivedBytesCount(receivedBytes);
        return urlResponseInfo;
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testBuilderChecks() throws Exception {
        TestBidirectionalStreamCallback callback = new TestBidirectionalStreamCallback();
        try {
            new BidirectionalStream.Builder(
                    null, callback, callback.getExecutor(), mTestFramework.mCronetEngine);
            fail("URL not null-checked");
        } catch (NullPointerException e) {
            assertEquals("URL is required.", e.getMessage());
        }
        try {
            new BidirectionalStream.Builder(Http2TestServer.getServerUrl(), null,
                    callback.getExecutor(), mTestFramework.mCronetEngine);
            fail("Callback not null-checked");
        } catch (NullPointerException e) {
            assertEquals("Callback is required.", e.getMessage());
        }
        try {
            new BidirectionalStream.Builder(
                    Http2TestServer.getServerUrl(), callback, null, mTestFramework.mCronetEngine);
            fail("Executor not null-checked");
        } catch (NullPointerException e) {
            assertEquals("Executor is required.", e.getMessage());
        }
        try {
            new BidirectionalStream.Builder(
                    Http2TestServer.getServerUrl(), callback, callback.getExecutor(), null);
            fail("CronetEngine not null-checked");
        } catch (NullPointerException e) {
            assertEquals("CronetEngine is required.", e.getMessage());
        }
        // Verify successful creation doesn't throw.
        BidirectionalStream.Builder builder =
                new BidirectionalStream.Builder(Http2TestServer.getServerUrl(), callback,
                        callback.getExecutor(), mTestFramework.mCronetEngine);
        try {
            builder.addHeader(null, "value");
            fail("Header name is not null-checked");
        } catch (NullPointerException e) {
            assertEquals("Invalid header name.", e.getMessage());
        }
        try {
            builder.addHeader("name", null);
            fail("Header value is not null-checked");
        } catch (NullPointerException e) {
            assertEquals("Invalid header value.", e.getMessage());
        }
        try {
            builder.setHttpMethod(null);
            fail("Method name is not null-checked");
        } catch (NullPointerException e) {
            assertEquals("Method is required.", e.getMessage());
        }
    }

    @SmallTest
    @Feature({"Cronet"})
    @OnlyRunNativeCronet
    public void testFailPlainHttp() throws Exception {
        String url = "http://example.com";
        TestBidirectionalStreamCallback callback = new TestBidirectionalStreamCallback();
        // Create stream.
        BidirectionalStream stream = new BidirectionalStream
                                             .Builder(url, callback, callback.getExecutor(),
                                                     mTestFramework.mCronetEngine)
                                             .build();
        stream.start();
        callback.blockForDone();
        assertTrue(stream.isDone());
        assertEquals("Exception in BidirectionalStream: net::ERR_DISALLOWED_URL_SCHEME",
                callback.mError.getMessage());
        assertEquals(-301, callback.mError.getCronetInternalErrorCode());
    }

    @SmallTest
    @Feature({"Cronet"})
    @OnlyRunNativeCronet
    public void testSimpleGet() throws Exception {
        String url = Http2TestServer.getEchoMethodUrl();
        TestBidirectionalStreamCallback callback = new TestBidirectionalStreamCallback();
        // Create stream.
        BidirectionalStream stream = new BidirectionalStream
                                             .Builder(url, callback, callback.getExecutor(),
                                                     mTestFramework.mCronetEngine)
                                             .setHttpMethod("GET")
                                             .build();
        stream.start();
        callback.blockForDone();
        assertTrue(stream.isDone());
        assertEquals(200, callback.mResponseInfo.getHttpStatusCode());
        // Default method is 'GET'.
        assertEquals("GET", callback.mResponseAsString);
        UrlResponseInfo urlResponseInfo =
                createUrlResponseInfo(new String[] {url}, "", 200, 27, ":status", "200");
        assertResponseEquals(urlResponseInfo, callback.mResponseInfo);
        checkResponseInfo(callback.mResponseInfo, Http2TestServer.getEchoMethodUrl(), 200, "");
    }

    @SmallTest
    @Feature({"Cronet"})
    @OnlyRunNativeCronet
    public void testSimpleHead() throws Exception {
        String url = Http2TestServer.getEchoMethodUrl();
        TestBidirectionalStreamCallback callback = new TestBidirectionalStreamCallback();
        // Create stream.
        BidirectionalStream stream = new BidirectionalStream
                                             .Builder(url, callback, callback.getExecutor(),
                                                     mTestFramework.mCronetEngine)
                                             .setHttpMethod("HEAD")
                                             .build();
        stream.start();
        callback.blockForDone();
        assertTrue(stream.isDone());
        assertEquals(200, callback.mResponseInfo.getHttpStatusCode());
        assertEquals("HEAD", callback.mResponseAsString);
        UrlResponseInfo urlResponseInfo =
                createUrlResponseInfo(new String[] {url}, "", 200, 28, ":status", "200");
        assertResponseEquals(urlResponseInfo, callback.mResponseInfo);
        checkResponseInfo(callback.mResponseInfo, Http2TestServer.getEchoMethodUrl(), 200, "");
    }

    @SmallTest
    @Feature({"Cronet"})
    @OnlyRunNativeCronet
    public void testSimplePost() throws Exception {
        String url = Http2TestServer.getEchoStreamUrl();
        TestBidirectionalStreamCallback callback = new TestBidirectionalStreamCallback();
        callback.addWriteData("Test String".getBytes());
        callback.addWriteData("1234567890".getBytes());
        callback.addWriteData("woot!".getBytes());
        // Create stream.
        BidirectionalStream stream = new BidirectionalStream
                                             .Builder(url, callback, callback.getExecutor(),
                                                     mTestFramework.mCronetEngine)
                                             .addHeader("foo", "bar")
                                             .addHeader("empty", "")
                                             .addHeader("Content-Type", "zebra")
                                             .build();
        stream.start();
        callback.blockForDone();
        assertTrue(stream.isDone());
        assertEquals(200, callback.mResponseInfo.getHttpStatusCode());
        assertEquals("Test String1234567890woot!", callback.mResponseAsString);
        assertEquals("bar", callback.mResponseInfo.getAllHeaders().get("echo-foo").get(0));
        assertEquals("", callback.mResponseInfo.getAllHeaders().get("echo-empty").get(0));
        assertEquals(
                "zebra", callback.mResponseInfo.getAllHeaders().get("echo-content-type").get(0));
    }

    @SmallTest
    @Feature({"Cronet"})
    @OnlyRunNativeCronet
    public void testSimplePostWithFlush() throws Exception {
        String url = Http2TestServer.getEchoStreamUrl();
        TestBidirectionalStreamCallback callback = new TestBidirectionalStreamCallback();
        callback.addWriteData("Test String".getBytes(), false);
        callback.addWriteData("1234567890".getBytes(), false);
        callback.addWriteData("woot!".getBytes(), true);
        BidirectionalStream stream = new BidirectionalStream
                                             .Builder(url, callback, callback.getExecutor(),
                                                     mTestFramework.mCronetEngine)
                                             .disableAutoFlush(true)
                                             .addHeader("foo", "bar")
                                             .addHeader("empty", "")
                                             .addHeader("Content-Type", "zebra")
                                             .build();
        // Flush before stream is started should not crash.
        stream.flush();

        stream.start();
        callback.blockForDone();
        assertTrue(stream.isDone());

        // Flush after stream is completed is no-op. It shouldn't call into the destroyed adapter.
        stream.flush();

        assertEquals(200, callback.mResponseInfo.getHttpStatusCode());
        assertEquals("Test String1234567890woot!", callback.mResponseAsString);
        assertEquals("bar", callback.mResponseInfo.getAllHeaders().get("echo-foo").get(0));
        assertEquals("", callback.mResponseInfo.getAllHeaders().get("echo-empty").get(0));
        assertEquals(
                "zebra", callback.mResponseInfo.getAllHeaders().get("echo-content-type").get(0));
    }

    @SmallTest
    @Feature({"Cronet"})
    @OnlyRunNativeCronet
    // Tests that a delayed flush() only sends buffers that have been written
    // before it is called, and it doesn't flush buffers in mPendingQueue.
    public void testFlushData() throws Exception {
        String url = Http2TestServer.getEchoStreamUrl();
        TestBidirectionalStreamCallback callback = new TestBidirectionalStreamCallback() {
            // Number of onWriteCompleted callbacks that have been invoked.
            private int mNumWriteCompleted = 0;
            @Override
            public void onWriteCompleted(BidirectionalStream stream, UrlResponseInfo info,
                    ByteBuffer buffer, boolean endOfStream) {
                super.onWriteCompleted(stream, info, buffer, endOfStream);
                mNumWriteCompleted++;
                if (mNumWriteCompleted <= 3) {
                    // "6" is in pending queue.
                    List<ByteBuffer> pendingData =
                            ((CronetBidirectionalStream) stream).getPendingDataForTesting();
                    assertEquals(1, pendingData.size());
                    ByteBuffer pendingBuffer = pendingData.get(0);
                    byte[] content = new byte[pendingBuffer.remaining()];
                    pendingBuffer.get(content);
                    assertTrue(Arrays.equals("6".getBytes(), content));

                    // "4" and "5" have been flushed.
                    assertEquals(0,
                            ((CronetBidirectionalStream) stream).getFlushDataForTesting().size());
                } else if (mNumWriteCompleted == 5) {
                    // Now flush "6", which is still in pending queue.
                    List<ByteBuffer> pendingData =
                            ((CronetBidirectionalStream) stream).getPendingDataForTesting();
                    assertEquals(1, pendingData.size());
                    ByteBuffer pendingBuffer = pendingData.get(0);
                    byte[] content = new byte[pendingBuffer.remaining()];
                    pendingBuffer.get(content);
                    assertTrue(Arrays.equals("6".getBytes(), content));

                    stream.flush();

                    assertEquals(0,
                            ((CronetBidirectionalStream) stream).getPendingDataForTesting().size());
                    assertEquals(0,
                            ((CronetBidirectionalStream) stream).getFlushDataForTesting().size());
                }
            }
        };
        callback.addWriteData("1".getBytes(), false);
        callback.addWriteData("2".getBytes(), false);
        callback.addWriteData("3".getBytes(), true);
        callback.addWriteData("4".getBytes(), false);
        callback.addWriteData("5".getBytes(), true);
        callback.addWriteData("6".getBytes(), false);
        CronetBidirectionalStream stream = (CronetBidirectionalStream) new BidirectionalStream
                                                   .Builder(url, callback, callback.getExecutor(),
                                                           mTestFramework.mCronetEngine)
                                                   .disableAutoFlush(true)
                                                   .addHeader("foo", "bar")
                                                   .addHeader("empty", "")
                                                   .addHeader("Content-Type", "zebra")
                                                   .build();
        callback.setAutoAdvance(false);
        stream.start();
        callback.waitForNextWriteStep(); // onStreamReady

        assertEquals(0, stream.getPendingDataForTesting().size());
        assertEquals(0, stream.getFlushDataForTesting().size());

        // Write 1, 2, 3 and flush().
        callback.startNextWrite(stream);
        // Write 4, 5 and flush(). 4, 5 will be in flush queue.
        callback.startNextWrite(stream);
        // Write 6, but do not flush. 6 will be in pending queue.
        callback.startNextWrite(stream);

        callback.setAutoAdvance(true);
        callback.blockForDone();
        assertEquals(200, callback.mResponseInfo.getHttpStatusCode());
        assertEquals("123456", callback.mResponseAsString);
        assertEquals("bar", callback.mResponseInfo.getAllHeaders().get("echo-foo").get(0));
        assertEquals("", callback.mResponseInfo.getAllHeaders().get("echo-empty").get(0));
        assertEquals(
                "zebra", callback.mResponseInfo.getAllHeaders().get("echo-content-type").get(0));
    }

    @SmallTest
    @Feature({"Cronet"})
    @OnlyRunNativeCronet
    public void testSimpleGetWithFlush() throws Exception {
        // TODO(xunjieli): Use ParameterizedTest instead of the loop.
        for (int i = 0; i < 2; i++) {
            String url = Http2TestServer.getEchoStreamUrl();
            TestBidirectionalStreamCallback callback = new TestBidirectionalStreamCallback() {
                @Override
                public void onStreamReady(BidirectionalStream stream) {
                    try {
                        // Attempt to write data for GET request.
                        stream.write(ByteBuffer.wrap("dummy".getBytes()), true);
                    } catch (IllegalArgumentException e) {
                        // Expected.
                    }
                    // If there are delayed headers, this flush should try to send them.
                    // If nothing to flush, it should not crash.
                    stream.flush();
                    super.onStreamReady(stream);
                    try {
                        // Attempt to write data for GET request.
                        stream.write(ByteBuffer.wrap("dummy".getBytes()), true);
                    } catch (IllegalArgumentException e) {
                        // Expected.
                    }
                }
            };
            BidirectionalStream stream = new BidirectionalStream
                                                 .Builder(url, callback, callback.getExecutor(),
                                                         mTestFramework.mCronetEngine)
                                                 .setHttpMethod("GET")
                                                 .disableAutoFlush(true)
                                                 .delayRequestHeadersUntilFirstFlush(i == 0)
                                                 .addHeader("foo", "bar")
                                                 .addHeader("empty", "")
                                                 .build();
            // Flush before stream is started should not crash.
            stream.flush();

            stream.start();
            callback.blockForDone();
            assertTrue(stream.isDone());

            // Flush after stream is completed is no-op. It shouldn't call into the destroyed
            // adapter.
            stream.flush();

            assertEquals(200, callback.mResponseInfo.getHttpStatusCode());
            assertEquals("", callback.mResponseAsString);
            assertEquals("bar", callback.mResponseInfo.getAllHeaders().get("echo-foo").get(0));
            assertEquals("", callback.mResponseInfo.getAllHeaders().get("echo-empty").get(0));
        }
    }

    @SmallTest
    @Feature({"Cronet"})
    @OnlyRunNativeCronet
    public void testSimplePostWithFlushAfterOneWrite() throws Exception {
        // TODO(xunjieli): Use ParameterizedTest instead of the loop.
        for (int i = 0; i < 2; i++) {
            String url = Http2TestServer.getEchoStreamUrl();
            TestBidirectionalStreamCallback callback = new TestBidirectionalStreamCallback();
            callback.addWriteData("Test String".getBytes(), true);
            BidirectionalStream stream = new BidirectionalStream
                                                 .Builder(url, callback, callback.getExecutor(),
                                                         mTestFramework.mCronetEngine)
                                                 .disableAutoFlush(true)
                                                 .delayRequestHeadersUntilFirstFlush(i == 0)
                                                 .addHeader("foo", "bar")
                                                 .addHeader("empty", "")
                                                 .addHeader("Content-Type", "zebra")
                                                 .build();
            stream.start();
            callback.blockForDone();
            assertTrue(stream.isDone());

            assertEquals(200, callback.mResponseInfo.getHttpStatusCode());
            assertEquals("Test String", callback.mResponseAsString);
            assertEquals("bar", callback.mResponseInfo.getAllHeaders().get("echo-foo").get(0));
            assertEquals("", callback.mResponseInfo.getAllHeaders().get("echo-empty").get(0));
            assertEquals("zebra",
                    callback.mResponseInfo.getAllHeaders().get("echo-content-type").get(0));
        }
    }

    @SmallTest
    @Feature({"Cronet"})
    @OnlyRunNativeCronet
    public void testSimplePostWithFlushTwice() throws Exception {
        // TODO(xunjieli): Use ParameterizedTest instead of the loop.
        for (int i = 0; i < 2; i++) {
            String url = Http2TestServer.getEchoStreamUrl();
            TestBidirectionalStreamCallback callback = new TestBidirectionalStreamCallback();
            callback.addWriteData("Test String".getBytes(), false);
            callback.addWriteData("1234567890".getBytes(), false);
            callback.addWriteData("woot!".getBytes(), true);
            callback.addWriteData("Test String".getBytes(), false);
            callback.addWriteData("1234567890".getBytes(), false);
            callback.addWriteData("woot!".getBytes(), true);
            BidirectionalStream stream = new BidirectionalStream
                                                 .Builder(url, callback, callback.getExecutor(),
                                                         mTestFramework.mCronetEngine)
                                                 .disableAutoFlush(true)
                                                 .delayRequestHeadersUntilFirstFlush(i == 0)
                                                 .addHeader("foo", "bar")
                                                 .addHeader("empty", "")
                                                 .addHeader("Content-Type", "zebra")
                                                 .build();
            stream.start();
            callback.blockForDone();
            assertTrue(stream.isDone());
            assertEquals(200, callback.mResponseInfo.getHttpStatusCode());
            assertEquals("Test String1234567890woot!Test String1234567890woot!",
                    callback.mResponseAsString);
            assertEquals("bar", callback.mResponseInfo.getAllHeaders().get("echo-foo").get(0));
            assertEquals("", callback.mResponseInfo.getAllHeaders().get("echo-empty").get(0));
            assertEquals("zebra",
                    callback.mResponseInfo.getAllHeaders().get("echo-content-type").get(0));
        }
    }

    @SmallTest
    @Feature({"Cronet"})
    @OnlyRunNativeCronet
    // Tests that it is legal to call read() in onStreamReady().
    public void testReadDuringOnStreamReady() throws Exception {
        String url = Http2TestServer.getEchoStreamUrl();
        TestBidirectionalStreamCallback callback = new TestBidirectionalStreamCallback() {
            @Override
            public void onStreamReady(BidirectionalStream stream) {
                super.onStreamReady(stream);
                startNextRead(stream);
            }
            @Override
            public void onResponseHeadersReceived(
                    BidirectionalStream stream, UrlResponseInfo info) {
                // Do nothing. Skip readng.
            }
        };
        callback.addWriteData("Test String".getBytes());
        callback.addWriteData("1234567890".getBytes());
        callback.addWriteData("woot!".getBytes());
        BidirectionalStream stream = new BidirectionalStream
                                             .Builder(url, callback, callback.getExecutor(),
                                                     mTestFramework.mCronetEngine)
                                             .addHeader("foo", "bar")
                                             .addHeader("empty", "")
                                             .addHeader("Content-Type", "zebra")
                                             .build();
        stream.start();
        callback.blockForDone();
        assertTrue(stream.isDone());
        assertEquals(200, callback.mResponseInfo.getHttpStatusCode());
        assertEquals("Test String1234567890woot!", callback.mResponseAsString);
        assertEquals("bar", callback.mResponseInfo.getAllHeaders().get("echo-foo").get(0));
        assertEquals("", callback.mResponseInfo.getAllHeaders().get("echo-empty").get(0));
        assertEquals(
                "zebra", callback.mResponseInfo.getAllHeaders().get("echo-content-type").get(0));
    }

    @SmallTest
    @Feature({"Cronet"})
    @OnlyRunNativeCronet
    // Tests that it is legal to call flush() when previous nativeWritevData has
    // yet to complete.
    public void testSimplePostWithFlushBeforePreviousWriteCompleted() throws Exception {
        String url = Http2TestServer.getEchoStreamUrl();
        TestBidirectionalStreamCallback callback = new TestBidirectionalStreamCallback() {
            @Override
            public void onStreamReady(BidirectionalStream stream) {
                super.onStreamReady(stream);
                // Write a second time before the previous nativeWritevData has completed.
                startNextWrite(stream);
                assertEquals(0, numPendingWrites());
            }
        };
        callback.addWriteData("Test String".getBytes(), false);
        callback.addWriteData("1234567890".getBytes(), false);
        callback.addWriteData("woot!".getBytes(), true);
        callback.addWriteData("Test String".getBytes(), false);
        callback.addWriteData("1234567890".getBytes(), false);
        callback.addWriteData("woot!".getBytes(), true);
        BidirectionalStream stream = new BidirectionalStream
                                             .Builder(url, callback, callback.getExecutor(),
                                                     mTestFramework.mCronetEngine)
                                             .disableAutoFlush(true)
                                             .addHeader("foo", "bar")
                                             .addHeader("empty", "")
                                             .addHeader("Content-Type", "zebra")
                                             .build();
        stream.start();
        callback.blockForDone();
        assertTrue(stream.isDone());
        assertEquals(200, callback.mResponseInfo.getHttpStatusCode());
        assertEquals(
                "Test String1234567890woot!Test String1234567890woot!", callback.mResponseAsString);
        assertEquals("bar", callback.mResponseInfo.getAllHeaders().get("echo-foo").get(0));
        assertEquals("", callback.mResponseInfo.getAllHeaders().get("echo-empty").get(0));
        assertEquals(
                "zebra", callback.mResponseInfo.getAllHeaders().get("echo-content-type").get(0));
    }

    @SmallTest
    @Feature({"Cronet"})
    @OnlyRunNativeCronet
    public void testSimplePut() throws Exception {
        TestBidirectionalStreamCallback callback = new TestBidirectionalStreamCallback();
        callback.addWriteData("Put This Data!".getBytes());
        String methodName = "PUT";
        BidirectionalStream.Builder builder =
                new BidirectionalStream.Builder(Http2TestServer.getServerUrl(), callback,
                        callback.getExecutor(), mTestFramework.mCronetEngine);
        builder.setHttpMethod(methodName);
        builder.build().start();
        callback.blockForDone();
        assertEquals(200, callback.mResponseInfo.getHttpStatusCode());
        assertEquals("Put This Data!", callback.mResponseAsString);
        assertEquals(methodName, callback.mResponseInfo.getAllHeaders().get("echo-method").get(0));
    }

    @SmallTest
    @Feature({"Cronet"})
    @OnlyRunNativeCronet
    public void testBadMethod() throws Exception {
        TestBidirectionalStreamCallback callback = new TestBidirectionalStreamCallback();
        BidirectionalStream.Builder builder =
                new BidirectionalStream.Builder(Http2TestServer.getServerUrl(), callback,
                        callback.getExecutor(), mTestFramework.mCronetEngine);
        try {
            builder.setHttpMethod("bad:method!");
            builder.build().start();
            fail("IllegalArgumentException not thrown.");
        } catch (IllegalArgumentException e) {
            assertEquals("Invalid http method bad:method!", e.getMessage());
        }
    }

    @SmallTest
    @Feature({"Cronet"})
    @OnlyRunNativeCronet
    public void testBadHeaderName() throws Exception {
        TestBidirectionalStreamCallback callback = new TestBidirectionalStreamCallback();
        BidirectionalStream.Builder builder =
                new BidirectionalStream.Builder(Http2TestServer.getServerUrl(), callback,
                        callback.getExecutor(), mTestFramework.mCronetEngine);
        try {
            builder.addHeader("goodheader1", "headervalue");
            builder.addHeader("header:name", "headervalue");
            builder.addHeader("goodheader2", "headervalue");
            builder.build().start();
            fail("IllegalArgumentException not thrown.");
        } catch (IllegalArgumentException e) {
            assertEquals("Invalid header header:name=headervalue", e.getMessage());
        }
    }

    @SmallTest
    @Feature({"Cronet"})
    @OnlyRunNativeCronet
    public void testBadHeaderValue() throws Exception {
        TestBidirectionalStreamCallback callback = new TestBidirectionalStreamCallback();
        BidirectionalStream.Builder builder =
                new BidirectionalStream.Builder(Http2TestServer.getServerUrl(), callback,
                        callback.getExecutor(), mTestFramework.mCronetEngine);
        try {
            builder.addHeader("headername", "bad header\r\nvalue");
            builder.build().start();
            fail("IllegalArgumentException not thrown.");
        } catch (IllegalArgumentException e) {
            assertEquals("Invalid header headername=bad header\r\nvalue", e.getMessage());
        }
    }

    @SmallTest
    @Feature({"Cronet"})
    @OnlyRunNativeCronet
    public void testAddHeader() throws Exception {
        TestBidirectionalStreamCallback callback = new TestBidirectionalStreamCallback();
        String headerName = "header-name";
        String headerValue = "header-value";
        BidirectionalStream.Builder builder =
                new BidirectionalStream.Builder(Http2TestServer.getEchoHeaderUrl(headerName),
                        callback, callback.getExecutor(), mTestFramework.mCronetEngine);
        builder.addHeader(headerName, headerValue);
        builder.setHttpMethod("GET");
        builder.build().start();
        callback.blockForDone();
        assertEquals(200, callback.mResponseInfo.getHttpStatusCode());
        assertEquals(headerValue, callback.mResponseAsString);
    }

    @SmallTest
    @Feature({"Cronet"})
    @OnlyRunNativeCronet
    public void testMultiRequestHeaders() throws Exception {
        TestBidirectionalStreamCallback callback = new TestBidirectionalStreamCallback();
        String headerName = "header-name";
        String headerValue1 = "header-value1";
        String headerValue2 = "header-value2";
        BidirectionalStream.Builder builder =
                new BidirectionalStream.Builder(Http2TestServer.getEchoAllHeadersUrl(), callback,
                        callback.getExecutor(), mTestFramework.mCronetEngine);
        builder.addHeader(headerName, headerValue1);
        builder.addHeader(headerName, headerValue2);
        builder.setHttpMethod("GET");
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
    @OnlyRunNativeCronet
    public void testEchoTrailers() throws Exception {
        TestBidirectionalStreamCallback callback = new TestBidirectionalStreamCallback();
        String headerName = "header-name";
        String headerValue = "header-value";
        BidirectionalStream.Builder builder =
                new BidirectionalStream.Builder(Http2TestServer.getEchoTrailersUrl(), callback,
                        callback.getExecutor(), mTestFramework.mCronetEngine);
        builder.addHeader(headerName, headerValue);
        builder.setHttpMethod("GET");
        builder.build().start();
        callback.blockForDone();
        assertEquals(200, callback.mResponseInfo.getHttpStatusCode());
        assertNotNull(callback.mTrailers);
        // Verify that header value is properly echoed in trailers.
        assertEquals(headerValue, callback.mTrailers.getAsMap().get("echo-" + headerName).get(0));
    }

    @SmallTest
    @Feature({"Cronet"})
    @OnlyRunNativeCronet
    public void testCustomUserAgent() throws Exception {
        String userAgentName = "User-Agent";
        String userAgentValue = "User-Agent-Value";
        TestBidirectionalStreamCallback callback = new TestBidirectionalStreamCallback();
        BidirectionalStream.Builder builder =
                new BidirectionalStream.Builder(Http2TestServer.getEchoHeaderUrl(userAgentName),
                        callback, callback.getExecutor(), mTestFramework.mCronetEngine);
        builder.setHttpMethod("GET");
        builder.addHeader(userAgentName, userAgentValue);
        builder.build().start();
        callback.blockForDone();
        assertEquals(200, callback.mResponseInfo.getHttpStatusCode());
        assertEquals(userAgentValue, callback.mResponseAsString);
    }

    @SmallTest
    @Feature({"Cronet"})
    @OnlyRunNativeCronet
    public void testCustomCronetEngineUserAgent() throws Exception {
        String userAgentName = "User-Agent";
        String userAgentValue = "User-Agent-Value";
        CronetEngine engine =
                new CronetEngine.Builder(getContext())
                        .setMockCertVerifierForTesting(QuicTestServer.createMockCertVerifier())
                        .setUserAgent(userAgentValue)
                        .build();
        TestBidirectionalStreamCallback callback = new TestBidirectionalStreamCallback();
        BidirectionalStream.Builder builder =
                new BidirectionalStream.Builder(Http2TestServer.getEchoHeaderUrl(userAgentName),
                        callback, callback.getExecutor(), engine);
        builder.setHttpMethod("GET");
        builder.build().start();
        callback.blockForDone();
        assertEquals(200, callback.mResponseInfo.getHttpStatusCode());
        assertEquals(userAgentValue, callback.mResponseAsString);
    }

    @SmallTest
    @Feature({"Cronet"})
    @OnlyRunNativeCronet
    public void testDefaultUserAgent() throws Exception {
        String userAgentName = "User-Agent";
        TestBidirectionalStreamCallback callback = new TestBidirectionalStreamCallback();
        BidirectionalStream.Builder builder =
                new BidirectionalStream.Builder(Http2TestServer.getEchoHeaderUrl(userAgentName),
                        callback, callback.getExecutor(), mTestFramework.mCronetEngine);
        builder.setHttpMethod("GET");
        builder.build().start();
        callback.blockForDone();
        assertEquals(200, callback.mResponseInfo.getHttpStatusCode());
        assertEquals(new CronetEngine.Builder(getContext()).getDefaultUserAgent(),
                callback.mResponseAsString);
    }

    @SmallTest
    @Feature({"Cronet"})
    @OnlyRunNativeCronet
    public void testEchoStream() throws Exception {
        String url = Http2TestServer.getEchoStreamUrl();
        TestBidirectionalStreamCallback callback = new TestBidirectionalStreamCallback();
        String[] testData = {"Test String", createLongString("1234567890", 50000), "woot!"};
        StringBuilder stringData = new StringBuilder();
        for (String writeData : testData) {
            callback.addWriteData(writeData.getBytes());
            stringData.append(writeData);
        }
        // Create stream.
        BidirectionalStream stream = new BidirectionalStream
                                             .Builder(url, callback, callback.getExecutor(),
                                                     mTestFramework.mCronetEngine)
                                             .addHeader("foo", "Value with Spaces")
                                             .addHeader("Content-Type", "zebra")
                                             .build();
        stream.start();
        callback.blockForDone();
        assertTrue(stream.isDone());
        assertEquals(200, callback.mResponseInfo.getHttpStatusCode());
        assertEquals(stringData.toString(), callback.mResponseAsString);
        assertEquals(
                "Value with Spaces", callback.mResponseInfo.getAllHeaders().get("echo-foo").get(0));
        assertEquals(
                "zebra", callback.mResponseInfo.getAllHeaders().get("echo-content-type").get(0));
    }

    @SmallTest
    @Feature({"Cronet"})
    @OnlyRunNativeCronet
    public void testEchoStreamEmptyWrite() throws Exception {
        String url = Http2TestServer.getEchoStreamUrl();
        TestBidirectionalStreamCallback callback = new TestBidirectionalStreamCallback();
        callback.addWriteData(new byte[0]);
        // Create stream.
        BidirectionalStream stream = new BidirectionalStream
                                             .Builder(url, callback, callback.getExecutor(),
                                                     mTestFramework.mCronetEngine)
                                             .build();
        stream.start();
        callback.blockForDone();
        assertTrue(stream.isDone());
        assertEquals(200, callback.mResponseInfo.getHttpStatusCode());
        assertEquals("", callback.mResponseAsString);
    }

    @SmallTest
    @Feature({"Cronet"})
    @OnlyRunNativeCronet
    public void testDoubleWrite() throws Exception {
        String url = Http2TestServer.getEchoStreamUrl();
        TestBidirectionalStreamCallback callback = new TestBidirectionalStreamCallback() {
            @Override
            public void onStreamReady(BidirectionalStream stream) {
                // super class will call Write() once.
                super.onStreamReady(stream);
                // Call Write() again.
                startNextWrite(stream);
                // Make sure there is no pending write.
                assertEquals(0, numPendingWrites());
            }
        };
        callback.addWriteData("1".getBytes());
        callback.addWriteData("2".getBytes());
        // Create stream.
        BidirectionalStream stream = new BidirectionalStream
                                             .Builder(url, callback, callback.getExecutor(),
                                                     mTestFramework.mCronetEngine)
                                             .build();
        stream.start();
        callback.blockForDone();
        assertTrue(stream.isDone());
        assertEquals(200, callback.mResponseInfo.getHttpStatusCode());
        assertEquals("12", callback.mResponseAsString);
    }

    @SmallTest
    @Feature({"Cronet"})
    @OnlyRunNativeCronet
    public void testDoubleRead() throws Exception {
        String url = Http2TestServer.getEchoStreamUrl();
        TestBidirectionalStreamCallback callback = new TestBidirectionalStreamCallback() {
            @Override
            public void onResponseHeadersReceived(
                    BidirectionalStream stream, UrlResponseInfo info) {
                startNextRead(stream);
                try {
                    // Second read from callback invoked on single-threaded executor throws
                    // an exception because previous read is still pending until its completion
                    // is handled on executor.
                    stream.read(ByteBuffer.allocateDirect(5));
                    fail("Exception is not thrown.");
                } catch (Exception e) {
                    assertEquals("Unexpected read attempt.", e.getMessage());
                }
            }
        };
        callback.addWriteData("1".getBytes());
        callback.addWriteData("2".getBytes());
        // Create stream.
        BidirectionalStream stream = new BidirectionalStream
                                             .Builder(url, callback, callback.getExecutor(),
                                                     mTestFramework.mCronetEngine)
                                             .build();
        stream.start();
        callback.blockForDone();
        assertTrue(stream.isDone());
        assertEquals(200, callback.mResponseInfo.getHttpStatusCode());
        assertEquals("12", callback.mResponseAsString);
    }

    @SmallTest
    @Feature({"Cronet"})
    @OnlyRunNativeCronet
    // Disabled due to timeout. See crbug.com/591112
    @DisabledTest
    public void testReadAndWrite() throws Exception {
        String url = Http2TestServer.getEchoStreamUrl();
        TestBidirectionalStreamCallback callback = new TestBidirectionalStreamCallback() {
            @Override
            public void onResponseHeadersReceived(
                    BidirectionalStream stream, UrlResponseInfo info) {
                // Start the write, that will not complete until callback completion.
                startNextWrite(stream);
                // Start the read. It is allowed with write in flight.
                super.onResponseHeadersReceived(stream, info);
            }
        };
        callback.setAutoAdvance(false);
        callback.addWriteData("1".getBytes());
        callback.addWriteData("2".getBytes());
        // Create stream.
        BidirectionalStream stream = new BidirectionalStream
                                             .Builder(url, callback, callback.getExecutor(),
                                                     mTestFramework.mCronetEngine)
                                             .build();
        stream.start();
        callback.waitForNextWriteStep();
        callback.waitForNextReadStep();
        callback.startNextRead(stream);
        callback.setAutoAdvance(true);
        callback.blockForDone();
        assertTrue(stream.isDone());
        assertEquals(200, callback.mResponseInfo.getHttpStatusCode());
        assertEquals("12", callback.mResponseAsString);
    }

    @SmallTest
    @Feature({"Cronet"})
    @OnlyRunNativeCronet
    public void testEchoStreamWriteFirst() throws Exception {
        String url = Http2TestServer.getEchoStreamUrl();
        TestBidirectionalStreamCallback callback = new TestBidirectionalStreamCallback();
        callback.setAutoAdvance(false);
        String[] testData = {"a", "bb", "ccc", "Test String", "1234567890", "woot!"};
        StringBuilder stringData = new StringBuilder();
        for (String writeData : testData) {
            callback.addWriteData(writeData.getBytes());
            stringData.append(writeData);
        }
        // Create stream.
        BidirectionalStream stream = new BidirectionalStream
                                             .Builder(url, callback, callback.getExecutor(),
                                                     mTestFramework.mCronetEngine)
                                             .build();
        stream.start();
        // Write first.
        callback.waitForNextWriteStep(); // onStreamReady
        for (String expected : testData) {
            // Write next chunk of test data.
            callback.startNextWrite(stream);
            callback.waitForNextWriteStep(); // onWriteCompleted
        }

        // Wait for read step, but don't read yet.
        callback.waitForNextReadStep(); // onResponseHeadersReceived
        assertEquals("", callback.mResponseAsString);
        // Read back.
        callback.startNextRead(stream);
        callback.waitForNextReadStep(); // onReadCompleted
        // Verify that some part of proper response is read.
        assertTrue(callback.mResponseAsString.startsWith(testData[0]));
        assertTrue(stringData.toString().startsWith(callback.mResponseAsString));
        // Read the rest of the response.
        callback.setAutoAdvance(true);
        callback.startNextRead(stream);
        callback.blockForDone();
        assertTrue(stream.isDone());
        assertEquals(200, callback.mResponseInfo.getHttpStatusCode());
        assertEquals(stringData.toString(), callback.mResponseAsString);
    }

    @SmallTest
    @Feature({"Cronet"})
    @OnlyRunNativeCronet
    public void testEchoStreamStepByStep() throws Exception {
        String url = Http2TestServer.getEchoStreamUrl();
        TestBidirectionalStreamCallback callback = new TestBidirectionalStreamCallback();
        callback.setAutoAdvance(false);
        String[] testData = {"a", "bb", "ccc", "Test String", "1234567890", "woot!"};
        StringBuilder stringData = new StringBuilder();
        for (String writeData : testData) {
            callback.addWriteData(writeData.getBytes());
            stringData.append(writeData);
        }
        // Create stream.
        BidirectionalStream stream = new BidirectionalStream
                                             .Builder(url, callback, callback.getExecutor(),
                                                     mTestFramework.mCronetEngine)
                                             .build();
        stream.start();
        callback.waitForNextWriteStep();
        callback.waitForNextReadStep();

        for (String expected : testData) {
            // Write next chunk of test data.
            callback.startNextWrite(stream);
            callback.waitForNextWriteStep();

            // Read next chunk of test data.
            ByteBuffer readBuffer = ByteBuffer.allocateDirect(100);
            callback.startNextRead(stream, readBuffer);
            callback.waitForNextReadStep();
            assertEquals(expected.length(), readBuffer.position());
            assertFalse(stream.isDone());
        }

        callback.setAutoAdvance(true);
        callback.startNextRead(stream);
        callback.blockForDone();
        assertTrue(stream.isDone());
        assertEquals(200, callback.mResponseInfo.getHttpStatusCode());
        assertEquals(stringData.toString(), callback.mResponseAsString);
    }

    /**
     * Checks that the buffer is updated correctly, when starting at an offset.
     */
    @SmallTest
    @Feature({"Cronet"})
    @OnlyRunNativeCronet
    public void testSimpleGetBufferUpdates() throws Exception {
        TestBidirectionalStreamCallback callback = new TestBidirectionalStreamCallback();
        callback.setAutoAdvance(false);
        // Since the method is "GET", the expected response body is also "GET".
        BidirectionalStream.Builder builder =
                new BidirectionalStream.Builder(Http2TestServer.getEchoMethodUrl(), callback,
                        callback.getExecutor(), mTestFramework.mCronetEngine);
        BidirectionalStream stream = builder.setHttpMethod("GET").build();
        stream.start();
        callback.waitForNextReadStep();

        assertEquals(null, callback.mError);
        assertFalse(callback.isDone());
        assertEquals(TestBidirectionalStreamCallback.ResponseStep.ON_RESPONSE_STARTED,
                callback.mResponseStep);

        ByteBuffer readBuffer = ByteBuffer.allocateDirect(5);
        readBuffer.put("FOR".getBytes());
        assertEquals(3, readBuffer.position());

        // Read first two characters of the response ("GE"). It's theoretically
        // possible to need one read per character, though in practice,
        // shouldn't happen.
        while (callback.mResponseAsString.length() < 2) {
            assertFalse(callback.isDone());
            callback.startNextRead(stream, readBuffer);
            callback.waitForNextReadStep();
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
        callback.startNextRead(stream, readBuffer);
        callback.waitForNextReadStep();

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
        callback.setAutoAdvance(true);
        callback.startNextRead(stream, readBuffer);
        callback.blockForDone();

        assertEquals(200, callback.mResponseInfo.getHttpStatusCode());
        assertEquals("GET", callback.mResponseAsString);
        checkResponseInfo(callback.mResponseInfo, Http2TestServer.getEchoMethodUrl(), 200, "");

        // Check that buffer contents were not modified.
        assertEquals("FORTE", bufferContentsToString(readBuffer, 0, 5));

        // Position should not have been modified, since nothing was read.
        assertEquals(1, readBuffer.position());
        // Limit should be unchanged as always.
        assertEquals(5, readBuffer.limit());

        assertEquals(ResponseStep.ON_SUCCEEDED, callback.mResponseStep);

        // Make sure there are no other pending messages, which would trigger
        // asserts in TestBidirectionalCallback.
        testSimpleGet();
    }

    @SmallTest
    @Feature({"Cronet"})
    @OnlyRunNativeCronet
    public void testBadBuffers() throws Exception {
        TestBidirectionalStreamCallback callback = new TestBidirectionalStreamCallback();
        callback.setAutoAdvance(false);
        BidirectionalStream.Builder builder =
                new BidirectionalStream.Builder(Http2TestServer.getEchoMethodUrl(), callback,
                        callback.getExecutor(), mTestFramework.mCronetEngine);
        BidirectionalStream stream = builder.setHttpMethod("GET").build();
        stream.start();
        callback.waitForNextReadStep();

        assertEquals(null, callback.mError);
        assertFalse(callback.isDone());
        assertEquals(TestBidirectionalStreamCallback.ResponseStep.ON_RESPONSE_STARTED,
                callback.mResponseStep);

        // Try to read using a full buffer.
        try {
            ByteBuffer readBuffer = ByteBuffer.allocateDirect(4);
            readBuffer.put("full".getBytes());
            stream.read(readBuffer);
            fail("Exception not thrown");
        } catch (IllegalArgumentException e) {
            assertEquals("ByteBuffer is already full.", e.getMessage());
        }

        // Try to read using a non-direct buffer.
        try {
            ByteBuffer readBuffer = ByteBuffer.allocate(5);
            stream.read(readBuffer);
            fail("Exception not thrown");
        } catch (Exception e) {
            assertEquals("byteBuffer must be a direct ByteBuffer.", e.getMessage());
        }

        // Finish the stream with a direct ByteBuffer.
        callback.setAutoAdvance(true);
        ByteBuffer readBuffer = ByteBuffer.allocateDirect(5);
        stream.read(readBuffer);
        callback.blockForDone();
        assertEquals(200, callback.mResponseInfo.getHttpStatusCode());
        assertEquals("GET", callback.mResponseAsString);
    }

    private void throwOrCancel(
            FailureType failureType, ResponseStep failureStep, boolean expectError) {
        TestBidirectionalStreamCallback callback = new TestBidirectionalStreamCallback();
        callback.setFailure(failureType, failureStep);
        BidirectionalStream.Builder builder =
                new BidirectionalStream.Builder(Http2TestServer.getEchoMethodUrl(), callback,
                        callback.getExecutor(), mTestFramework.mCronetEngine);
        BidirectionalStream stream = builder.setHttpMethod("GET").build();
        stream.start();
        callback.blockForDone();
        // assertEquals(callback.mResponseStep, failureStep);
        assertTrue(stream.isDone());
        // Cancellation when stream is ready does not guarantee that
        // mResponseInfo is null because there might be a
        // onResponseHeadersReceived already queued in the executor.
        // See crbug.com/594432.
        if (failureStep != ResponseStep.ON_STREAM_READY) {
            assertNotNull(callback.mResponseInfo);
        }
        assertEquals(expectError, callback.mError != null);
        assertEquals(expectError, callback.mOnErrorCalled);
        assertEquals(failureType == FailureType.CANCEL_SYNC
                        || failureType == FailureType.CANCEL_ASYNC
                        || failureType == FailureType.CANCEL_ASYNC_WITHOUT_PAUSE,
                callback.mOnCanceledCalled);
    }

    @SmallTest
    @Feature({"Cronet"})
    @OnlyRunNativeCronet
    public void testFailures() throws Exception {
        throwOrCancel(FailureType.CANCEL_SYNC, ResponseStep.ON_STREAM_READY, false);
        throwOrCancel(FailureType.CANCEL_ASYNC, ResponseStep.ON_STREAM_READY, false);
        throwOrCancel(FailureType.CANCEL_ASYNC_WITHOUT_PAUSE, ResponseStep.ON_STREAM_READY, false);
        throwOrCancel(FailureType.THROW_SYNC, ResponseStep.ON_STREAM_READY, true);

        throwOrCancel(FailureType.CANCEL_SYNC, ResponseStep.ON_RESPONSE_STARTED, false);
        throwOrCancel(FailureType.CANCEL_ASYNC, ResponseStep.ON_RESPONSE_STARTED, false);
        throwOrCancel(
                FailureType.CANCEL_ASYNC_WITHOUT_PAUSE, ResponseStep.ON_RESPONSE_STARTED, false);
        throwOrCancel(FailureType.THROW_SYNC, ResponseStep.ON_RESPONSE_STARTED, true);

        throwOrCancel(FailureType.CANCEL_SYNC, ResponseStep.ON_READ_COMPLETED, false);
        throwOrCancel(FailureType.CANCEL_ASYNC, ResponseStep.ON_READ_COMPLETED, false);
        throwOrCancel(
                FailureType.CANCEL_ASYNC_WITHOUT_PAUSE, ResponseStep.ON_READ_COMPLETED, false);
        throwOrCancel(FailureType.THROW_SYNC, ResponseStep.ON_READ_COMPLETED, true);
    }

    @SmallTest
    @Feature({"Cronet"})
    @OnlyRunNativeCronet
    public void testThrowOnSucceeded() {
        TestBidirectionalStreamCallback callback = new TestBidirectionalStreamCallback();
        callback.setFailure(FailureType.THROW_SYNC, ResponseStep.ON_SUCCEEDED);
        BidirectionalStream.Builder builder =
                new BidirectionalStream.Builder(Http2TestServer.getEchoMethodUrl(), callback,
                        callback.getExecutor(), mTestFramework.mCronetEngine);
        BidirectionalStream stream = builder.setHttpMethod("GET").build();
        stream.start();
        callback.blockForDone();
        assertEquals(callback.mResponseStep, ResponseStep.ON_SUCCEEDED);
        assertTrue(stream.isDone());
        assertNotNull(callback.mResponseInfo);
        // Check that error thrown from 'onSucceeded' callback is not reported.
        assertNull(callback.mError);
        assertFalse(callback.mOnErrorCalled);
    }

    @SmallTest
    @Feature({"Cronet"})
    @OnlyRunNativeCronet
    public void testExecutorShutdownBeforeStreamIsDone() {
        // Test that stream is destroyed even if executor is shut down and rejects posting tasks.
        TestBidirectionalStreamCallback callback = new TestBidirectionalStreamCallback();
        callback.setAutoAdvance(false);
        BidirectionalStream.Builder builder =
                new BidirectionalStream.Builder(Http2TestServer.getEchoMethodUrl(), callback,
                        callback.getExecutor(), mTestFramework.mCronetEngine);
        CronetBidirectionalStream stream =
                (CronetBidirectionalStream) builder.setHttpMethod("GET").build();
        stream.start();
        callback.waitForNextReadStep();
        assertFalse(callback.isDone());
        assertFalse(stream.isDone());

        final ConditionVariable streamDestroyed = new ConditionVariable(false);
        stream.setOnDestroyedCallbackForTesting(new Runnable() {
            @Override
            public void run() {
                streamDestroyed.open();
            }
        });

        // Shut down the executor, so posting the task will throw an exception.
        callback.shutdownExecutor();
        ByteBuffer readBuffer = ByteBuffer.allocateDirect(5);
        stream.read(readBuffer);
        // Callback will never be called again because executor is shut down,
        // but stream will be destroyed from network thread.
        streamDestroyed.block();

        assertFalse(callback.isDone());
        assertTrue(stream.isDone());
    }

    /**
     * Callback that shuts down the engine when the stream has succeeded
     * or failed.
     */
    private class ShutdownTestBidirectionalStreamCallback extends TestBidirectionalStreamCallback {
        @Override
        public void onSucceeded(BidirectionalStream stream, UrlResponseInfo info) {
            mTestFramework.mCronetEngine.shutdown();
            // Clear mCronetEngine so it doesn't get shut down second time in tearDown().
            mTestFramework.mCronetEngine = null;
            super.onSucceeded(stream, info);
        }

        @Override
        public void onFailed(
                BidirectionalStream stream, UrlResponseInfo info, CronetException error) {
            mTestFramework.mCronetEngine.shutdown();
            // Clear mCronetEngine so it doesn't get shut down second time in tearDown().
            mTestFramework.mCronetEngine = null;
            super.onFailed(stream, info, error);
        }

        @Override
        public void onCanceled(BidirectionalStream stream, UrlResponseInfo info) {
            mTestFramework.mCronetEngine.shutdown();
            // Clear mCronetEngine so it doesn't get shut down second time in tearDown().
            mTestFramework.mCronetEngine = null;
            super.onCanceled(stream, info);
        }
    }

    @SmallTest
    @Feature({"Cronet"})
    @OnlyRunNativeCronet
    public void testCronetEngineShutdown() throws Exception {
        // Test that CronetEngine cannot be shut down if there are any active streams.
        TestBidirectionalStreamCallback callback = new ShutdownTestBidirectionalStreamCallback();
        // Block callback when response starts to verify that shutdown fails
        // if there are active streams.
        callback.setAutoAdvance(false);
        BidirectionalStream.Builder builder =
                new BidirectionalStream.Builder(Http2TestServer.getEchoMethodUrl(), callback,
                        callback.getExecutor(), mTestFramework.mCronetEngine);
        CronetBidirectionalStream stream =
                (CronetBidirectionalStream) builder.setHttpMethod("GET").build();
        stream.start();
        try {
            mTestFramework.mCronetEngine.shutdown();
            fail("Should throw an exception");
        } catch (Exception e) {
            assertEquals("Cannot shutdown with active requests.", e.getMessage());
        }

        callback.waitForNextReadStep();
        assertEquals(ResponseStep.ON_RESPONSE_STARTED, callback.mResponseStep);
        try {
            mTestFramework.mCronetEngine.shutdown();
            fail("Should throw an exception");
        } catch (Exception e) {
            assertEquals("Cannot shutdown with active requests.", e.getMessage());
        }
        callback.startNextRead(stream);

        callback.waitForNextReadStep();
        assertEquals(ResponseStep.ON_READ_COMPLETED, callback.mResponseStep);
        try {
            mTestFramework.mCronetEngine.shutdown();
            fail("Should throw an exception");
        } catch (Exception e) {
            assertEquals("Cannot shutdown with active requests.", e.getMessage());
        }

        // May not have read all the data, in theory. Just enable auto-advance
        // and finish the request.
        callback.setAutoAdvance(true);
        callback.startNextRead(stream);
        callback.blockForDone();
    }

    @SmallTest
    @Feature({"Cronet"})
    @OnlyRunNativeCronet
    public void testCronetEngineShutdownAfterStreamFailure() throws Exception {
        // Test that CronetEngine can be shut down after stream reports a failure.
        TestBidirectionalStreamCallback callback = new ShutdownTestBidirectionalStreamCallback();
        BidirectionalStream.Builder builder =
                new BidirectionalStream.Builder(Http2TestServer.getEchoMethodUrl(), callback,
                        callback.getExecutor(), mTestFramework.mCronetEngine);
        CronetBidirectionalStream stream =
                (CronetBidirectionalStream) builder.setHttpMethod("GET").build();
        stream.start();
        callback.setFailure(FailureType.THROW_SYNC, ResponseStep.ON_READ_COMPLETED);
        callback.blockForDone();
        assertTrue(callback.mOnErrorCalled);
        assertNull(mTestFramework.mCronetEngine);
    }

    @SmallTest
    @Feature({"Cronet"})
    @OnlyRunNativeCronet
    public void testCronetEngineShutdownAfterStreamCancel() throws Exception {
        // Test that CronetEngine can be shut down after stream is canceled.
        TestBidirectionalStreamCallback callback = new ShutdownTestBidirectionalStreamCallback();
        BidirectionalStream.Builder builder =
                new BidirectionalStream.Builder(Http2TestServer.getEchoMethodUrl(), callback,
                        callback.getExecutor(), mTestFramework.mCronetEngine);
        CronetBidirectionalStream stream =
                (CronetBidirectionalStream) builder.setHttpMethod("GET").build();

        // Block callback when response starts to verify that shutdown fails
        // if there are active requests.
        callback.setAutoAdvance(false);
        stream.start();
        try {
            mTestFramework.mCronetEngine.shutdown();
            fail("Should throw an exception");
        } catch (Exception e) {
            assertEquals("Cannot shutdown with active requests.", e.getMessage());
        }
        callback.waitForNextReadStep();
        assertEquals(ResponseStep.ON_RESPONSE_STARTED, callback.mResponseStep);
        stream.cancel();
        callback.blockForDone();
        assertTrue(callback.mOnCanceledCalled);
        assertNull(mTestFramework.mCronetEngine);
    }

    // Returns the contents of byteBuffer, from its position() to its limit(),
    // as a String. Does not modify byteBuffer's position().
    private static String bufferContentsToString(ByteBuffer byteBuffer, int start, int end) {
        // Use a duplicate to avoid modifying byteBuffer.
        ByteBuffer duplicate = byteBuffer.duplicate();
        duplicate.position(start);
        duplicate.limit(end);
        byte[] contents = new byte[duplicate.remaining()];
        duplicate.get(contents);
        return new String(contents);
    }
}
