// Copyright 2016 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.net;

import static junit.framework.Assert.assertEquals;

import android.os.ConditionVariable;

import java.util.concurrent.Executor;

class TestNetworkQualityThroughputListener extends NetworkQualityThroughputListener {
    // Lock to ensure that observation counts can be updated and read by different threads.
    private final Object mLock = new Object();
    private final ConditionVariable mWaitForThroughput;
    private int mThroughputObservationCount;
    private Thread mExecutorThread;

    TestNetworkQualityThroughputListener(Executor executor, ConditionVariable waitForThroughput) {
        super(executor);
        mWaitForThroughput = waitForThroughput;
    }

    @Override
    public void onThroughputObservation(int throughputKbps, long when, int source) {
        synchronized (mLock) {
            if (mWaitForThroughput != null) {
                mWaitForThroughput.open();
            }
            mThroughputObservationCount++;
            if (mExecutorThread == null) {
                mExecutorThread = Thread.currentThread();
            }
            // Verify that the listener is always notified on the same thread.
            assertEquals(mExecutorThread, Thread.currentThread());
        }
    }

    public int throughputObservationCount() {
        synchronized (mLock) {
            return mThroughputObservationCount;
        }
    }

    public Thread getThread() {
        synchronized (mLock) {
            return mExecutorThread;
        }
    }
}