// Copyright 2015 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.net;

import android.os.ConditionVariable;

import org.chromium.base.annotations.CalledByNative;
import org.chromium.base.annotations.JNINamespace;

/**
 * Class to watch for Sdch dictionary events. The native implementation
 * unregisters itself when an event happens. Therefore, an instance of this
 * class is only able to receive a notification of the earliest event.
 * Currently, implemented events include {@link #onDictionaryAdded}.
 */
@JNINamespace("cronet")
public class SdchObserver {
    protected boolean mDictionaryAlreadyPresent = false;
    private final ConditionVariable mAddBlock = new ConditionVariable();

    /**
     * Constructor.
     * @param targetUrl the target url on which sdch encoding will be used.
     * @param contextAdapter the native context adapter to register the observer.
     * @param isLegacyApi whether legacy api is used.
     */
    public SdchObserver(String targetUrl, long contextAdapter, boolean isLegacyAPI) {
        if (isLegacyAPI) {
            nativeAddSdchObserverLegacyAPI(targetUrl, contextAdapter);
        } else {
            nativeAddSdchObserver(targetUrl, contextAdapter);
        }
        mAddBlock.block();
        mAddBlock.close();
    }

    /**
     * Called when a dictionary is added to the SdchManager for the target url.
     * Override this method if caller would like to get notified.
     */
    @CalledByNative
    public void onDictionaryAdded() {
        // Left blank;
    }

    @CalledByNative
    private void onAddSdchObserverCompleted() {
        mAddBlock.open();
    }

    @CalledByNative
    private void onDictionarySetAlreadyPresent() {
        mDictionaryAlreadyPresent = true;
        mAddBlock.open();
    }

    private native void nativeAddSdchObserver(String targetUrl, long contextAdapter);
    private native void nativeAddSdchObserverLegacyAPI(String targetUrl, long contextAdapter);
}
