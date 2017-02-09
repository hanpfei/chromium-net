// Copyright 2014 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.net;

import android.content.Context;
import android.content.ContextWrapper;
import android.os.ConditionVariable;
import android.os.Handler;
import android.os.Looper;
import android.os.StrictMode;
import android.test.suitebuilder.annotation.SmallTest;

import org.chromium.base.PathUtils;
import org.chromium.base.annotations.JNINamespace;
import org.chromium.base.test.util.Feature;
import org.chromium.base.test.util.FlakyTest;
import org.chromium.net.TestUrlRequestCallback.ResponseStep;
import org.chromium.net.impl.CronetLibraryLoader;
import org.chromium.net.impl.CronetUrlRequestContext;
import org.chromium.net.test.EmbeddedTestServer;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.NoSuchElementException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Test CronetEngine.
 */
@JNINamespace("cronet")
public class CronetUrlRequestContextTest extends CronetTestBase {
    // URLs used for tests.
    private static final String MOCK_CRONET_TEST_FAILED_URL =
            "http://mock.failed.request/-2";
    private static final String MOCK_CRONET_TEST_SUCCESS_URL =
            "http://mock.http/success.txt";

    private EmbeddedTestServer mTestServer;
    private String mUrl;
    private String mUrl404;
    private String mUrl500;
    CronetTestFramework mTestFramework;

    // Thread on which network quality listeners should be notified.
    private Thread mNetworkQualityThread;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mTestServer = EmbeddedTestServer.createAndStartDefaultServer(getContext());
        mUrl = mTestServer.getURL("/echo?status=200");
        mUrl404 = mTestServer.getURL("/echo?status=404");
        mUrl500 = mTestServer.getURL("/echo?status=500");
    }

    @Override
    protected void tearDown() throws Exception {
        mTestServer.stopAndDestroyServer();
        super.tearDown();
    }

    static class RequestThread extends Thread {
        public TestUrlRequestCallback mCallback;

        final CronetTestFramework mTestFramework;
        final String mUrl;
        final ConditionVariable mRunBlocker;

        public RequestThread(
                CronetTestFramework testFramework, String url, ConditionVariable runBlocker) {
            mTestFramework = testFramework;
            mUrl = url;
            mRunBlocker = runBlocker;
        }

        @Override
        public void run() {
            mRunBlocker.block();
            CronetEngine cronetEngine = mTestFramework.initCronetEngine();
            mCallback = new TestUrlRequestCallback();
            UrlRequest.Builder urlRequestBuilder =
                    new UrlRequest.Builder(mUrl, mCallback, mCallback.getExecutor(), cronetEngine);
            urlRequestBuilder.build().start();
            mCallback.blockForDone();
        }
    }

    /**
     * Callback that shutdowns the request context when request has succeeded
     * or failed.
     */
    class ShutdownTestUrlRequestCallback extends TestUrlRequestCallback {
        @Override
        public void onSucceeded(UrlRequest request, UrlResponseInfo info) {
            super.onSucceeded(request, info);
            mTestFramework.mCronetEngine.shutdown();
        }

        @Override
        public void onFailed(UrlRequest request, UrlResponseInfo info, UrlRequestException error) {
            super.onFailed(request, info, error);
            mTestFramework.mCronetEngine.shutdown();
        }
    }

    static class TestExecutor implements Executor {
        private final LinkedList<Runnable> mTaskQueue = new LinkedList<Runnable>();

        @Override
        public void execute(Runnable task) {
            mTaskQueue.add(task);
        }

        public void runAllTasks() {
            try {
                while (mTaskQueue.size() > 0) {
                    mTaskQueue.remove().run();
                }
            } catch (NoSuchElementException e) {
            }
        }
    }

    private class ExecutorThreadFactory implements ThreadFactory {
        public Thread newThread(final Runnable r) {
            mNetworkQualityThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    StrictMode.ThreadPolicy threadPolicy = StrictMode.getThreadPolicy();
                    try {
                        StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder()
                                                           .detectNetwork()
                                                           .penaltyLog()
                                                           .penaltyDeath()
                                                           .build());
                        r.run();
                    } finally {
                        StrictMode.setThreadPolicy(threadPolicy);
                    }
                }
            });
            return mNetworkQualityThread;
        }
    }

    @SmallTest
    @Feature({"Cronet"})
    @SuppressWarnings("deprecation")
    public void testConfigUserAgent() throws Exception {
        String userAgentName = "User-Agent";
        String userAgentValue = "User-Agent-Value";
        CronetEngine.Builder cronetEngineBuilder = new CronetEngine.Builder(getContext());
        if (testingJavaImpl()) {
            cronetEngineBuilder.enableLegacyMode(true);
        }
        cronetEngineBuilder.setUserAgent(userAgentValue);
        cronetEngineBuilder.setLibraryName("cronet_tests");
        mTestFramework =
                startCronetTestFrameworkWithUrlAndCronetEngineBuilder(mUrl, cronetEngineBuilder);
        NativeTestServer.shutdownNativeTestServer(); // startNativeTestServer returns false if it's
        // already running
        assertTrue(NativeTestServer.startNativeTestServer(getContext()));
        TestUrlRequestCallback callback = new TestUrlRequestCallback();
        UrlRequest.Builder urlRequestBuilder =
                new UrlRequest.Builder(NativeTestServer.getEchoHeaderURL(userAgentName), callback,
                        callback.getExecutor(), mTestFramework.mCronetEngine);
        urlRequestBuilder.build().start();
        callback.blockForDone();
        assertEquals(userAgentValue, callback.mResponseAsString);
    }

    @SmallTest
    @Feature({"Cronet"})
    @OnlyRunNativeCronet
    public void testDataReductionProxyEnabled() throws Exception {
        mTestFramework = startCronetTestFrameworkAndSkipLibraryInit();

        // Ensure native code is loaded before trying to start test server.
        new CronetEngine.Builder(getContext()).setLibraryName("cronet_tests").build().shutdown();

        assertTrue(NativeTestServer.startNativeTestServer(getContext()));
        if (!NativeTestServer.isDataReductionProxySupported()) {
            return;
        }
        String serverHostPort = NativeTestServer.getHostPort();

        // Enable the Data Reduction Proxy and configure it to use the test
        // server as its primary proxy, and to check successfully that this
        // proxy is OK to use.
        CronetEngine.Builder cronetEngineBuilder = new CronetEngine.Builder(getContext());
        cronetEngineBuilder.enableDataReductionProxy("test-key");
        cronetEngineBuilder.setDataReductionProxyOptions(serverHostPort, "unused.net:9999",
                NativeTestServer.getFileURL("/secureproxychecksuccess.txt"));
        cronetEngineBuilder.setLibraryName("cronet_tests");
        mTestFramework.mCronetEngine = cronetEngineBuilder.build();
        TestUrlRequestCallback callback = new TestUrlRequestCallback();

        // Construct and start a request that can only be returned by the test
        // server. This request will fail if the configuration logic for the
        // Data Reduction Proxy is not used.
        UrlRequest.Builder urlRequestBuilder = new UrlRequest.Builder(
                "http://DomainThatDoesnt.Resolve/datareductionproxysuccess.txt", callback,
                callback.getExecutor(), mTestFramework.mCronetEngine);
        urlRequestBuilder.build().start();
        callback.blockForDone();

        // Verify that the request is successful and that the Data Reduction
        // Proxy logic configured to use the test server as its proxy.
        assertEquals(200, callback.mResponseInfo.getHttpStatusCode());
        assertEquals(serverHostPort, callback.mResponseInfo.getProxyServer());
        assertEquals("http://DomainThatDoesnt.Resolve/datareductionproxysuccess.txt",
                callback.mResponseInfo.getUrl());
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testRealTimeNetworkQualityObservationsNotEnabled() throws Exception {
        CronetEngine.Builder mCronetEngineBuilder = new CronetEngine.Builder(getContext());
        mTestFramework =
                startCronetTestFrameworkWithUrlAndCronetEngineBuilder(null, mCronetEngineBuilder);
        Executor networkQualityExecutor = Executors.newSingleThreadExecutor();
        TestNetworkQualityRttListener rttListener =
                new TestNetworkQualityRttListener(networkQualityExecutor);
        TestNetworkQualityThroughputListener throughputListener =
                new TestNetworkQualityThroughputListener(networkQualityExecutor, null);
        try {
            mTestFramework.mCronetEngine.addRttListener(rttListener);
            fail("Should throw an exception.");
        } catch (IllegalStateException e) {
        }
        try {
            mTestFramework.mCronetEngine.addThroughputListener(throughputListener);
            fail("Should throw an exception.");
        } catch (IllegalStateException e) {
        }
        TestUrlRequestCallback callback = new TestUrlRequestCallback();
        UrlRequest.Builder builder = new UrlRequest.Builder(
                mUrl, callback, callback.getExecutor(), mTestFramework.mCronetEngine);
        UrlRequest urlRequest = builder.build();

        urlRequest.start();
        callback.blockForDone();
        assertEquals(0, rttListener.rttObservationCount());
        assertEquals(0, throughputListener.throughputObservationCount());
        mTestFramework.mCronetEngine.shutdown();
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testRealTimeNetworkQualityObservationsListenerRemoved() throws Exception {
        CronetEngine.Builder mCronetEngineBuilder = new CronetEngine.Builder(getContext());
        TestExecutor networkQualityExecutor = new TestExecutor();
        TestNetworkQualityRttListener rttListener =
                new TestNetworkQualityRttListener(networkQualityExecutor);
        mCronetEngineBuilder.enableNetworkQualityEstimator(true);
        mTestFramework =
                startCronetTestFrameworkWithUrlAndCronetEngineBuilder(null, mCronetEngineBuilder);
        mTestFramework.mCronetEngine.configureNetworkQualityEstimatorForTesting(true, true);

        mTestFramework.mCronetEngine.addRttListener(rttListener);
        mTestFramework.mCronetEngine.removeRttListener(rttListener);
        TestUrlRequestCallback callback = new TestUrlRequestCallback();
        UrlRequest.Builder builder = new UrlRequest.Builder(
                mUrl, callback, callback.getExecutor(), mTestFramework.mCronetEngine);
        UrlRequest urlRequest = builder.build();
        urlRequest.start();
        callback.blockForDone();
        networkQualityExecutor.runAllTasks();
        assertEquals(0, rttListener.rttObservationCount());
        mTestFramework.mCronetEngine.shutdown();
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testRealTimeNetworkQualityObservationsQuicDisabled() throws Exception {
        CronetEngine.Builder mCronetEngineBuilder = new CronetEngine.Builder(getContext());
        Executor listenersExecutor = Executors.newSingleThreadExecutor(new ExecutorThreadFactory());
        ConditionVariable waitForThroughput = new ConditionVariable();
        TestNetworkQualityRttListener rttListener =
                new TestNetworkQualityRttListener(listenersExecutor);
        TestNetworkQualityThroughputListener throughputListener =
                new TestNetworkQualityThroughputListener(listenersExecutor, waitForThroughput);
        mCronetEngineBuilder.enableHttp2(true).enableQuic(false).enableNetworkQualityEstimator(
                true);
        mTestFramework =
                startCronetTestFrameworkWithUrlAndCronetEngineBuilder(null, mCronetEngineBuilder);
        mTestFramework.mCronetEngine.configureNetworkQualityEstimatorForTesting(true, true);

        mTestFramework.mCronetEngine.addRttListener(rttListener);
        mTestFramework.mCronetEngine.addThroughputListener(throughputListener);

        TestUrlRequestCallback callback = new TestUrlRequestCallback();
        UrlRequest.Builder builder = new UrlRequest.Builder(
                mUrl, callback, callback.getExecutor(), mTestFramework.mCronetEngine);
        UrlRequest urlRequest = builder.build();
        urlRequest.start();
        callback.blockForDone();

        // Throughput observation is posted to the network quality estimator on the network thread
        // after the UrlRequest is completed. The observations are then eventually posted to
        // throughput listeners on the executor provided to network quality.
        waitForThroughput.block();
        assertTrue(throughputListener.throughputObservationCount() > 0);

        // Check RTT observation count after throughput observation has been received. This ensures
        // that executor has finished posting the RTT observation to the RTT listeners.
        assertTrue(rttListener.rttObservationCount() > 0);

        // NETWORK_QUALITY_OBSERVATION_SOURCE_URL_REQUEST
        assertTrue(rttListener.rttObservationCount(0) > 0);

        // NETWORK_QUALITY_OBSERVATION_SOURCE_TCP
        assertTrue(rttListener.rttObservationCount(1) > 0);

        // NETWORK_QUALITY_OBSERVATION_SOURCE_QUIC
        assertEquals(0, rttListener.rttObservationCount(2));

        // Verify that the listeners were notified on the expected thread.
        assertEquals(mNetworkQualityThread, rttListener.getThread());
        assertEquals(mNetworkQualityThread, throughputListener.getThread());

        mTestFramework.mCronetEngine.shutdown();
    }



    /**
    @SmallTest
    @Feature({"Cronet"})
    https://crbug.com/596929
    */
    @FlakyTest
    public void testShutdown() throws Exception {
        mTestFramework = startCronetTestFramework();
        TestUrlRequestCallback callback = new ShutdownTestUrlRequestCallback();
        // Block callback when response starts to verify that shutdown fails
        // if there are active requests.
        callback.setAutoAdvance(false);
        UrlRequest.Builder urlRequestBuilder = new UrlRequest.Builder(
                mUrl, callback, callback.getExecutor(), mTestFramework.mCronetEngine);
        UrlRequest urlRequest = urlRequestBuilder.build();
        urlRequest.start();
        try {
            mTestFramework.mCronetEngine.shutdown();
            fail("Should throw an exception");
        } catch (Exception e) {
            assertEquals("Cannot shutdown with active requests.",
                         e.getMessage());
        }

        callback.waitForNextStep();
        assertEquals(ResponseStep.ON_RESPONSE_STARTED, callback.mResponseStep);
        try {
            mTestFramework.mCronetEngine.shutdown();
            fail("Should throw an exception");
        } catch (Exception e) {
            assertEquals("Cannot shutdown with active requests.",
                         e.getMessage());
        }
        callback.startNextRead(urlRequest);

        callback.waitForNextStep();
        assertEquals(ResponseStep.ON_READ_COMPLETED, callback.mResponseStep);
        try {
            mTestFramework.mCronetEngine.shutdown();
            fail("Should throw an exception");
        } catch (Exception e) {
            assertEquals("Cannot shutdown with active requests.",
                         e.getMessage());
        }

        // May not have read all the data, in theory. Just enable auto-advance
        // and finish the request.
        callback.setAutoAdvance(true);
        callback.startNextRead(urlRequest);
        callback.blockForDone();
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testShutdownDuringInit() throws Exception {
        final CronetTestFramework testFramework = startCronetTestFrameworkAndSkipLibraryInit();
        final ConditionVariable block = new ConditionVariable(false);

        // Post a task to main thread to block until shutdown is called to test
        // scenario when shutdown is called right after construction before
        // context is fully initialized on the main thread.
        Runnable blockingTask = new Runnable() {
            @Override
            public void run() {
                try {
                    block.block();
                } catch (Exception e) {
                    fail("Caught " + e.getMessage());
                }
            }
        };
        // Ensure that test is not running on the main thread.
        assertTrue(Looper.getMainLooper() != Looper.myLooper());
        new Handler(Looper.getMainLooper()).post(blockingTask);

        // Create new request context, but its initialization on the main thread
        // will be stuck behind blockingTask.
        final CronetEngine cronetEngine = testFramework.initCronetEngine();
        // Unblock the main thread, so context gets initialized and shutdown on
        // it.
        block.open();
        // Shutdown will wait for init to complete on main thread.
        cronetEngine.shutdown();
        // Verify that context is shutdown.
        try {
            cronetEngine.stopNetLog();
            fail("Should throw an exception.");
        } catch (Exception e) {
            assertEquals("Engine is shut down.", e.getMessage());
        }
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testInitAndShutdownOnMainThread() throws Exception {
        final CronetTestFramework testFramework = startCronetTestFrameworkAndSkipLibraryInit();
        final ConditionVariable block = new ConditionVariable(false);

        // Post a task to main thread to init and shutdown on the main thread.
        Runnable blockingTask = new Runnable() {
            @Override
            public void run() {
                // Create new request context, loading the library.
                final CronetEngine cronetEngine = testFramework.initCronetEngine();
                // Shutdown right after init.
                cronetEngine.shutdown();
                // Verify that context is shutdown.
                try {
                    cronetEngine.stopNetLog();
                    fail("Should throw an exception.");
                } catch (Exception e) {
                    assertEquals("Engine is shut down.", e.getMessage());
                }
                block.open();
            }
        };
        new Handler(Looper.getMainLooper()).post(blockingTask);
        // Wait for shutdown to complete on main thread.
        block.block();
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testMultipleShutdown() throws Exception {
        mTestFramework = startCronetTestFramework();
        try {
            mTestFramework.mCronetEngine.shutdown();
            mTestFramework.mCronetEngine.shutdown();
            fail("Should throw an exception");
        } catch (Exception e) {
            assertEquals("Engine is shut down.", e.getMessage());
        }
    }

    /*
    @SmallTest
    @Feature({"Cronet"})
    */
    @FlakyTest(message = "https://crbug.com/592444")
    public void testShutdownAfterError() throws Exception {
        mTestFramework = startCronetTestFramework();
        TestUrlRequestCallback callback = new ShutdownTestUrlRequestCallback();
        UrlRequest.Builder urlRequestBuilder = new UrlRequest.Builder(MOCK_CRONET_TEST_FAILED_URL,
                callback, callback.getExecutor(), mTestFramework.mCronetEngine);
        urlRequestBuilder.build().start();
        callback.blockForDone();
        assertTrue(callback.mOnErrorCalled);
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testShutdownAfterCancel() throws Exception {
        mTestFramework = startCronetTestFramework();
        TestUrlRequestCallback callback = new TestUrlRequestCallback();
        // Block callback when response starts to verify that shutdown fails
        // if there are active requests.
        callback.setAutoAdvance(false);
        UrlRequest.Builder urlRequestBuilder = new UrlRequest.Builder(
                mUrl, callback, callback.getExecutor(), mTestFramework.mCronetEngine);
        UrlRequest urlRequest = urlRequestBuilder.build();
        urlRequest.start();
        try {
            mTestFramework.mCronetEngine.shutdown();
            fail("Should throw an exception");
        } catch (Exception e) {
            assertEquals("Cannot shutdown with active requests.",
                         e.getMessage());
        }
        callback.waitForNextStep();
        assertEquals(ResponseStep.ON_RESPONSE_STARTED, callback.mResponseStep);
        urlRequest.cancel();
        mTestFramework.mCronetEngine.shutdown();
    }

    @SmallTest
    @Feature({"Cronet"})
    @OnlyRunNativeCronet // No netlogs for pure java impl
    public void testNetLog() throws Exception {
        Context context = getContext();
        File directory = new File(PathUtils.getDataDirectory(context));
        File file = File.createTempFile("cronet", "json", directory);
        CronetEngine cronetEngine = new CronetUrlRequestContext(
                new CronetEngine.Builder(context).setLibraryName("cronet_tests"));
        // Start NetLog immediately after the request context is created to make
        // sure that the call won't crash the app even when the native request
        // context is not fully initialized. See crbug.com/470196.
        cronetEngine.startNetLogToFile(file.getPath(), false);

        // Start a request.
        TestUrlRequestCallback callback = new TestUrlRequestCallback();
        UrlRequest.Builder urlRequestBuilder =
                new UrlRequest.Builder(mUrl, callback, callback.getExecutor(), cronetEngine);
        urlRequestBuilder.build().start();
        callback.blockForDone();
        cronetEngine.stopNetLog();
        assertTrue(file.exists());
        assertTrue(file.length() != 0);
        assertFalse(hasBytesInNetLog(file));
        assertTrue(file.delete());
        assertTrue(!file.exists());
    }

    @SmallTest
    @Feature({"Cronet"})
    @OnlyRunNativeCronet // No netlogs for pure java impl
    // Tests that if stopNetLog is not explicity called, CronetEngine.shutdown()
    // will take care of it. crbug.com/623701.
    public void testNoStopNetLog() throws Exception {
        Context context = getContext();
        File directory = new File(PathUtils.getDataDirectory(context));
        File file = File.createTempFile("cronet", "json", directory);
        CronetEngine cronetEngine = new CronetUrlRequestContext(
                new CronetEngine.Builder(context).setLibraryName("cronet_tests"));
        cronetEngine.startNetLogToFile(file.getPath(), false);

        // Start a request.
        TestUrlRequestCallback callback = new TestUrlRequestCallback();
        UrlRequest.Builder urlRequestBuilder =
                new UrlRequest.Builder(mUrl, callback, callback.getExecutor(), cronetEngine);
        urlRequestBuilder.build().start();
        callback.blockForDone();
        // Shut down the engine without calling stopNetLog.
        cronetEngine.shutdown();
        assertTrue(file.exists());
        assertTrue(file.length() != 0);
        assertFalse(hasBytesInNetLog(file));
        assertTrue(file.delete());
        assertTrue(!file.exists());
    }

    @SmallTest
    @Feature({"Cronet"})
    @OnlyRunNativeCronet
    // Tests that NetLog contains events emitted by all live CronetEngines.
    public void testNetLogContainEventsFromAllLiveEngines() throws Exception {
        Context context = getContext();
        File directory = new File(PathUtils.getDataDirectory(context));
        File file1 = File.createTempFile("cronet1", "json", directory);
        File file2 = File.createTempFile("cronet2", "json", directory);
        CronetEngine cronetEngine1 = new CronetUrlRequestContext(
                new CronetEngine.Builder(context).setLibraryName("cronet_tests"));
        CronetEngine cronetEngine2 = new CronetUrlRequestContext(
                new CronetEngine.Builder(context).setLibraryName("cronet_tests"));

        cronetEngine1.startNetLogToFile(file1.getPath(), false);
        cronetEngine2.startNetLogToFile(file2.getPath(), false);

        // Warm CronetEngine and make sure both CronetUrlRequestContexts are
        // initialized before testing the logs.
        makeRequestAndCheckStatus(cronetEngine1, mUrl, 200);
        makeRequestAndCheckStatus(cronetEngine2, mUrl, 200);

        // Use cronetEngine1 to make a request to mUrl404.
        makeRequestAndCheckStatus(cronetEngine1, mUrl404, 404);

        // Use cronetEngine2 to make a request to mUrl500.
        makeRequestAndCheckStatus(cronetEngine2, mUrl500, 500);

        cronetEngine1.stopNetLog();
        cronetEngine2.stopNetLog();
        assertTrue(file1.exists());
        assertTrue(file2.exists());
        // Make sure both files contain the two requests made separately using
        // different engines.
        assertTrue(containsStringInNetLog(file1, mUrl404));
        assertTrue(containsStringInNetLog(file1, mUrl500));
        assertTrue(containsStringInNetLog(file2, mUrl404));
        assertTrue(containsStringInNetLog(file2, mUrl500));
        assertTrue(file1.delete());
        assertTrue(file2.delete());
    }

    @SmallTest
    @Feature({"Cronet"})
    @OnlyRunNativeCronet
    // Tests that if CronetEngine is shut down when reading from disk cache,
    // there isn't a crash. See crbug.com/486120.
    public void testShutDownEngineWhenReadingFromDiskCache() throws Exception {
        enableCache(CronetEngine.Builder.HTTP_CACHE_DISK);
        String url = NativeTestServer.getFileURL("/cacheable.txt");
        // Make a request to a cacheable resource.
        checkRequestCaching(url, false);

        // Shut down the server.
        NativeTestServer.shutdownNativeTestServer();
        class CancelUrlRequestCallback extends TestUrlRequestCallback {
            @Override
            public void onResponseStarted(UrlRequest request, UrlResponseInfo info) {
                super.onResponseStarted(request, info);
                request.cancel();
                // Shut down CronetEngine immediately after request is destroyed.
                mTestFramework.mCronetEngine.shutdown();
            }

            @Override
            public void onSucceeded(UrlRequest request, UrlResponseInfo info) {
                // onSucceeded will not happen, because the request is canceled
                // after sending first read and the executor is single threaded.
                throw new RuntimeException("Unexpected");
            }

            @Override
            public void onFailed(
                    UrlRequest request, UrlResponseInfo info, UrlRequestException error) {
                throw new RuntimeException("Unexpected");
            }
        }
        CancelUrlRequestCallback callback = new CancelUrlRequestCallback();
        UrlRequest.Builder urlRequestBuilder = new UrlRequest.Builder(
                url, callback, callback.getExecutor(), mTestFramework.mCronetEngine);
        urlRequestBuilder.build().start();
        callback.blockForDone();
        assertEquals(200, callback.mResponseInfo.getHttpStatusCode());
        assertTrue(callback.mResponseInfo.wasCached());
        assertTrue(callback.mOnCanceledCalled);
    }

    @SmallTest
    @Feature({"Cronet"})
    @OnlyRunNativeCronet
    public void testNetLogAfterShutdown() throws Exception {
        mTestFramework = startCronetTestFramework();
        TestUrlRequestCallback callback = new TestUrlRequestCallback();
        UrlRequest.Builder urlRequestBuilder = new UrlRequest.Builder(
                mUrl, callback, callback.getExecutor(), mTestFramework.mCronetEngine);
        urlRequestBuilder.build().start();
        callback.blockForDone();
        mTestFramework.mCronetEngine.shutdown();

        File directory = new File(PathUtils.getDataDirectory(getContext()));
        File file = File.createTempFile("cronet", "json", directory);
        try {
            mTestFramework.mCronetEngine.startNetLogToFile(file.getPath(), false);
            fail("Should throw an exception.");
        } catch (Exception e) {
            assertEquals("Engine is shut down.", e.getMessage());
        }
        assertFalse(hasBytesInNetLog(file));
        assertTrue(file.delete());
        assertTrue(!file.exists());
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testNetLogStartMultipleTimes() throws Exception {
        mTestFramework = startCronetTestFramework();
        File directory = new File(PathUtils.getDataDirectory(getContext()));
        File file = File.createTempFile("cronet", "json", directory);
        // Start NetLog multiple times.
        mTestFramework.mCronetEngine.startNetLogToFile(file.getPath(), false);
        mTestFramework.mCronetEngine.startNetLogToFile(file.getPath(), false);
        mTestFramework.mCronetEngine.startNetLogToFile(file.getPath(), false);
        mTestFramework.mCronetEngine.startNetLogToFile(file.getPath(), false);
        // Start a request.
        TestUrlRequestCallback callback = new TestUrlRequestCallback();
        UrlRequest.Builder urlRequestBuilder = new UrlRequest.Builder(
                mUrl, callback, callback.getExecutor(), mTestFramework.mCronetEngine);
        urlRequestBuilder.build().start();
        callback.blockForDone();
        mTestFramework.mCronetEngine.stopNetLog();
        assertTrue(file.exists());
        assertTrue(file.length() != 0);
        assertFalse(hasBytesInNetLog(file));
        assertTrue(file.delete());
        assertTrue(!file.exists());
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testNetLogStopMultipleTimes() throws Exception {
        mTestFramework = startCronetTestFramework();
        File directory = new File(PathUtils.getDataDirectory(getContext()));
        File file = File.createTempFile("cronet", "json", directory);
        mTestFramework.mCronetEngine.startNetLogToFile(file.getPath(), false);
        // Start a request.
        TestUrlRequestCallback callback = new TestUrlRequestCallback();
        UrlRequest.Builder urlRequestBuilder = new UrlRequest.Builder(
                mUrl, callback, callback.getExecutor(), mTestFramework.mCronetEngine);
        urlRequestBuilder.build().start();
        callback.blockForDone();
        // Stop NetLog multiple times.
        mTestFramework.mCronetEngine.stopNetLog();
        mTestFramework.mCronetEngine.stopNetLog();
        mTestFramework.mCronetEngine.stopNetLog();
        mTestFramework.mCronetEngine.stopNetLog();
        mTestFramework.mCronetEngine.stopNetLog();
        assertTrue(file.exists());
        assertTrue(file.length() != 0);
        assertFalse(hasBytesInNetLog(file));
        assertTrue(file.delete());
        assertTrue(!file.exists());
    }

    @SmallTest
    @Feature({"Cronet"})
    @OnlyRunNativeCronet
    public void testNetLogWithBytes() throws Exception {
        Context context = getContext();
        File directory = new File(PathUtils.getDataDirectory(context));
        File file = File.createTempFile("cronet", "json", directory);
        CronetEngine cronetEngine = new CronetUrlRequestContext(
                new CronetEngine.Builder(context).setLibraryName("cronet_tests"));
        // Start NetLog with logAll as true.
        cronetEngine.startNetLogToFile(file.getPath(), true);
        // Start a request.
        TestUrlRequestCallback callback = new TestUrlRequestCallback();
        UrlRequest.Builder urlRequestBuilder =
                new UrlRequest.Builder(mUrl, callback, callback.getExecutor(), cronetEngine);
        urlRequestBuilder.build().start();
        callback.blockForDone();
        cronetEngine.stopNetLog();
        assertTrue(file.exists());
        assertTrue(file.length() != 0);
        assertTrue(hasBytesInNetLog(file));
        assertTrue(file.delete());
        assertTrue(!file.exists());
    }

    private boolean hasBytesInNetLog(File logFile) throws Exception {
        return containsStringInNetLog(logFile, "\"hex_encoded_bytes\"");
    }

    private boolean containsStringInNetLog(File logFile, String content) throws Exception {
        BufferedReader logReader = new BufferedReader(new FileReader(logFile));
        try {
            String logLine;
            while ((logLine = logReader.readLine()) != null) {
                if (logLine.contains(content)) {
                    return true;
                }
            }
            return false;
        } finally {
            logReader.close();
        }
    }

    /**
     * Helper method to make a request to {@code url}, wait for it to
     * complete, and check that the status code is the same as {@code expectedStatusCode}.
     */
    private void makeRequestAndCheckStatus(
            CronetEngine engine, String url, int expectedStatusCode) {
        TestUrlRequestCallback callback = new TestUrlRequestCallback();
        UrlRequest request =
                new UrlRequest.Builder(url, callback, callback.getExecutor(), engine).build();
        request.start();
        callback.blockForDone();
        assertEquals(expectedStatusCode, callback.mResponseInfo.getHttpStatusCode());
    }

    private void enableCache(int cacheType) throws Exception {
        String cacheTypeString = "";
        if (cacheType == CronetEngine.Builder.HTTP_CACHE_DISK) {
            cacheTypeString = CronetTestFramework.CACHE_DISK;
        } else if (cacheType == CronetEngine.Builder.HTTP_CACHE_DISK_NO_HTTP) {
            cacheTypeString = CronetTestFramework.CACHE_DISK_NO_HTTP;
        } else if (cacheType == CronetEngine.Builder.HTTP_CACHE_IN_MEMORY) {
            cacheTypeString = CronetTestFramework.CACHE_IN_MEMORY;
        }
        String[] commandLineArgs = {CronetTestFramework.CACHE_KEY, cacheTypeString};
        mTestFramework = startCronetTestFrameworkWithUrlAndCommandLineArgs(null, commandLineArgs);
        assertTrue(NativeTestServer.startNativeTestServer(getContext()));
    }

    private void checkRequestCaching(String url, boolean expectCached) {
        checkRequestCaching(url, expectCached, false);
    }

    private void checkRequestCaching(String url, boolean expectCached,
            boolean disableCache) {
        TestUrlRequestCallback callback = new TestUrlRequestCallback();
        UrlRequest.Builder urlRequestBuilder = new UrlRequest.Builder(
                url, callback, callback.getExecutor(), mTestFramework.mCronetEngine);
        if (disableCache) {
            urlRequestBuilder.disableCache();
        }
        urlRequestBuilder.build().start();
        callback.blockForDone();
        assertEquals(expectCached, callback.mResponseInfo.wasCached());
        assertEquals("this is a cacheable file\n", callback.mResponseAsString);
    }

    @SmallTest
    @Feature({"Cronet"})
    @OnlyRunNativeCronet
    public void testEnableHttpCacheDisabled() throws Exception {
        enableCache(CronetEngine.Builder.HTTP_CACHE_DISABLED);
        String url = NativeTestServer.getFileURL("/cacheable.txt");
        checkRequestCaching(url, false);
        checkRequestCaching(url, false);
        checkRequestCaching(url, false);
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testEnableHttpCacheInMemory() throws Exception {
        enableCache(CronetEngine.Builder.HTTP_CACHE_IN_MEMORY);
        String url = NativeTestServer.getFileURL("/cacheable.txt");
        checkRequestCaching(url, false);
        checkRequestCaching(url, true);
        NativeTestServer.shutdownNativeTestServer();
        checkRequestCaching(url, true);
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testEnableHttpCacheDisk() throws Exception {
        enableCache(CronetEngine.Builder.HTTP_CACHE_DISK);
        String url = NativeTestServer.getFileURL("/cacheable.txt");
        checkRequestCaching(url, false);
        checkRequestCaching(url, true);
        NativeTestServer.shutdownNativeTestServer();
        checkRequestCaching(url, true);
    }

    @SmallTest
    @Feature({"Cronet"})
    @OnlyRunNativeCronet
    public void testEnableHttpCacheDiskNoHttp() throws Exception {
        enableCache(CronetEngine.Builder.HTTP_CACHE_DISABLED);
        String url = NativeTestServer.getFileURL("/cacheable.txt");
        checkRequestCaching(url, false);
        checkRequestCaching(url, false);
        checkRequestCaching(url, false);
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testDisableCache() throws Exception {
        enableCache(CronetEngine.Builder.HTTP_CACHE_DISK);
        String url = NativeTestServer.getFileURL("/cacheable.txt");

        // When cache is disabled, making a request does not write to the cache.
        checkRequestCaching(url, false, true /** disable cache */);
        checkRequestCaching(url, false);

        // When cache is enabled, the second request is cached.
        checkRequestCaching(url, false, true /** disable cache */);
        checkRequestCaching(url, true);

        // Shut down the server, next request should have a cached response.
        NativeTestServer.shutdownNativeTestServer();
        checkRequestCaching(url, true);

        // Cache is disabled after server is shut down, request should fail.
        TestUrlRequestCallback callback = new TestUrlRequestCallback();
        UrlRequest.Builder urlRequestBuilder = new UrlRequest.Builder(
                url, callback, callback.getExecutor(), mTestFramework.mCronetEngine);
        urlRequestBuilder.disableCache();
        urlRequestBuilder.build().start();
        callback.blockForDone();
        assertNotNull(callback.mError);
        assertEquals("Exception in CronetUrlRequest: net::ERR_CONNECTION_REFUSED",
                callback.mError.getMessage());
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testEnableHttpCacheDiskNewEngine() throws Exception {
        enableCache(CronetEngine.Builder.HTTP_CACHE_DISK);
        String url = NativeTestServer.getFileURL("/cacheable.txt");
        checkRequestCaching(url, false);
        checkRequestCaching(url, true);
        NativeTestServer.shutdownNativeTestServer();
        checkRequestCaching(url, true);

        // Shutdown original context and create another that uses the same cache.
        mTestFramework.mCronetEngine.shutdown();
        mTestFramework.mCronetEngine = mTestFramework.getCronetEngineBuilder().build();
        checkRequestCaching(url, true);
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testInitEngineAndStartRequest() {
        CronetTestFramework testFramework = startCronetTestFrameworkAndSkipLibraryInit();

        // Immediately make a request after initializing the engine.
        CronetEngine cronetEngine = testFramework.initCronetEngine();
        TestUrlRequestCallback callback = new TestUrlRequestCallback();
        UrlRequest.Builder urlRequestBuilder =
                new UrlRequest.Builder(mUrl, callback, callback.getExecutor(), cronetEngine);
        urlRequestBuilder.build().start();
        callback.blockForDone();
        assertEquals(200, callback.mResponseInfo.getHttpStatusCode());
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testEmptyGetCertVerifierData() {
        CronetTestFramework testFramework = startCronetTestFrameworkAndSkipLibraryInit();

        // Immediately make a request after initializing the engine.
        CronetEngine cronetEngine = testFramework.initCronetEngine();
        TestUrlRequestCallback callback = new TestUrlRequestCallback();
        UrlRequest.Builder urlRequestBuilder =
                new UrlRequest.Builder(mUrl, callback, callback.getExecutor(), cronetEngine);
        urlRequestBuilder.build().start();
        callback.blockForDone();
        assertEquals(200, callback.mResponseInfo.getHttpStatusCode());

        try {
            cronetEngine.getCertVerifierData(-1);
            fail("Should throw an exception");
        } catch (Exception e) {
            assertEquals("timeout must be a positive value", e.getMessage());
        }
        // Because mUrl is http, getCertVerifierData() will return empty data.
        String data = cronetEngine.getCertVerifierData(100);
        assertTrue(data.isEmpty());
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testInitEngineStartTwoRequests() throws Exception {
        CronetTestFramework testFramework = startCronetTestFrameworkAndSkipLibraryInit();

        // Make two requests after initializing the context.
        CronetEngine cronetEngine = testFramework.initCronetEngine();
        int[] statusCodes = {0, 0};
        String[] urls = {mUrl, mUrl404};
        for (int i = 0; i < 2; i++) {
            TestUrlRequestCallback callback = new TestUrlRequestCallback();
            UrlRequest.Builder urlRequestBuilder =
                    new UrlRequest.Builder(urls[i], callback, callback.getExecutor(), cronetEngine);
            urlRequestBuilder.build().start();
            callback.blockForDone();
            statusCodes[i] = callback.mResponseInfo.getHttpStatusCode();
        }
        assertEquals(200, statusCodes[0]);
        assertEquals(404, statusCodes[1]);
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testInitTwoEnginesSimultaneously() throws Exception {
        final CronetTestFramework testFramework = startCronetTestFrameworkAndSkipLibraryInit();

        // Threads will block on runBlocker to ensure simultaneous execution.
        ConditionVariable runBlocker = new ConditionVariable(false);
        RequestThread thread1 = new RequestThread(testFramework, mUrl, runBlocker);
        RequestThread thread2 = new RequestThread(testFramework, mUrl404, runBlocker);

        thread1.start();
        thread2.start();
        runBlocker.open();
        thread1.join();
        thread2.join();
        assertEquals(200, thread1.mCallback.mResponseInfo.getHttpStatusCode());
        assertEquals(404, thread2.mCallback.mResponseInfo.getHttpStatusCode());
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testInitTwoEnginesInSequence() throws Exception {
        final CronetTestFramework testFramework = startCronetTestFrameworkAndSkipLibraryInit();

        ConditionVariable runBlocker = new ConditionVariable(true);
        RequestThread thread1 = new RequestThread(testFramework, mUrl, runBlocker);
        RequestThread thread2 = new RequestThread(testFramework, mUrl404, runBlocker);

        thread1.start();
        thread1.join();
        thread2.start();
        thread2.join();
        assertEquals(200, thread1.mCallback.mResponseInfo.getHttpStatusCode());
        assertEquals(404, thread2.mCallback.mResponseInfo.getHttpStatusCode());
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testInitDifferentEngines() throws Exception {
        // Test that concurrently instantiating Cronet context's upon various
        // different versions of the same Android Context does not cause crashes
        // like crbug.com/453845
        mTestFramework = startCronetTestFramework();
        CronetEngine firstEngine =
                new CronetUrlRequestContext(mTestFramework.createCronetEngineBuilder(getContext()));
        CronetEngine secondEngine = new CronetUrlRequestContext(
                mTestFramework.createCronetEngineBuilder(getContext().getApplicationContext()));
        CronetEngine thirdEngine = new CronetUrlRequestContext(
                mTestFramework.createCronetEngineBuilder(new ContextWrapper(getContext())));
        firstEngine.shutdown();
        secondEngine.shutdown();
        thirdEngine.shutdown();
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testGetGlobalMetricsDeltas() throws Exception {
        mTestFramework = startCronetTestFramework();

        byte delta1[] = mTestFramework.mCronetEngine.getGlobalMetricsDeltas();

        TestUrlRequestCallback callback = new TestUrlRequestCallback();
        UrlRequest.Builder builder = new UrlRequest.Builder(
                mUrl, callback, callback.getExecutor(), mTestFramework.mCronetEngine);
        builder.build().start();
        callback.blockForDone();
        byte delta2[] = mTestFramework.mCronetEngine.getGlobalMetricsDeltas();
        assertTrue(delta2.length != 0);
        assertFalse(Arrays.equals(delta1, delta2));
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testCronetEngineBuilderConfig() throws Exception {
        // This is to prompt load of native library.
        startCronetTestFramework();
        // Verify CronetEngine.Builder config is passed down accurately to native code.
        CronetEngine.Builder builder = new CronetEngine.Builder(getContext());
        builder.enableHttp2(false);
        builder.enableQuic(true);
        builder.enableSDCH(true);
        builder.addQuicHint("example.com", 12, 34);
        builder.setCertVerifierData("test_cert_verifier_data");
        builder.enableHttpCache(CronetEngine.Builder.HTTP_CACHE_IN_MEMORY, 54321);
        builder.enableDataReductionProxy("abcd");
        builder.setUserAgent("efgh");
        builder.setExperimentalOptions("ijkl");
        builder.setDataReductionProxyOptions("mnop", "qrst", "uvwx");
        builder.setStoragePath(CronetTestFramework.getTestStorage(getContext()));
        builder.enablePublicKeyPinningBypassForLocalTrustAnchors(false);
        nativeVerifyUrlRequestContextConfig(
                CronetUrlRequestContext.createNativeUrlRequestContextConfig(getContext(), builder),
                CronetTestFramework.getTestStorage(getContext()));
    }

    // Verifies that CronetEngine.Builder config from testCronetEngineBuilderConfig() is properly
    // translated to a native UrlRequestContextConfig.
    private static native void nativeVerifyUrlRequestContextConfig(long config, String storagePath);

    private static class TestBadLibraryLoader extends CronetEngine.Builder.LibraryLoader {
        private boolean mWasCalled = false;

        public void loadLibrary(String libName) {
            // Report that this method was called, but don't load the library
            mWasCalled = true;
        }

        boolean wasCalled() {
            return mWasCalled;
        }
    }

    @SmallTest
    @Feature({"Cronet"})
    @OnlyRunNativeCronet
    public void testSkipLibraryLoading() throws Exception {
        CronetEngine.Builder builder = new CronetEngine.Builder(getContext());
        TestBadLibraryLoader loader = new TestBadLibraryLoader();
        builder.setLibraryLoader(loader).setLibraryName("cronet_tests");
        try {
            // ensureInitialized() calls native code to check the version right after library load
            // and will error with the message below if library loading was skipped
            CronetLibraryLoader.ensureInitialized(getContext(), builder);
            fail("Native library should not be loaded");
        } catch (UnsatisfiedLinkError e) {
            assertTrue(loader.wasCalled());
        }
    }

    // Creates a CronetEngine on another thread and then one on the main thread.  This shouldn't
    // crash.
    @SmallTest
    @Feature({"Cronet"})
    public void testThreadedStartup() throws Exception {
        final ConditionVariable otherThreadDone = new ConditionVariable();
        final ConditionVariable uiThreadDone = new ConditionVariable();
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            public void run() {
                final CronetEngine.Builder builder =
                        new CronetEngine.Builder(getContext()).setLibraryName("cronet_tests");
                new Thread() {
                    public void run() {
                        CronetEngine cronetEngine = builder.build();
                        otherThreadDone.open();
                        cronetEngine.shutdown();
                    }
                }.start();
                otherThreadDone.block();
                builder.build().shutdown();
                uiThreadDone.open();
            }
        });
        assertTrue(uiThreadDone.block(1000));
    }
}
