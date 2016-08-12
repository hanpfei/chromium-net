// Copyright 2015 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.base.test.shadows;

import android.content.Context;
import android.support.multidex.MultiDex;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/** Do-nothing shadow for {@link android.support.multidex.MultiDex}. */
@Implements(MultiDex.class)
public class ShadowMultiDex {

    @Implementation
    public static void install(Context context) {
    }

}
