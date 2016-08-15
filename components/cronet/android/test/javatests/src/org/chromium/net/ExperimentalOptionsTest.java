// Copyright 2016 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.net;

import android.test.suitebuilder.annotation.SmallTest;

import org.chromium.base.PathUtils;
import org.chromium.base.test.util.Feature;
import org.chromium.net.CronetTestBase.OnlyRunNativeCronet;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

/**
 * Tests for experimental options.
 */
public class ExperimentalOptionsTest extends CronetTestBase {
    private static final String TAG = "cr.QuicTest";
    private CronetTestFramework mTestFramework;
    private CronetEngine.Builder mBuilder;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        System.loadLibrary("cronet_tests");
        mBuilder = new CronetEngine.Builder(getContext());
        mBuilder.setMockCertVerifierForTesting(QuicTestServer.createMockCertVerifier());
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

    @SmallTest
    @Feature({"Cronet"})
    @OnlyRunNativeCronet
    public void testSetSSLKeyLogFile() throws Exception {
        String url = Http2TestServer.getEchoMethodUrl();
        File dir = new File(PathUtils.getDataDirectory(getContext()));
        File file = File.createTempFile("ssl_key_log_file", "", dir);

        JSONObject experimentalOptions = new JSONObject().put("ssl_key_log_file", file.getPath());
        mBuilder.setExperimentalOptions(experimentalOptions.toString());
        mTestFramework = new CronetTestFramework(null, null, getContext(), mBuilder);

        TestUrlRequestCallback callback = new TestUrlRequestCallback();
        UrlRequest.Builder builder = new UrlRequest.Builder(
                url, callback, callback.getExecutor(), mTestFramework.mCronetEngine);
        UrlRequest urlRequest = builder.build();
        urlRequest.start();
        callback.blockForDone();
        assertEquals(200, callback.mResponseInfo.getHttpStatusCode());
        assertEquals("GET", callback.mResponseAsString);

        assertTrue(file.exists());
        assertTrue(file.length() != 0);
        BufferedReader logReader = new BufferedReader(new FileReader(file));
        boolean validFile = false;
        try {
            String logLine;
            while ((logLine = logReader.readLine()) != null) {
                if (logLine.contains("CLIENT_RANDOM")) {
                    validFile = true;
                    break;
                }
            }
        } finally {
            logReader.close();
        }
        assertTrue(validFile);
        assertTrue(file.delete());
        assertTrue(!file.exists());
    }
}
