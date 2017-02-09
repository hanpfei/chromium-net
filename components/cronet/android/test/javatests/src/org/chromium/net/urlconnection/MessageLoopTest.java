// Copyright 2014 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.net.urlconnection;

import android.test.suitebuilder.annotation.SmallTest;

import org.chromium.base.test.util.Feature;
import org.chromium.net.CronetTestBase;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;

/**
 * Tests the MessageLoop implementation.
 */
public class MessageLoopTest extends CronetTestBase {
    private Thread mTestThread;
    private final ExecutorService mExecutorService =
            Executors.newSingleThreadExecutor(new ExecutorThreadFactory());
    private class ExecutorThreadFactory implements ThreadFactory {
        public Thread newThread(Runnable r) {
            mTestThread = new Thread(r);
            return mTestThread;
        }
    }
    private boolean mFailed = false;

    @SmallTest
    @Feature({"Cronet"})
    public void testInterrupt() throws Exception {
        final MessageLoop loop = new MessageLoop();
        assertFalse(loop.isRunning());
        Future future = mExecutorService.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    loop.loop();
                    mFailed = true;
                } catch (IOException e) {
                    // Expected interrupt.
                }
            }
        });
        Thread.sleep(1000);
        assertTrue(loop.isRunning());
        assertFalse(loop.hasLoopFailed());
        mTestThread.interrupt();
        future.get();
        assertFalse(loop.isRunning());
        assertTrue(loop.hasLoopFailed());
        assertFalse(mFailed);
        // Re-spinning the message loop is not allowed after interrupt.
        mExecutorService.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    loop.loop();
                    fail();
                } catch (Exception e) {
                    if (!(e instanceof IllegalStateException)) {
                        fail();
                    }
                }
            }
        }).get();
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testTaskFailed() throws Exception {
        final MessageLoop loop = new MessageLoop();
        assertFalse(loop.isRunning());
        Future future = mExecutorService.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    loop.loop();
                    mFailed = true;
                } catch (Exception e) {
                    if (!(e instanceof NullPointerException)) {
                        mFailed = true;
                    }
                }
            }
        });
        Runnable failedTask = new Runnable() {
            @Override
            public void run() {
                throw new NullPointerException();
            }
        };
        Thread.sleep(1000);
        assertTrue(loop.isRunning());
        assertFalse(loop.hasLoopFailed());
        loop.execute(failedTask);
        future.get();
        assertFalse(loop.isRunning());
        assertTrue(loop.hasLoopFailed());
        assertFalse(mFailed);
        // Re-spinning the message loop is not allowed after exception.
        mExecutorService.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    loop.loop();
                    fail();
                } catch (Exception e) {
                    if (!(e instanceof IllegalStateException)) {
                        fail();
                    }
                }
            }
        }).get();
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testLoopWithTimeout() throws Exception {
        final MessageLoop loop = new MessageLoop();
        assertFalse(loop.isRunning());
        // The MessageLoop queue is empty. Use a timeout of 100ms to check that
        // it doesn't block forever.
        try {
            loop.loop(100);
            fail();
        } catch (SocketTimeoutException e) {
            // Expected.
        }
        assertFalse(loop.isRunning());
    }
}
