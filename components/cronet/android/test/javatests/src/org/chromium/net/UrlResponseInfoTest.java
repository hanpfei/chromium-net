// Copyright 2015 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.net;

import static junit.framework.Assert.assertEquals;

import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;

import org.chromium.base.test.util.Feature;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Tests for {@link UrlResponseInfo}.
 */
public class UrlResponseInfoTest extends AndroidTestCase {
    /**
     * Test for public API of {@link UrlResponseInfo}.
     */
    @SmallTest
    @Feature({"Cronet"})
    public void testPublicAPI() throws Exception {
        final List<String> urlChain = new ArrayList<String>();
        urlChain.add("chromium.org");
        final int httpStatusCode = 200;
        final String httpStatusText = "OK";
        final List<Map.Entry<String, String>> allHeadersList =
                new ArrayList<Map.Entry<String, String>>();
        allHeadersList.add(new AbstractMap.SimpleImmutableEntry<String, String>(
                "Date", "Fri, 30 Oct 2015 14:26:41 GMT"));
        final boolean wasCached = true;
        final String negotiatedProtocol = "quic/1+spdy/3";
        final String proxyServer = "example.com";

        final UrlResponseInfo info = new UrlResponseInfo(urlChain, httpStatusCode, httpStatusText,
                allHeadersList, wasCached, negotiatedProtocol, proxyServer);
        assertEquals(info.getUrlChain(), urlChain);
        try {
            info.getUrlChain().add("example.com");
            fail("getUrlChain() returned modifyable list.");
        } catch (UnsupportedOperationException e) {
            // Expected.
        }
        assertEquals(info.getHttpStatusCode(), httpStatusCode);
        assertEquals(info.getHttpStatusText(), httpStatusText);
        assertEquals(info.getAllHeadersAsList(), allHeadersList);
        try {
            info.getAllHeadersAsList().add(
                    new AbstractMap.SimpleImmutableEntry<String, String>("X", "Y"));
            fail("getAllHeadersAsList() returned modifyable list.");
        } catch (UnsupportedOperationException e) {
            // Expected.
        }
        assertEquals(info.getAllHeaders().size(), allHeadersList.size());
        assertEquals(info.getAllHeaders().get(allHeadersList.get(0).getKey()).size(), 1);
        assertEquals(info.getAllHeaders().get(allHeadersList.get(0).getKey()).get(0),
                allHeadersList.get(0).getValue());
        assertEquals(info.wasCached(), wasCached);
        assertEquals(info.getNegotiatedProtocol(), negotiatedProtocol);
        assertEquals(info.getProxyServer(), proxyServer);
    }
}