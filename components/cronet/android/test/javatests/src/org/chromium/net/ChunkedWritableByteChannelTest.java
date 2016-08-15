// Copyright 2014 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.net;

import android.test.InstrumentationTestCase;
import android.test.suitebuilder.annotation.SmallTest;

import org.chromium.base.test.util.Feature;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.Charset;
import java.util.Arrays;

/**
 * Tests for {@link ChunkedWritableByteChannel}
 */
@SuppressWarnings("deprecation")
public class ChunkedWritableByteChannelTest extends InstrumentationTestCase {
    private ChunkedWritableByteChannel mChannel;

    @Override
    public void setUp() {
        mChannel = new ChunkedWritableByteChannel();
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testSetCapacity() {
        int capacity = 100;
        mChannel.setCapacity(capacity);
        assertEquals("Bytebuffer capacity wasn't set properly", capacity,
                mChannel.getByteBuffer().capacity());
        mChannel.close();
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testWrite() throws IOException {
        String test = "Write in the buffer.";
        mChannel.write(
                ByteBuffer.wrap(test.getBytes(Charset.forName("UTF-8"))));
        mChannel.write(
                ByteBuffer.wrap(test.getBytes(Charset.forName("UTF-8"))));
        assertEquals("Buffer didn't write the bytes properly", test + test,
                new String(mChannel.getBytes(), "UTF-8"));
        mChannel.close();
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testCloseChannel() {
        assertTrue("Channel should be open", mChannel.isOpen());
        mChannel.close();
        assertFalse("Channel shouldn't be open", mChannel.isOpen());
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testWriteToClosedChannel() throws IOException {
        try {
            mChannel.close();
            mChannel.write(ByteBuffer.wrap(new byte[1]));
            fail("ClosedChannelException should have been thrown.");
        } catch (ClosedChannelException e) {
            // Intended
        }
    }

    @SmallTest
    @Feature({"Cronet"})
    public void testCapacityGrows() throws Exception {
        mChannel.setCapacity(123);
        byte[] data = new byte[1234];
        Arrays.fill(data, (byte) 'G');
        mChannel.write(ByteBuffer.wrap(data));
        assertTrue(Arrays.equals(data, mChannel.getBytes()));
    }
}
