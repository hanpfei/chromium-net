// Copyright 2014 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.net;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import org.chromium.base.FileUtils;
import org.chromium.base.PathUtils;
import org.chromium.base.annotations.SuppressFBWarnings;

import java.io.File;
import java.io.IOException;

/**
 * Helper class to install test files.
 */
public final class TestFilesInstaller {
    private static final String TAG = "TestFilesInstaller";
    // Name of the asset directory in which test files are stored.
    private static final String TEST_FILE_ASSET_PATH = "test";

    /**
     * Installs test files if files have not been installed.
     */
    public static void installIfNeeded(Context context) {
        if (areFilesInstalled(context)) {
            return;
        }
        try {
            install(context, TEST_FILE_ASSET_PATH);
        } catch (IOException e) {
            // Make the test app crash and fail early.
            throw new RuntimeException(e);
        }
    }

    /**
     * Returns the installed path of the test files.
     */
    public static String getInstalledPath(Context context) {
        return PathUtils.getDataDirectory(context) + "/" + TEST_FILE_ASSET_PATH;
    }

    /**
     * Returns whether test files are installed.
     */
    private static boolean areFilesInstalled(Context context) {
        // Checking for file directory is fine even when new files are added,
        // because the app will be re-installed and app data will be cleared.
        File directory = new File(getInstalledPath(context));
        return directory.exists();
    }

    /**
     * Installs test files that are included in {@code path}.
     * @params context Application context
     * @params path
     */
    private static void install(Context context, String path) throws IOException {
        AssetManager assetManager = context.getAssets();
        String files[] = assetManager.list(path);
        Log.i(TAG, "Loading " + path + " ...");
        String root = PathUtils.getDataDirectory(context);
        if (files.length == 0) {
            // The path is a file, so copy the file now.
            copyTestFile(context, path, root + "/" + path);
        } else {
            // The path is a directory, so recursively handle its files, since
            // the directory can contain subdirectories.
            String fullPath = root + "/" + path;
            File dir = new File(fullPath);
            if (!dir.exists()) {
                Log.i(TAG, "Creating directory " + fullPath + " ...");
                if (!dir.mkdir()) {
                    throw new IOException("Directory not created.");
                }
            }
            for (int i = 0; i < files.length; i++) {
                install(context, path + "/" + files[i]);
            }
        }
    }
    /**
     * Copies a file from assets to the device's file system.
     * @param context
     * @param srcFilePath the source file path in assets.
     * @param destFilePath the destination file path.
     * @throws IllegalStateException if the destination file already exists.
     */
    @SuppressFBWarnings("OBL_UNSATISFIED_OBLIGATION_EXCEPTION_EDGE")
    private static void copyTestFile(Context context, String srcFilePath, String destFilePath)
            throws IOException {
        File destFile = new File(destFilePath);
        if (destFile.exists()) {
            throw new IllegalStateException(srcFilePath + " already exists.");
        }

        if (!FileUtils.extractAsset(context, srcFilePath, destFile)) {
            throw new IOException("Could not extract asset " + srcFilePath + ".");
        }
    }
}
