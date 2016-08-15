// Copyright 2016 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.net;

import android.os.ConditionVariable;
import android.os.ParcelFileDescriptor;
import android.os.StrictMode;
import android.test.suitebuilder.annotation.SmallTest;

import org.chromium.base.annotations.SuppressFBWarnings;
import org.chromium.base.test.util.Feature;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

/** Test the default provided implementations of {@link UploadDataProvider} */
public class UploadDataProvidersTest extends CronetTestBase {
    private static final String LOREM = "Lorem ipsum dolor sit amet, consectetur adipiscing elit. "
            + "Proin elementum, libero laoreet fringilla faucibus, metus tortor vehicula ante, "
            + "lacinia lorem eros vel sapien.";
    private CronetTestFramework mTestFramework;
    private File mFile;
    private StrictMode.VmPolicy mOldVmPolicy;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mOldVmPolicy = StrictMode.getVmPolicy();
        StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder()
                                       .detectLeakedClosableObjects()
                                       .penaltyLog()
                                       .penaltyDeath()
                                       .build());
        mTestFramework = startCronetTestFramework();
        assertTrue(NativeTestServer.startNativeTestServer(getContext()));
        // Add url interceptors after native application context is initialized.
        MockUrlRequestJobFactory.setUp();
        mFile = new File(getContext().getCacheDir().getPath() + "/tmpfile");
        FileOutputStream fileOutputStream = new FileOutputStream(mFile);
        try {
            fileOutputStream.write(LOREM.getBytes("UTF-8"));
        } finally {
            fileOutputStream.close();
        }
    }

    @SuppressFBWarnings("DM_GC") // Used to trigger strictmode detecting leaked closeables
    @Override
    protected void tearDown() throws Exception {
        try {
            NativeTestServer.shutdownNativeTestServer();
            mTestFramework.mCronetEngine.shutdown();
            assertTrue(mFile.delete());
            // Run GC and finalizers a few times to pick up leaked closeables
            for (int i = 0; i < 10; i++) {
                System.gc();
                System.runFinalization();
            }
            System.gc();
            System.runFinalization();
            super.tearDown();
        } finally {
            StrictMode.setVmPolicy(mOldVmPolicy);
        }
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testFileProvider() throws Exception {
        TestUrlRequestCallback callback = new TestUrlRequestCallback();
        UrlRequest.Builder builder =
                new UrlRequest.Builder(NativeTestServer.getRedirectToEchoBody(), callback,
                        callback.getExecutor(), mTestFramework.mCronetEngine);
        UploadDataProvider dataProvider = UploadDataProviders.create(mFile);
        builder.setUploadDataProvider(dataProvider, callback.getExecutor());
        builder.addHeader("Content-Type", "useless/string");
        builder.build().start();
        callback.blockForDone();
        assertEquals(200, callback.mResponseInfo.getHttpStatusCode());
        assertEquals(LOREM, callback.mResponseAsString);
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testFileDescriptorProvider() throws Exception {
        ParcelFileDescriptor descriptor =
                ParcelFileDescriptor.open(mFile, ParcelFileDescriptor.MODE_READ_ONLY);
        assertTrue(descriptor.getFileDescriptor().valid());
        TestUrlRequestCallback callback = new TestUrlRequestCallback();
        UrlRequest.Builder builder =
                new UrlRequest.Builder(NativeTestServer.getRedirectToEchoBody(), callback,
                        callback.getExecutor(), mTestFramework.mCronetEngine);
        UploadDataProvider dataProvider = UploadDataProviders.create(descriptor);
        builder.setUploadDataProvider(dataProvider, callback.getExecutor());
        builder.addHeader("Content-Type", "useless/string");
        builder.build().start();
        callback.blockForDone();
        assertEquals(200, callback.mResponseInfo.getHttpStatusCode());
        assertEquals(LOREM, callback.mResponseAsString);
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testBadFileDescriptorProvider() throws Exception {
        TestUrlRequestCallback callback = new TestUrlRequestCallback();
        UrlRequest.Builder builder =
                new UrlRequest.Builder(NativeTestServer.getRedirectToEchoBody(), callback,
                        callback.getExecutor(), mTestFramework.mCronetEngine);
        ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        try {
            UploadDataProvider dataProvider = UploadDataProviders.create(pipe[0]);
            builder.setUploadDataProvider(dataProvider, callback.getExecutor());
            builder.addHeader("Content-Type", "useless/string");
            builder.build().start();
            callback.blockForDone();

            assertTrue(callback.mError.getCause() instanceof IllegalArgumentException);
        } finally {
            pipe[1].close();
        }
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testBufferProvider() throws Exception {
        TestUrlRequestCallback callback = new TestUrlRequestCallback();
        UrlRequest.Builder builder =
                new UrlRequest.Builder(NativeTestServer.getRedirectToEchoBody(), callback,
                        callback.getExecutor(), mTestFramework.mCronetEngine);
        UploadDataProvider dataProvider = UploadDataProviders.create(LOREM.getBytes("UTF-8"));
        builder.setUploadDataProvider(dataProvider, callback.getExecutor());
        builder.addHeader("Content-Type", "useless/string");
        builder.build().start();
        callback.blockForDone();

        assertEquals(200, callback.mResponseInfo.getHttpStatusCode());
        assertEquals(LOREM, callback.mResponseAsString);
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testNoErrorWhenCanceledDuringStart() throws Exception {
        TestUrlRequestCallback callback = new TestUrlRequestCallback();
        UrlRequest.Builder builder = new UrlRequest.Builder(NativeTestServer.getEchoBodyURL(),
                callback, callback.getExecutor(), mTestFramework.mCronetEngine);
        final ConditionVariable first = new ConditionVariable();
        final ConditionVariable second = new ConditionVariable();
        builder.addHeader("Content-Type", "useless/string");
        builder.setUploadDataProvider(new UploadDataProvider() {
            @Override
            public long getLength() throws IOException {
                first.open();
                second.block();
                return 0;
            }

            @Override
            public void read(UploadDataSink uploadDataSink, ByteBuffer byteBuffer)
                    throws IOException {}

            @Override
            public void rewind(UploadDataSink uploadDataSink) throws IOException {}
        }, callback.getExecutor());
        UrlRequest urlRequest = builder.build();
        urlRequest.start();
        first.block();
        urlRequest.cancel();
        second.open();
        callback.blockForDone();
        assertTrue(callback.mOnCanceledCalled);
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testNoErrorWhenExceptionDuringStart() throws Exception {
        TestUrlRequestCallback callback = new TestUrlRequestCallback();
        UrlRequest.Builder builder = new UrlRequest.Builder(NativeTestServer.getEchoBodyURL(),
                callback, callback.getExecutor(), mTestFramework.mCronetEngine);
        final ConditionVariable first = new ConditionVariable();
        final String exceptionMessage = "Bad Length";
        builder.addHeader("Content-Type", "useless/string");
        builder.setUploadDataProvider(new UploadDataProvider() {
            @Override
            public long getLength() throws IOException {
                first.open();
                throw new IOException(exceptionMessage);
            }

            @Override
            public void read(UploadDataSink uploadDataSink, ByteBuffer byteBuffer)
                    throws IOException {}

            @Override
            public void rewind(UploadDataSink uploadDataSink) throws IOException {}
        }, callback.getExecutor());
        UrlRequest urlRequest = builder.build();
        urlRequest.start();
        first.block();
        callback.blockForDone();
        assertFalse(callback.mOnCanceledCalled);
        assertEquals(UrlRequestError.LISTENER_EXCEPTION_THROWN, callback.mError.getErrorCode());
        assertEquals("Exception received from UploadDataProvider", callback.mError.getMessage());
        assertEquals(exceptionMessage, callback.mError.getCause().getMessage());
    }

    @SmallTest
    @Feature({"Cronet"})
    // Tests that creating a ByteBufferUploadProvider using a byte array with an
    // offset gives a ByteBuffer with position 0. crbug.com/603124.
    public void testCreateByteBufferUploadWithArrayOffset() throws Exception {
        TestUrlRequestCallback callback = new TestUrlRequestCallback();
        // This URL will trigger a rewind().
        UrlRequest.Builder builder =
                new UrlRequest.Builder(NativeTestServer.getRedirectToEchoBody(), callback,
                        callback.getExecutor(), mTestFramework.mCronetEngine);
        builder.addHeader("Content-Type", "useless/string");
        byte[] uploadData = LOREM.getBytes("UTF-8");
        int offset = 5;
        byte[] uploadDataWithPadding = new byte[uploadData.length + offset];
        System.arraycopy(uploadData, 0, uploadDataWithPadding, offset, uploadData.length);
        UploadDataProvider dataProvider =
                UploadDataProviders.create(uploadDataWithPadding, offset, uploadData.length);
        assertEquals(uploadData.length, dataProvider.getLength());
        builder.setUploadDataProvider(dataProvider, callback.getExecutor());
        UrlRequest urlRequest = builder.build();
        urlRequest.start();
        callback.blockForDone();
        assertEquals(200, callback.mResponseInfo.getHttpStatusCode());
        assertEquals(LOREM, callback.mResponseAsString);
    }
}
