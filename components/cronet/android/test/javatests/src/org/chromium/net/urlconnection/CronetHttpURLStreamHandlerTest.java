// Copyright 2015 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.net.urlconnection;

import android.test.suitebuilder.annotation.SmallTest;

import org.chromium.base.test.util.Feature;
import org.chromium.net.CronetTestBase;
import org.chromium.net.CronetTestFramework;
import org.chromium.net.NativeTestServer;

import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;

/**
 * Tests for CronetHttpURLStreamHandler class.
 */
public class CronetHttpURLStreamHandlerTest extends CronetTestBase {
    private CronetTestFramework mTestFramework;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mTestFramework = startCronetTestFramework();
        assertTrue(NativeTestServer.startNativeTestServer(getContext()));
    }

    @Override
    protected void tearDown() throws Exception {
        NativeTestServer.shutdownNativeTestServer();
        super.tearDown();
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testOpenConnectionHttp() throws Exception {
        URL url = new URL(NativeTestServer.getEchoMethodURL());
        CronetHttpURLStreamHandler streamHandler =
                new CronetHttpURLStreamHandler(mTestFramework.mCronetEngine);
        HttpURLConnection connection =
                (HttpURLConnection) streamHandler.openConnection(url);
        assertEquals(200, connection.getResponseCode());
        assertEquals("OK", connection.getResponseMessage());
        assertEquals("GET", TestUtil.getResponseAsString(connection));
        connection.disconnect();
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testOpenConnectionHttps() throws Exception {
        URL url = new URL("https://example.com");
        CronetHttpURLStreamHandler streamHandler =
                new CronetHttpURLStreamHandler(mTestFramework.mCronetEngine);
        HttpURLConnection connection =
                (HttpURLConnection) streamHandler.openConnection(url);
        assertNotNull(connection);
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testOpenConnectionProtocolNotSupported() throws Exception {
        URL url = new URL("ftp://example.com");
        CronetHttpURLStreamHandler streamHandler =
                new CronetHttpURLStreamHandler(mTestFramework.mCronetEngine);
        try {
            streamHandler.openConnection(url);
            fail();
        } catch (UnsupportedOperationException e) {
            assertEquals("Unexpected protocol:ftp", e.getMessage());
        }
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testOpenConnectionWithProxy() throws Exception {
        URL url = new URL(NativeTestServer.getEchoMethodURL());
        CronetHttpURLStreamHandler streamHandler =
                new CronetHttpURLStreamHandler(mTestFramework.mCronetEngine);
        Proxy proxy = new Proxy(Proxy.Type.HTTP,
                new InetSocketAddress("127.0.0.1", 8080));
        try {
            streamHandler.openConnection(url, proxy);
            fail();
        } catch (UnsupportedOperationException e) {
            // Expected.
        }
    }
}
