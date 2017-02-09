// Copyright 2016 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.net;

import static junit.framework.Assert.assertEquals;

import android.util.SparseIntArray;

import java.util.concurrent.Executor;

class TestNetworkQualityRttListener extends NetworkQualityRttListener {
    // Lock to ensure that observation counts can be updated and read by different threads.
    private final Object mLock = new Object();
    private int mRttObservationCount;

    // Holds the RTT observations counts indexed by source.
    private SparseIntArray mRttObservationCountBySource = new SparseIntArray();

    private Thread mExecutorThread;

    TestNetworkQualityRttListener(Executor executor) {
        super(executor);
    }

    @Override
    public void onRttObservation(int rttMs, long when, int source) {
        synchronized (mLock) {
            mRttObservationCount++;
            mRttObservationCountBySource.put(source, mRttObservationCountBySource.get(source) + 1);

            if (mExecutorThread == null) {
                mExecutorThread = Thread.currentThread();
            }
            // Verify that the listener is always notified on the same thread.
            assertEquals(mExecutorThread, Thread.currentThread());
        }
    }

    public int rttObservationCount() {
        synchronized (mLock) {
            return mRttObservationCount;
        }
    }

    public int rttObservationCount(int source) {
        synchronized (mLock) {
            return mRttObservationCountBySource.get(source);
        }
    }

    public Thread getThread() {
        synchronized (mLock) {
            return mExecutorThread;
        }
    }
}