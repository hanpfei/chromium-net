// Copyright 2015 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.components.variations.firstrun;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * This receiver is notified when a user goes through the Setup Wizard and acknowledges
 * the Chrome ToS and after that we start a variations service to fetch variations seed
 * before the actual first Chrome launch.
 *
 * TODO(agulenko): Implement working with another broadcast (e.g. connectivity change).
 */
public class VariationsSeedServiceLauncher extends BroadcastReceiver {
    private static final String TAG = "VariationsSeedServiceLauncher";

    @Override
    public void onReceive(Context context, Intent intent) {
        // Start of service to fetch variations seed from the server to use it on Chrome first run
        Intent serviceIntent = new Intent(context, VariationsSeedService.class);
        context.startService(serviceIntent);
    }
}