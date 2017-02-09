// Copyright 2014 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.net;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayList;

/**
 * A writable byte channel that is optimized for chunked writing. Each call to
 * {@link #write} results in a ByteBuffer being created and remembered. Then all
 * of those byte buffers are combined on demand. This approach allows to avoid
 * the cost of reallocating a byte buffer.
 * @deprecated This is no longer used in the new async API.
 */
@Deprecated
public class ChunkedWritableByteChannel implements WritableByteChannel {

    private final ArrayList<ByteBuffer> mBuffers = new ArrayList<ByteBuffer>();

    private ByteBuffer mInitialBuffer;

    private ByteBuffer mBuffer;

    private int mSize;

    private boolean mClosed;

    public void setCapacity(int capacity) {
        if (!mBuffers.isEmpty() || mInitialBuffer != null) {
            throw new IllegalStateException();
        }

        mInitialBuffer = ByteBuffer.allocateDirect(capacity);
    }

    @Override
    public int write(ByteBuffer buffer) throws IOException {
        if (mClosed) {
            throw new ClosedChannelException();
        }

        int size = buffer.remaining();
        mSize += size;

        if (mInitialBuffer != null) {
            if (size <= mInitialBuffer.remaining()) {
                mInitialBuffer.put(buffer);
                return size;
            }

            // The supplied initial size was incorrect. Keep the accumulated
            // data and switch to the usual "sequence of buffers" mode.
            mInitialBuffer.flip();
            mBuffers.add(mInitialBuffer);
            mInitialBuffer = null;
        }

        // We can't hold a reference to this buffer, because it may wrap native
        // memory and is not guaranteed to be immutable.
        ByteBuffer tmpBuf = ByteBuffer.allocateDirect(size);
        tmpBuf.put(buffer).rewind();
        mBuffers.add(tmpBuf);
        return size;
    }

    /**
     * Returns the entire content accumulated by the channel as a ByteBuffer.
     */
    public ByteBuffer getByteBuffer() {
        if (mInitialBuffer != null) {
            mInitialBuffer.flip();
            mBuffer = mInitialBuffer;
            mInitialBuffer = null;
        } else if (mBuffer != null && mSize == mBuffer.capacity()) {
            // Cache hit
        } else if (mBuffer == null && mBuffers.size() == 1) {
            mBuffer = mBuffers.get(0);
        } else {
            mBuffer = ByteBuffer.allocateDirect(mSize);
            int count = mBuffers.size();
            for (int i = 0; i < count; i++) {
                mBuffer.put(mBuffers.get(i));
            }
            mBuffer.rewind();
        }
        return mBuffer;
    }

    /**
     * Returns the entire content accumulated by the channel as a byte array.
     */
    public byte[] getBytes() {
        byte[] bytes = new byte[mSize];
        if (mInitialBuffer != null) {
            mInitialBuffer.flip();
            mInitialBuffer.get(bytes);
        } else {
            int bufferCount = mBuffers.size();
            int offset = 0;
            for (int i = 0; i < bufferCount; i++) {
                ByteBuffer buffer = mBuffers.get(i);
                int bufferSize = buffer.remaining();
                buffer.get(bytes, offset, bufferSize);
                buffer.rewind();
                offset += bufferSize;
            }
        }
        return bytes;
    }

    @Override
    public void close() {
        mClosed = true;
    }

    @Override
    public boolean isOpen() {
        return !mClosed;
    }
}
