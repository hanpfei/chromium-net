// Copyright 2015 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.net.impl;

import android.util.Log;

import org.chromium.base.VisibleForTesting;
import org.chromium.base.annotations.CalledByNative;
import org.chromium.base.annotations.JNINamespace;
import org.chromium.base.annotations.NativeClassQualifiedName;
import org.chromium.net.UploadDataProvider;
import org.chromium.net.UploadDataSink;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.Executor;

import javax.annotation.concurrent.GuardedBy;

/**
 * CronetUploadDataStream handles communication between an upload body
 * encapsulated in the embedder's {@link UploadDataSink} and a C++
 * UploadDataStreamAdapter, which it owns. It's attached to a {@link
 * CronetUrlRequest}'s during the construction of request's native C++ objects
 * on the network thread, though it's created on one of the embedder's threads.
 * It is called by the UploadDataStreamAdapter on the network thread, but calls
 * into the UploadDataSink and the UploadDataStreamAdapter on the Executor
 * passed into its constructor.
 */
@JNINamespace("cronet")
@VisibleForTesting
public final class CronetUploadDataStream implements UploadDataSink {
    private static final String TAG = "CronetUploadDataStream";
    // These are never changed, once a request starts.
    private final Executor mExecutor;
    private final UploadDataProvider mDataProvider;
    private long mLength;
    private long mRemainingLength;
    private CronetUrlRequest mRequest;

    // Reusable read task, to reduce redundant memory allocation.
    private final Runnable mReadTask = new Runnable() {
        @Override
        public void run() {
            synchronized (mLock) {
                if (mUploadDataStreamAdapter == 0) {
                    return;
                }
                checkState(UserCallback.NOT_IN_CALLBACK);
                if (mByteBuffer == null) {
                    throw new IllegalStateException("Unexpected readData call. Buffer is null");
                }
                mInWhichUserCallback = UserCallback.READ;
            }
            try {
                mDataProvider.read(CronetUploadDataStream.this, mByteBuffer);
            } catch (Exception exception) {
                onError(exception);
            }
        }
    };

    // ByteBuffer created in the native code and passed to
    // UploadDataProvider for reading. It is only valid from the
    // call to mDataProvider.read until onError or onReadSucceeded.
    private ByteBuffer mByteBuffer = null;

    // Lock that protects all subsequent variables. The adapter has to be
    // protected to ensure safe shutdown, mReading and mRewinding are protected
    // to robustly detect getting read/rewind results more often than expected.
    private final Object mLock = new Object();

    // Native adapter object, owned by the CronetUploadDataStream. It's only
    // deleted after the native UploadDataStream object is destroyed. All access
    // to the adapter is synchronized, for safe usage and cleanup.
    @GuardedBy("mLock")
    private long mUploadDataStreamAdapter = 0;
    enum UserCallback {
        READ,
        REWIND,
        GET_LENGTH,
        NOT_IN_CALLBACK,
    }
    @GuardedBy("mLock")
    private UserCallback mInWhichUserCallback = UserCallback.NOT_IN_CALLBACK;
    @GuardedBy("mLock")
    private boolean mDestroyAdapterPostponed = false;
    private Runnable mOnDestroyedCallbackForTesting;

    /**
     * Constructs a CronetUploadDataStream.
     * @param dataProvider the UploadDataProvider to read data from.
     * @param executor the Executor to execute UploadDataProvider tasks.
     */
    public CronetUploadDataStream(UploadDataProvider dataProvider, Executor executor) {
        mExecutor = executor;
        mDataProvider = dataProvider;
    }

    /**
     * Called by native code to make the UploadDataProvider read data into
     * {@code byteBuffer}.
     */
    @SuppressWarnings("unused")
    @CalledByNative
    void readData(ByteBuffer byteBuffer) {
        mByteBuffer = byteBuffer;
        postTaskToExecutor(mReadTask);
    }

    // TODO(mmenke): Consider implementing a cancel method.
    // currently wait for any pending read to complete.

    /**
     * Called by native code to make the UploadDataProvider rewind upload data.
     */
    @SuppressWarnings("unused")
    @CalledByNative
    void rewind() {
        Runnable task = new Runnable() {
            @Override
            public void run() {
                synchronized (mLock) {
                    if (mUploadDataStreamAdapter == 0) {
                        return;
                    }
                    checkState(UserCallback.NOT_IN_CALLBACK);
                    mInWhichUserCallback = UserCallback.REWIND;
                }
                try {
                    mDataProvider.rewind(CronetUploadDataStream.this);
                } catch (Exception exception) {
                    onError(exception);
                }
            }
        };
        postTaskToExecutor(task);
    }

    @GuardedBy("mLock")
    private void checkState(UserCallback mode) {
        if (mInWhichUserCallback != mode) {
            throw new IllegalStateException(
                    "Expected " + mode + ", but was " + mInWhichUserCallback);
        }
    }

    /**
     * Called when the native UploadDataStream is destroyed.  At this point,
     * the native adapter needs to be destroyed, but only after any pending
     * read operation completes, as the adapter owns the read buffer.
     */
    @SuppressWarnings("unused")
    @CalledByNative
    void onUploadDataStreamDestroyed() {
        destroyAdapter();
    }

    /**
     * Helper method called when an exception occurred. This method resets
     * states and propagates the error to the request.
     */
    private void onError(Throwable exception) {
        synchronized (mLock) {
            if (mInWhichUserCallback == UserCallback.NOT_IN_CALLBACK) {
                throw new IllegalStateException(
                        "There is no read or rewind or length check in progress.");
            }
            mInWhichUserCallback = UserCallback.NOT_IN_CALLBACK;
            mByteBuffer = null;
            destroyAdapterIfPostponed();
        }

        // Just fail the request - simpler to fail directly, and
        // UploadDataStream only supports failing during initialization, not
        // while reading. The request is smart enough to handle the case where
        // it was already canceled by the embedder.
        mRequest.onUploadException(exception);
    }

    @Override
    public void onReadSucceeded(boolean lastChunk) {
        synchronized (mLock) {
            checkState(UserCallback.READ);
            if (lastChunk && mLength >= 0) {
                throw new IllegalArgumentException("Non-chunked upload can't have last chunk");
            }
            int bytesRead = mByteBuffer.position();
            mRemainingLength -= bytesRead;
            if (mRemainingLength < 0 && mLength >= 0) {
                throw new IllegalArgumentException(
                        String.format("Read upload data length %d exceeds expected length %d",
                                mLength - mRemainingLength, mLength));
            }
            mByteBuffer = null;
            mInWhichUserCallback = UserCallback.NOT_IN_CALLBACK;

            destroyAdapterIfPostponed();
            // Request may been canceled already.
            if (mUploadDataStreamAdapter == 0) {
                return;
            }
            nativeOnReadSucceeded(mUploadDataStreamAdapter, bytesRead, lastChunk);
        }
    }

    @Override
    public void onReadError(Exception exception) {
        synchronized (mLock) {
            checkState(UserCallback.READ);
            onError(exception);
        }
    }

    @Override
    public void onRewindSucceeded() {
        synchronized (mLock) {
            checkState(UserCallback.REWIND);
            mInWhichUserCallback = UserCallback.NOT_IN_CALLBACK;
            mRemainingLength = mLength;
            // Request may been canceled already.
            if (mUploadDataStreamAdapter == 0) {
                return;
            }
            nativeOnRewindSucceeded(mUploadDataStreamAdapter);
        }
    }

    @Override
    public void onRewindError(Exception exception) {
        synchronized (mLock) {
            checkState(UserCallback.REWIND);
            onError(exception);
        }
    }

    /**
     * Posts task to application Executor.
     */
    void postTaskToExecutor(Runnable task) {
        try {
            mExecutor.execute(task);
        } catch (Throwable e) {
            // Just fail the request. The request is smart enough to handle the
            // case where it was already canceled by the embedder.
            mRequest.onUploadException(e);
        }
    }

    /**
     * The adapter is owned by the CronetUploadDataStream, so it can be
     * destroyed safely when there is no pending read; however, destruction is
     * initiated by the destruction of the native UploadDataStream.
     */
    private void destroyAdapter() {
        synchronized (mLock) {
            if (mInWhichUserCallback == UserCallback.READ) {
                // Wait for the read to complete before destroy the adapter.
                mDestroyAdapterPostponed = true;
                return;
            }
            if (mUploadDataStreamAdapter == 0) {
                return;
            }
            nativeDestroy(mUploadDataStreamAdapter);
            mUploadDataStreamAdapter = 0;
            if (mOnDestroyedCallbackForTesting != null) {
                mOnDestroyedCallbackForTesting.run();
            }
        }
        postTaskToExecutor(new Runnable() {
            @Override
            public void run() {
                try {
                    mDataProvider.close();
                } catch (IOException e) {
                    Log.e(TAG, "Exception thrown when closing", e);
                }
            }
        });
    }

    /**
     * Destroys the native adapter if the destruction is postponed due to a
     * pending read, which has since completed. Caller needs to be on executor
     * thread.
     */
    private void destroyAdapterIfPostponed() {
        synchronized (mLock) {
            if (mInWhichUserCallback == UserCallback.READ) {
                throw new IllegalStateException(
                        "Method should not be called when read has not completed.");
            }
            if (mDestroyAdapterPostponed) {
                destroyAdapter();
            }
        }
    }

    /**
     * Initializes upload length by getting it from data provider. Always called
     * on executor thread to allow getLength() to block and/or report errors.
     * If data provider throws an exception, then it is reported to the request.
     * No native calls to urlRequest are allowed as this is done before request
     * start, so native object may not exist.
     */
    void initializeWithRequest(final CronetUrlRequest urlRequest) {
        synchronized (mLock) {
            mRequest = urlRequest;
            mInWhichUserCallback = UserCallback.GET_LENGTH;
        }
        try {
            mLength = mDataProvider.getLength();
            mRemainingLength = mLength;
        } catch (Throwable t) {
            onError(t);
        }
        synchronized (mLock) {
            mInWhichUserCallback = UserCallback.NOT_IN_CALLBACK;
        }
    }

    /**
     * Creates native objects and attaches them to the underlying request
     * adapter object. Always called on executor thread.
     */
    void attachNativeAdapterToRequest(final long requestAdapter) {
        synchronized (mLock) {
            mUploadDataStreamAdapter = nativeAttachUploadDataToRequest(requestAdapter, mLength);
        }
    }

    /**
     * Creates a native CronetUploadDataStreamAdapter and
     * CronetUploadDataStream for testing.
     * @return the address of the native CronetUploadDataStream object.
     */
    @VisibleForTesting
    public long createUploadDataStreamForTesting() throws IOException {
        synchronized (mLock) {
            mUploadDataStreamAdapter = nativeCreateAdapterForTesting();
            mLength = mDataProvider.getLength();
            mRemainingLength = mLength;
            return nativeCreateUploadDataStreamForTesting(mLength, mUploadDataStreamAdapter);
        }
    }

    @VisibleForTesting
    void setOnDestroyedCallbackForTesting(Runnable onDestroyedCallbackForTesting) {
        mOnDestroyedCallbackForTesting = onDestroyedCallbackForTesting;
    }

    // Native methods are implemented in upload_data_stream_adapter.cc.

    private native long nativeAttachUploadDataToRequest(long urlRequestAdapter, long length);

    private native long nativeCreateAdapterForTesting();

    private native long nativeCreateUploadDataStreamForTesting(long length, long adapter);

    @NativeClassQualifiedName("CronetUploadDataStreamAdapter")
    private native void nativeOnReadSucceeded(long nativePtr, int bytesRead, boolean finalChunk);

    @NativeClassQualifiedName("CronetUploadDataStreamAdapter")
    private native void nativeOnRewindSucceeded(long nativePtr);

    @NativeClassQualifiedName("CronetUploadDataStreamAdapter")
    private static native void nativeDestroy(long nativePtr);
}
