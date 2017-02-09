// Copyright 2014 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.net;

import android.test.suitebuilder.annotation.SmallTest;

import org.chromium.base.test.util.Feature;
import org.chromium.net.impl.ChromiumUrlRequest;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.util.HashMap;
import java.util.concurrent.Executors;

/**
 * Test fixture to test upload APIs.  Uses an in-process test server.
 */
@SuppressWarnings("deprecation")
public class UploadTest extends CronetTestBase {
    private static final String UPLOAD_DATA = "Nifty upload data!";
    private static final String UPLOAD_CHANNEL_DATA = "Upload channel data";

    private CronetTestFramework mTestFramework;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mTestFramework = startCronetTestFrameworkForLegacyApi(null);
        assertNotNull(mTestFramework);
        assertTrue(NativeTestServer.startNativeTestServer(getContext()));
    }

    private HttpUrlRequest createRequest(
            String url, HttpUrlRequestListener listener) {
        HashMap<String, String> headers = new HashMap<String, String>();
        return mTestFramework.mRequestFactory.createRequest(
                url, HttpUrlRequest.REQUEST_PRIORITY_MEDIUM, headers, listener);
    }

    /**
     * Sets request to have an upload channel containing the given data.
     * uploadDataLength should generally be uploadData.length(), unless a test
     * needs to get a read error.
     */
    private void setUploadChannel(HttpUrlRequest request,
                                  String contentType,
                                  String uploadData,
                                  int uploadDataLength) {
        InputStream uploadDataStream = new ByteArrayInputStream(
                uploadData.getBytes());
        ReadableByteChannel uploadDataChannel =
                Channels.newChannel(uploadDataStream);
        request.setUploadChannel(
                contentType, uploadDataChannel, uploadDataLength);
    }

    /**
     * Tests uploading an in-memory string.
     */
    @SmallTest
    @Feature({"Cronet"})
    public void testUploadData() throws Exception {
        TestHttpUrlRequestListener listener = new TestHttpUrlRequestListener();
        HttpUrlRequest request = createRequest(
                NativeTestServer.getEchoBodyURL(), listener);
        request.setUploadData("text/plain", UPLOAD_DATA.getBytes("UTF8"));
        request.start();
        listener.blockForComplete();

        assertEquals(200, listener.mHttpStatusCode);
        assertEquals(UPLOAD_DATA, listener.mResponseAsString);
    }

    /**
     * Tests uploading an in-memory string with a redirect that preserves the
     * POST body.  This makes sure the body is correctly sent again.
     */
    @SmallTest
    @Feature({"Cronet"})
    public void testUploadDataWithRedirect() throws Exception {
        TestHttpUrlRequestListener listener = new TestHttpUrlRequestListener();
        HttpUrlRequest request = createRequest(
                NativeTestServer.getRedirectToEchoBody(), listener);
        request.setUploadData("text/plain", UPLOAD_DATA.getBytes("UTF8"));
        request.start();
        listener.blockForComplete();

        assertEquals(200, listener.mHttpStatusCode);
        assertEquals(UPLOAD_DATA, listener.mResponseAsString);
    }

    /**
     * Tests Content-Type can be set when uploading an in-memory string.
     */
    @SmallTest
    @Feature({"Cronet"})
    public void testUploadDataContentType() throws Exception {
        String contentTypes[] = {"text/plain", "chicken/spicy"};
        for (String contentType : contentTypes) {
            TestHttpUrlRequestListener listener =
                    new TestHttpUrlRequestListener();
            HttpUrlRequest request = createRequest(
                    NativeTestServer.getEchoHeaderURL("Content-Type"),
                    listener);
            request.setUploadData(contentType, UPLOAD_DATA.getBytes("UTF8"));
            request.start();
            listener.blockForComplete();

            assertEquals(200, listener.mHttpStatusCode);
            assertEquals(contentType, listener.mResponseAsString);
        }
    }

    /**
     * Tests the default method when uploading.
     */
    @SmallTest
    @Feature({"Cronet"})
    public void testDefaultUploadMethod() throws Exception {
        TestHttpUrlRequestListener listener = new TestHttpUrlRequestListener();
        HttpUrlRequest request = createRequest(
                NativeTestServer.getEchoMethodURL(), listener);
        request.setUploadData("text/plain", UPLOAD_DATA.getBytes("UTF8"));
        request.start();
        listener.blockForComplete();

        assertEquals(200, listener.mHttpStatusCode);
        assertEquals("POST", listener.mResponseAsString);
    }

    /**
     * Tests methods can be set when uploading.
     */
    @SmallTest
    @Feature({"Cronet"})
    public void testUploadMethods() throws Exception {
        String uploadMethods[] = {"POST", "PUT"};
        for (String uploadMethod : uploadMethods) {
            TestHttpUrlRequestListener listener =
                    new TestHttpUrlRequestListener();
            HttpUrlRequest request = createRequest(
                    NativeTestServer.getEchoMethodURL(), listener);
            request.setHttpMethod(uploadMethod);
            request.setUploadData("text/plain", UPLOAD_DATA.getBytes("UTF8"));
            request.start();
            listener.blockForComplete();

            assertEquals(200, listener.mHttpStatusCode);
            assertEquals(uploadMethod, listener.mResponseAsString);
        }
    }

    /**
     * Tests uploading from a channel.
     */
    @SmallTest
    @Feature({"Cronet"})
    public void testUploadChannel() throws Exception {
        TestHttpUrlRequestListener listener = new TestHttpUrlRequestListener();
        HttpUrlRequest request = createRequest(
                NativeTestServer.getEchoBodyURL(), listener);
        setUploadChannel(request, "text/plain", UPLOAD_CHANNEL_DATA,
                         UPLOAD_CHANNEL_DATA.length());
        request.start();
        listener.blockForComplete();

        assertEquals(200, listener.mHttpStatusCode);
        assertEquals(UPLOAD_CHANNEL_DATA, listener.mResponseAsString);
    }

    /**
     * Tests uploading from a channel in the case a redirect preserves the post
     * body.  Since channels can't be rewound, the request fails when we try to
     * rewind it to send the second request.
     */
    @SmallTest
    @Feature({"Cronet"})
    public void testUploadChannelWithRedirect() throws Exception {
        TestHttpUrlRequestListener listener = new TestHttpUrlRequestListener();
        HttpUrlRequest request = createRequest(
                NativeTestServer.getRedirectToEchoBody(), listener);
        setUploadChannel(request, "text/plain", UPLOAD_CHANNEL_DATA,
                         UPLOAD_CHANNEL_DATA.length());
        request.start();
        listener.blockForComplete();

        assertEquals(0, listener.mHttpStatusCode);
        assertEquals(
                "System error: net::ERR_UPLOAD_STREAM_REWIND_NOT_SUPPORTED(-25)",
                listener.mException.getMessage());
    }

    /**
     * Tests uploading from a channel when there's a read error.  The body
     * should be 0-padded.
     */
    @SmallTest
    @Feature({"Cronet"})
    public void testUploadChannelWithReadError() throws Exception {
        TestHttpUrlRequestListener listener = new TestHttpUrlRequestListener();
        HttpUrlRequest request = createRequest(
                NativeTestServer.getEchoBodyURL(), listener);
        setUploadChannel(request, "text/plain", UPLOAD_CHANNEL_DATA,
                         UPLOAD_CHANNEL_DATA.length() + 2);
        request.start();
        listener.blockForComplete();

        assertEquals(0, listener.mHttpStatusCode);
        assertNull(listener.mResponseAsString);
    }

    /**
     * Tests Content-Type can be set when uploading from a channel.
     */
    @SmallTest
    @Feature({"Cronet"})
    public void testUploadChannelContentType() throws Exception {
        String contentTypes[] = {"text/plain", "chicken/spicy"};
        for (String contentType : contentTypes) {
            TestHttpUrlRequestListener listener =
                    new TestHttpUrlRequestListener();
            HttpUrlRequest request = createRequest(
                    NativeTestServer.getEchoHeaderURL("Content-Type"),
                                                      listener);
            setUploadChannel(request, contentType, UPLOAD_CHANNEL_DATA,
                             UPLOAD_CHANNEL_DATA.length());
            request.start();
            listener.blockForComplete();

            assertEquals(200, listener.mHttpStatusCode);
            assertEquals(contentType, listener.mResponseAsString);
        }
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testAppendChunkRaceWithCancel() throws Exception {
        ByteBuffer byteBuffer = ByteBuffer.allocateDirect(1024);
        byteBuffer.put(UPLOAD_DATA.getBytes());
        byteBuffer.position(0);

        // Try to recreate race described in crbug.com/434855 when request
        // is canceled from another thread while adding chunks to upload.
        for (int test = 0; test < 100; ++test) {
            TestHttpUrlRequestListener listener =
                    new TestHttpUrlRequestListener();
            final ChromiumUrlRequest request =
                    (ChromiumUrlRequest) createRequest("http://127.0.0.1:8000",
                                                       listener);
            request.setChunkedUpload("dangerous/crocodile");
            request.start();
            Runnable cancelTask = new Runnable() {
                public void run() {
                    request.cancel();
                }
            };
            Executors.newCachedThreadPool().execute(cancelTask);
            try {
                request.appendChunk(byteBuffer, false);
                request.appendChunk(byteBuffer, false);
                request.appendChunk(byteBuffer, false);
                request.appendChunk(byteBuffer, true);
                // IOException may be thrown if appendChunk detects that request
                // is already destroyed.
            } catch (IOException e) {
                assertEquals("Native peer destroyed.", e.getMessage());
            }
            listener.blockForComplete();
        }
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testAppendChunkPreAndPost() throws Exception {
        ByteBuffer byteBuffer = ByteBuffer.allocateDirect(1024);
        byteBuffer.put(UPLOAD_DATA.getBytes());
        byteBuffer.position(0);

        TestHttpUrlRequestListener listener = new TestHttpUrlRequestListener();
        ChromiumUrlRequest request = (ChromiumUrlRequest) createRequest(
                NativeTestServer.getEchoBodyURL(), listener);
        request.setChunkedUpload("dangerous/crocodile");
        try {
            request.appendChunk(byteBuffer, false);
            fail("Exception not thrown.");
        } catch (IllegalStateException e) {
            assertEquals("Request not yet started.", e.getMessage());
        }
        request.start();
        request.appendChunk(byteBuffer, true);
        listener.blockForComplete();
        try {
            request.appendChunk(byteBuffer, true);
            fail("Exception not thrown.");
        } catch (IOException e) {
            assertEquals("Native peer destroyed.", e.getMessage());
        }
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testAppendChunkEmptyChunk() throws Exception {
        ByteBuffer byteBuffer = ByteBuffer.allocateDirect(1024);
        byteBuffer.put(UPLOAD_DATA.getBytes());
        byteBuffer.flip();

        TestHttpUrlRequestListener listener = new TestHttpUrlRequestListener();
        ChromiumUrlRequest request = (ChromiumUrlRequest) createRequest(
                NativeTestServer.getEchoBodyURL(), listener);
        request.setChunkedUpload("dangerous/crocodile");
        request.start();

        // Upload one non-empty followed by one empty chunk.
        request.appendChunk(byteBuffer, false);
        byteBuffer.position(0);
        byteBuffer.limit(0);
        request.appendChunk(byteBuffer, true);

        listener.blockForComplete();

        assertEquals(200, listener.mHttpStatusCode);
        assertEquals(UPLOAD_DATA, listener.mResponseAsString);
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testAppendChunkEmptyBody() throws Exception {
        TestHttpUrlRequestListener listener = new TestHttpUrlRequestListener();
        ChromiumUrlRequest request = (ChromiumUrlRequest) createRequest(
                NativeTestServer.getEchoBodyURL(), listener);
        request.setChunkedUpload("dangerous/crocodile");
        request.start();

        // Upload a single empty chunk.
        ByteBuffer byteBuffer = ByteBuffer.allocateDirect(0);
        request.appendChunk(byteBuffer, true);

        listener.blockForComplete();

        assertEquals(200, listener.mHttpStatusCode);
        assertEquals("", listener.mResponseAsString);
    }
}
