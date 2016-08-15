// Copyright 2014 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.cronet_sample_apk;

import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.ConditionVariable;
import android.test.ActivityInstrumentationTestCase2;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.TextView;

import org.chromium.base.test.util.FlakyTest;
import org.chromium.net.test.EmbeddedTestServer;

/**
 * Base test class for all CronetSample based tests.
 */
public class CronetSampleTest extends
        ActivityInstrumentationTestCase2<CronetSampleActivity> {
    private EmbeddedTestServer mTestServer;
    private String mUrl;

    public CronetSampleTest() {
        super(CronetSampleActivity.class);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mTestServer =
                EmbeddedTestServer.createAndStartDefaultServer(getInstrumentation().getContext());
        mUrl = mTestServer.getURL("/echo?status=200");
    }

    @Override
    protected void tearDown() throws Exception {
        mTestServer.stopAndDestroyServer();
        super.tearDown();
    }

    /*
    @SmallTest
    @Feature({"Cronet"})
    */
    @FlakyTest(message = "https://crbug.com/592444")
    public void testLoadUrl() throws Exception {
        CronetSampleActivity activity = launchCronetSampleWithUrl(mUrl);

        // Make sure the activity was created as expected.
        assertNotNull(activity);

        // Verify successful fetch.
        final TextView textView = (TextView) activity.findViewById(R.id.resultView);
        final ConditionVariable done = new ConditionVariable();
        final TextWatcher textWatcher = new TextWatcher() {
            @Override
            public void afterTextChanged(Editable s) {}

            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (s.equals("Completed " + mUrl + " (200)")) {
                    done.open();
                }
            }
        };
        textView.addTextChangedListener(textWatcher);
        // Check current text in case it changed before |textWatcher| was added.
        textWatcher.onTextChanged(textView.getText(), 0, 0, 0);
        done.block();
    }

    /**
     * Starts the CronetSample activity and loads the given URL.
     */
    protected CronetSampleActivity launchCronetSampleWithUrl(String url) {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.setData(Uri.parse(url));
        intent.setComponent(new ComponentName(
                getInstrumentation().getTargetContext(),
                CronetSampleActivity.class));
        setActivityIntent(intent);
        return getActivity();
    }
}
