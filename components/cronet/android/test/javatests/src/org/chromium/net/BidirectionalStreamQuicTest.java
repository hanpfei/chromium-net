// Copyright 2016 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.net;

import android.test.suitebuilder.annotation.SmallTest;

import org.chromium.base.test.util.Feature;
import org.chromium.net.CronetTestBase.OnlyRunNativeCronet;
import org.json.JSONObject;

import java.nio.ByteBuffer;

/**
 * Tests functionality of BidirectionalStream's QUIC implementation.
 */
public class BidirectionalStreamQuicTest extends CronetTestBase {
    private CronetTestFramework mTestFramework;
    private enum QuicBidirectionalStreams {
        ENABLED,
        DISABLED,
    }

    private void setUp(QuicBidirectionalStreams enabled) throws Exception {
        // Load library first to create MockCertVerifier.
        System.loadLibrary("cronet_tests");
        CronetEngine.Builder builder = new CronetEngine.Builder(getContext());

        QuicTestServer.startQuicTestServer(getContext());

        builder.enableQuic(true);
        JSONObject quicParams = new JSONObject().put("host_whitelist", "test.example.com");
        if (enabled == QuicBidirectionalStreams.DISABLED) {
            quicParams.put("quic_disable_bidirectional_streams", true);
        }
        JSONObject experimentalOptions = new JSONObject().put("QUIC", quicParams);
        builder.setExperimentalOptions(experimentalOptions.toString());

        builder.addQuicHint(QuicTestServer.getServerHost(), QuicTestServer.getServerPort(),
                QuicTestServer.getServerPort());

        builder.setMockCertVerifierForTesting(QuicTestServer.createMockCertVerifier());

        mTestFramework = startCronetTestFrameworkWithUrlAndCronetEngineBuilder(null, builder);
        registerHostResolver(mTestFramework);
    }

    @Override
    protected void tearDown() throws Exception {
        QuicTestServer.shutdownQuicTestServer();
        super.tearDown();
    }

    @SmallTest
    @Feature({"Cronet"})
    @OnlyRunNativeCronet
    // Test that QUIC is negotiated.
    public void testSimpleGet() throws Exception {
        setUp(QuicBidirectionalStreams.ENABLED);
        String path = "/simple.txt";
        String quicURL = QuicTestServer.getServerURL() + path;
        TestBidirectionalStreamCallback callback = new TestBidirectionalStreamCallback();
        BidirectionalStream stream = new BidirectionalStream
                                             .Builder(quicURL, callback, callback.getExecutor(),
                                                     mTestFramework.mCronetEngine)
                                             .setHttpMethod("GET")
                                             .build();
        stream.start();
        callback.blockForDone();
        assertTrue(stream.isDone());
        assertEquals(200, callback.mResponseInfo.getHttpStatusCode());
        assertEquals("This is a simple text file served by QUIC.\n", callback.mResponseAsString);
        assertEquals("quic/1+spdy/3", callback.mResponseInfo.getNegotiatedProtocol());
    }

    @SmallTest
    @Feature({"Cronet"})
    @OnlyRunNativeCronet
    public void testSimplePost() throws Exception {
        setUp(QuicBidirectionalStreams.ENABLED);
        String path = "/simple.txt";
        String quicURL = QuicTestServer.getServerURL() + path;
        TestBidirectionalStreamCallback callback = new TestBidirectionalStreamCallback();
        // Although we have no way to verify data sent at this point, this test
        // can make sure that onWriteCompleted is invoked appropriately.
        callback.addWriteData("Test String".getBytes());
        callback.addWriteData("1234567890".getBytes());
        callback.addWriteData("woot!".getBytes());
        BidirectionalStream stream = new BidirectionalStream
                                             .Builder(quicURL, callback, callback.getExecutor(),
                                                     mTestFramework.mCronetEngine)
                                             .addHeader("foo", "bar")
                                             .addHeader("empty", "")
                                             .addHeader("Content-Type", "zebra")
                                             .build();
        stream.start();
        callback.blockForDone();
        assertTrue(stream.isDone());
        assertEquals(200, callback.mResponseInfo.getHttpStatusCode());
        assertEquals("This is a simple text file served by QUIC.\n", callback.mResponseAsString);
        assertEquals("quic/1+spdy/3", callback.mResponseInfo.getNegotiatedProtocol());
    }

    @SmallTest
    @Feature({"Cronet"})
    @OnlyRunNativeCronet
    public void testSimplePostWithFlush() throws Exception {
        setUp(QuicBidirectionalStreams.ENABLED);
        // TODO(xunjieli): Use ParameterizedTest instead of the loop.
        for (int i = 0; i < 2; i++) {
            String path = "/simple.txt";
            String quicURL = QuicTestServer.getServerURL() + path;
            TestBidirectionalStreamCallback callback = new TestBidirectionalStreamCallback();
            // Although we have no way to verify data sent at this point, this test
            // can make sure that onWriteCompleted is invoked appropriately.
            callback.addWriteData("Test String".getBytes(), false);
            callback.addWriteData("1234567890".getBytes(), false);
            callback.addWriteData("woot!".getBytes(), true);
            BidirectionalStream stream = new BidirectionalStream
                                                 .Builder(quicURL, callback, callback.getExecutor(),
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
            assertEquals(
                    "This is a simple text file served by QUIC.\n", callback.mResponseAsString);
            assertEquals("quic/1+spdy/3", callback.mResponseInfo.getNegotiatedProtocol());
        }
    }

    @SmallTest
    @Feature({"Cronet"})
    @OnlyRunNativeCronet
    public void testSimplePostWithFlushTwice() throws Exception {
        setUp(QuicBidirectionalStreams.ENABLED);
        // TODO(xunjieli): Use ParameterizedTest instead of the loop.
        for (int i = 0; i < 2; i++) {
            String path = "/simple.txt";
            String quicURL = QuicTestServer.getServerURL() + path;
            TestBidirectionalStreamCallback callback = new TestBidirectionalStreamCallback();
            // Although we have no way to verify data sent at this point, this test
            // can make sure that onWriteCompleted is invoked appropriately.
            callback.addWriteData("Test String".getBytes(), false);
            callback.addWriteData("1234567890".getBytes(), false);
            callback.addWriteData("woot!".getBytes(), true);
            callback.addWriteData("Test String".getBytes(), false);
            callback.addWriteData("1234567890".getBytes(), false);
            callback.addWriteData("woot!".getBytes(), true);
            BidirectionalStream stream = new BidirectionalStream
                                                 .Builder(quicURL, callback, callback.getExecutor(),
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
            assertEquals(
                    "This is a simple text file served by QUIC.\n", callback.mResponseAsString);
            assertEquals("quic/1+spdy/3", callback.mResponseInfo.getNegotiatedProtocol());
        }
    }

    @SmallTest
    @Feature({"Cronet"})
    @OnlyRunNativeCronet
    public void testSimpleGetWithFlush() throws Exception {
        setUp(QuicBidirectionalStreams.ENABLED);
        // TODO(xunjieli): Use ParameterizedTest instead of the loop.
        for (int i = 0; i < 2; i++) {
            String path = "/simple.txt";
            String url = QuicTestServer.getServerURL() + path;

            TestBidirectionalStreamCallback callback = new TestBidirectionalStreamCallback() {
                @Override
                public void onStreamReady(BidirectionalStream stream) {
                    // This flush should send the delayed headers.
                    stream.flush();
                    super.onStreamReady(stream);
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
            assertEquals(
                    "This is a simple text file served by QUIC.\n", callback.mResponseAsString);
            assertEquals("quic/1+spdy/3", callback.mResponseInfo.getNegotiatedProtocol());
        }
    }

    @SmallTest
    @Feature({"Cronet"})
    @OnlyRunNativeCronet
    public void testSimplePostWithFlushAfterOneWrite() throws Exception {
        setUp(QuicBidirectionalStreams.ENABLED);
        // TODO(xunjieli): Use ParameterizedTest instead of the loop.
        for (int i = 0; i < 2; i++) {
            String path = "/simple.txt";
            String url = QuicTestServer.getServerURL() + path;

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
            assertEquals(
                    "This is a simple text file served by QUIC.\n", callback.mResponseAsString);
            assertEquals("quic/1+spdy/3", callback.mResponseInfo.getNegotiatedProtocol());
        }
    }

    @SmallTest
    @Feature({"Cronet"})
    @OnlyRunNativeCronet
    public void testQuicBidirectionalStreamDisabled() throws Exception {
        setUp(QuicBidirectionalStreams.DISABLED);
        String path = "/simple.txt";
        String quicURL = QuicTestServer.getServerURL() + path;

        TestBidirectionalStreamCallback callback = new TestBidirectionalStreamCallback();
        BidirectionalStream stream = new BidirectionalStream
                                             .Builder(quicURL, callback, callback.getExecutor(),
                                                     mTestFramework.mCronetEngine)
                                             .setHttpMethod("GET")
                                             .build();
        stream.start();
        callback.blockForDone();
        assertTrue(stream.isDone());
        assertTrue(callback.mOnErrorCalled);
        assertNull(callback.mResponseInfo);
    }

    @SmallTest
    @Feature({"Cronet"})
    @OnlyRunNativeCronet
    // Tests that if the stream failed between the time when we issue a Write()
    // and when the Write() is executed in the native stack, there is no crash.
    // This test is racy, but it should catch a crash (if there is any) most of
    // the time.
    public void testStreamFailBeforeWriteIsExecutedOnNetworkThread() throws Exception {
        setUp(QuicBidirectionalStreams.ENABLED);
        String path = "/simple.txt";
        String quicURL = QuicTestServer.getServerURL() + path;

        TestBidirectionalStreamCallback callback = new TestBidirectionalStreamCallback() {
            @Override
            public void onWriteCompleted(BidirectionalStream stream, UrlResponseInfo info,
                    ByteBuffer buffer, boolean endOfStream) {
                // Super class will write the next piece of data.
                super.onWriteCompleted(stream, info, buffer, endOfStream);
                // Shut down the server, and the stream should error out.
                // The second call to shutdownQuicTestServer is no-op.
                QuicTestServer.shutdownQuicTestServer();
            }
        };

        callback.addWriteData("Test String".getBytes());
        callback.addWriteData("1234567890".getBytes());
        callback.addWriteData("woot!".getBytes());

        BidirectionalStream stream = new BidirectionalStream
                                             .Builder(quicURL, callback, callback.getExecutor(),
                                                     mTestFramework.mCronetEngine)
                                             .addHeader("foo", "bar")
                                             .addHeader("empty", "")
                                             .addHeader("Content-Type", "zebra")
                                             .build();
        stream.start();
        callback.blockForDone();
        assertTrue(stream.isDone());
        // Server terminated on us, so the stream must fail.
        // QUIC reports this as ERR_QUIC_PROTOCOL_ERROR. Sometimes we get ERR_CONNECTION_REFUSED.
        assertNotNull(callback.mError);
        assertTrue(NetError.ERR_QUIC_PROTOCOL_ERROR == callback.mError.getCronetInternalErrorCode()
                || NetError.ERR_CONNECTION_REFUSED == callback.mError.getCronetInternalErrorCode());
    }

    @SmallTest
    @Feature({"Cronet"})
    @OnlyRunNativeCronet
    // Test that certificate verify results are serialized and deserialized correctly.
    public void testSerializeDeserialize() throws Exception {
        setUp(QuicBidirectionalStreams.ENABLED);
        String path = "/simple.txt";
        String quicURL = QuicTestServer.getServerURL() + path;
        TestBidirectionalStreamCallback callback = new TestBidirectionalStreamCallback();
        BidirectionalStream stream = new BidirectionalStream
                                             .Builder(quicURL, callback, callback.getExecutor(),
                                                     mTestFramework.mCronetEngine)
                                             .setHttpMethod("GET")
                                             .build();
        stream.start();
        callback.blockForDone();
        assertTrue(stream.isDone());
        assertEquals(200, callback.mResponseInfo.getHttpStatusCode());
        assertEquals("This is a simple text file served by QUIC.\n", callback.mResponseAsString);
        assertEquals("quic/1+spdy/3", callback.mResponseInfo.getNegotiatedProtocol());

        String serialized_data = mTestFramework.mCronetEngine.getCertVerifierData(100);
        assertFalse(serialized_data.isEmpty());

        // Create a new builder and verify that the |serialized_data| is deserialized correctly.
        CronetEngine.Builder builder = new CronetEngine.Builder(getContext());
        builder.enableQuic(true);
        builder.setMockCertVerifierForTesting(QuicTestServer.createMockCertVerifier());
        builder.setCertVerifierData(serialized_data);

        CronetTestFramework testFramework =
                startCronetTestFrameworkWithUrlAndCronetEngineBuilder(null, builder);
        String deserialized_data = testFramework.mCronetEngine.getCertVerifierData(100);
        assertEquals(deserialized_data, serialized_data);
    }
}
