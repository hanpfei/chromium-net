// Copyright 2014 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.net;

import android.os.ConditionVariable;
import android.util.Log;

import java.util.List;
import java.util.Map;

/**
 * A HttpUrlRequestListener that saves the response from a HttpUrlRequest.
 * This class is used in testing.
 */
@SuppressWarnings("deprecation")
public class TestHttpUrlRequestListener implements HttpUrlRequestListener {
    public static final String TAG = "TestHttpUrlRequestListener";

    public int mHttpStatusCode = 0;
    public String mHttpStatusText;
    public String mNegotiatedProtocol;
    public String mUrl;
    public byte[] mResponseAsBytes;
    public String mResponseAsString;
    public Exception mException;
    public Map<String, List<String>> mResponseHeaders;

    private final ConditionVariable mStarted = new ConditionVariable();
    private final ConditionVariable mComplete = new ConditionVariable();

    public TestHttpUrlRequestListener() {
    }

    @Override
    public void onResponseStarted(HttpUrlRequest request) {
        Log.i(TAG, "****** Response Started, content length is "
                + request.getContentLength());
        Log.i(TAG, "*** Headers Are *** " + request.getAllHeaders());
        mHttpStatusCode = request.getHttpStatusCode();
        mHttpStatusText = request.getHttpStatusText();
        mNegotiatedProtocol = request.getNegotiatedProtocol();
        mResponseHeaders = request.getAllHeaders();
        mStarted.open();
    }

    @Override
    public void onRequestComplete(HttpUrlRequest request) {
        mUrl = request.getUrl();
        mException = request.getException();
        if (mException != null) {
            // When there is an exception, onResponseStarted is often not
            // invoked (e.g. when request fails or redirects are disabled).
            // Populate status code and text in this case.
            mHttpStatusCode = request.getHttpStatusCode();
            mHttpStatusText = request.getHttpStatusText();
            if (mException.getMessage().equals("Request failed "
                    + "because there were too many redirects or redirects have "
                    + "been disabled")) {
                mResponseHeaders = request.getAllHeaders();
            }
        } else {
            // Read the response body if there is not an exception.
            mResponseAsBytes = request.getResponseAsBytes();
            mResponseAsString = new String(mResponseAsBytes);
        }
        mComplete.open();
        Log.i(TAG, "****** Request Complete over " + mNegotiatedProtocol
                + ", status code is " + mHttpStatusCode);
    }

    /**
     * Blocks until the response starts.
     */
    public void blockForStart() {
        mStarted.block();
    }

    /**
     * Blocks until the request completes.
     */
    public void blockForComplete() {
        mComplete.block();
    }

    public void resetComplete() {
        mComplete.close();
    }
}
