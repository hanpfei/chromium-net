// Copyright 2016 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.net;

import android.test.suitebuilder.annotation.SmallTest;

import org.chromium.base.FileUtils;
import org.chromium.base.PathUtils;
import org.chromium.base.test.util.Feature;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.util.Arrays;

/**
 * Test CronetEngine disk storage.
 */
public class DiskStorageTest extends CronetTestBase {
    private CronetTestFramework mTestFramework;
    private String mReadOnlyStoragePath;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        System.loadLibrary("cronet_tests");
        assertTrue(NativeTestServer.startNativeTestServer(getContext()));
    }

    @Override
    protected void tearDown() throws Exception {
        if (mReadOnlyStoragePath != null) {
            FileUtils.recursivelyDeleteFile(new File(mReadOnlyStoragePath));
        }
        NativeTestServer.shutdownNativeTestServer();
        super.tearDown();
    }

    @SmallTest
    @Feature({"Cronet"})
    @OnlyRunNativeCronet
    // Crashing on Android Cronet Builder, see crbug.com/601409.
    public void testReadOnlyStorageDirectory() throws Exception {
        mReadOnlyStoragePath = PathUtils.getDataDirectory(getContext()) + "/read_only";
        File readOnlyStorage = new File(mReadOnlyStoragePath);
        assertTrue(readOnlyStorage.mkdir());
        // Setting the storage directory as readonly has no effect.
        assertTrue(readOnlyStorage.setReadOnly());
        CronetEngine.Builder builder = new CronetEngine.Builder(getContext());
        builder.setStoragePath(mReadOnlyStoragePath);
        builder.enableHttpCache(CronetEngine.Builder.HTTP_CACHE_DISK, 1024 * 1024);

        mTestFramework = new CronetTestFramework(null, null, getContext(), builder);
        TestUrlRequestCallback callback = new TestUrlRequestCallback();
        String url = NativeTestServer.getFileURL("/cacheable.txt");
        UrlRequest.Builder requestBuilder = new UrlRequest.Builder(
                url, callback, callback.getExecutor(), mTestFramework.mCronetEngine);
        UrlRequest urlRequest = requestBuilder.build();
        urlRequest.start();
        callback.blockForDone();
        assertEquals(200, callback.mResponseInfo.getHttpStatusCode());
        mTestFramework.mCronetEngine.shutdown();
        FileInputStream newVersionFile = null;
        // Make sure that version file is in readOnlyStoragePath.
        File versionFile = new File(mReadOnlyStoragePath + "/version");
        try {
            newVersionFile = new FileInputStream(versionFile);
            byte[] buffer = new byte[] {0, 0, 0, 0};
            int bytesRead = newVersionFile.read(buffer, 0, 4);
            assertEquals(4, bytesRead);
            assertTrue(Arrays.equals(new byte[] {1, 0, 0, 0}, buffer));
        } finally {
            if (newVersionFile != null) {
                newVersionFile.close();
            }
        }
        File diskCacheDir = new File(mReadOnlyStoragePath + "/disk_cache");
        assertTrue(diskCacheDir.exists());
        File prefsDir = new File(mReadOnlyStoragePath + "/prefs");
        assertTrue(prefsDir.exists());
    }

    @SmallTest
    @Feature({"Cronet"})
    @OnlyRunNativeCronet
    // Crashing on Android Cronet Builder, see crbug.com/601409.
    public void testPurgeOldVersion() throws Exception {
        String testStorage = CronetTestFramework.getTestStorage(getContext());
        File versionFile = new File(testStorage + "/version");
        FileOutputStream versionOut = null;
        try {
            versionOut = new FileOutputStream(versionFile);
            versionOut.write(new byte[] {0, 0, 0, 0}, 0, 4);
        } finally {
            if (versionOut != null) {
                versionOut.close();
            }
        }
        File oldPrefsFile = new File(testStorage + "/local_prefs.json");
        FileOutputStream oldPrefsOut = null;
        try {
            oldPrefsOut = new FileOutputStream(oldPrefsFile);
            String dummy = "dummy content";
            oldPrefsOut.write(dummy.getBytes(), 0, dummy.length());
        } finally {
            if (oldPrefsOut != null) {
                oldPrefsOut.close();
            }
        }

        CronetEngine.Builder builder = new CronetEngine.Builder(getContext());
        builder.setStoragePath(CronetTestFramework.getTestStorage(getContext()));
        builder.enableHttpCache(CronetEngine.Builder.HTTP_CACHE_DISK, 1024 * 1024);

        mTestFramework = new CronetTestFramework(null, null, getContext(), builder);
        TestUrlRequestCallback callback = new TestUrlRequestCallback();
        String url = NativeTestServer.getFileURL("/cacheable.txt");
        UrlRequest.Builder requestBuilder = new UrlRequest.Builder(
                url, callback, callback.getExecutor(), mTestFramework.mCronetEngine);
        UrlRequest urlRequest = requestBuilder.build();
        urlRequest.start();
        callback.blockForDone();
        assertEquals(200, callback.mResponseInfo.getHttpStatusCode());
        mTestFramework.mCronetEngine.shutdown();
        FileInputStream newVersionFile = null;
        try {
            newVersionFile = new FileInputStream(versionFile);
            byte[] buffer = new byte[] {0, 0, 0, 0};
            int bytesRead = newVersionFile.read(buffer, 0, 4);
            assertEquals(4, bytesRead);
            assertTrue(Arrays.equals(new byte[] {1, 0, 0, 0}, buffer));
        } finally {
            if (newVersionFile != null) {
                newVersionFile.close();
            }
        }
        oldPrefsFile = new File(testStorage + "/local_prefs.json");
        assertTrue(!oldPrefsFile.exists());
        File diskCacheDir = new File(testStorage + "/disk_cache");
        assertTrue(diskCacheDir.exists());
        File prefsDir = new File(testStorage + "/prefs");
        assertTrue(prefsDir.exists());
    }

    @SmallTest
    @Feature({"Cronet"})
    @OnlyRunNativeCronet
    // Tests that if cache version is current, Cronet does not purge the directory.
    public void testCacheVersionCurrent() throws Exception {
        // Initialize a CronetEngine and shut it down.
        CronetEngine.Builder builder = new CronetEngine.Builder(getContext());
        builder.setStoragePath(CronetTestFramework.getTestStorage(getContext()));
        builder.enableHttpCache(CronetEngine.Builder.HTTP_CACHE_DISK, 1024 * 1024);

        mTestFramework = new CronetTestFramework(null, null, getContext(), builder);
        TestUrlRequestCallback callback = new TestUrlRequestCallback();
        String url = NativeTestServer.getFileURL("/cacheable.txt");
        UrlRequest.Builder requestBuilder = new UrlRequest.Builder(
                url, callback, callback.getExecutor(), mTestFramework.mCronetEngine);
        UrlRequest urlRequest = requestBuilder.build();
        urlRequest.start();
        callback.blockForDone();
        assertEquals(200, callback.mResponseInfo.getHttpStatusCode());
        mTestFramework.mCronetEngine.shutdown();

        // Create a dummy file in storage directory.
        String testStorage = CronetTestFramework.getTestStorage(getContext());
        File dummyFile = new File(testStorage + "/dummy.json");
        FileOutputStream dummyFileOut = null;
        String dummyContent = "dummy content";
        try {
            dummyFileOut = new FileOutputStream(dummyFile);
            dummyFileOut.write(dummyContent.getBytes(), 0, dummyContent.length());
        } finally {
            if (dummyFileOut != null) {
                dummyFileOut.close();
            }
        }

        // Creates a new CronetEngine and make a request.
        CronetEngine engine = builder.build();
        TestUrlRequestCallback callback2 = new TestUrlRequestCallback();
        String url2 = NativeTestServer.getFileURL("/cacheable.txt");
        UrlRequest.Builder requestBuilder2 =
                new UrlRequest.Builder(url2, callback2, callback2.getExecutor(), engine);
        UrlRequest urlRequest2 = requestBuilder2.build();
        urlRequest2.start();
        callback2.blockForDone();
        assertEquals(200, callback2.mResponseInfo.getHttpStatusCode());
        engine.shutdown();
        // Dummy file still exists.
        BufferedReader reader = new BufferedReader(new FileReader(dummyFile));
        StringBuilder stringBuilder = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            stringBuilder.append(line);
        }
        reader.close();
        assertEquals(dummyContent, stringBuilder.toString());
        File diskCacheDir = new File(testStorage + "/disk_cache");
        assertTrue(diskCacheDir.exists());
        File prefsDir = new File(testStorage + "/prefs");
        assertTrue(prefsDir.exists());
    }
}
