// Copyright 2016 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.base.test.util;

import junit.framework.TestCase;

import org.chromium.testing.local.LocalRobolectricTestRunner;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

/** Unit tests for MinAndroidSdkLevelSkipCheck. */
@RunWith(LocalRobolectricTestRunner.class)
@Config(manifest = Config.NONE, reportSdk = 19)
public class MinAndroidSdkLevelSkipCheckTest {

    private static class UnannotatedBaseClass extends TestCase {
        public UnannotatedBaseClass(String name) {
            super(name);
        }
        @MinAndroidSdkLevel(18) public void min18Method() {}
        @MinAndroidSdkLevel(20) public void min20Method() {}
    }

    @MinAndroidSdkLevel(18)
    private static class Min18Class extends UnannotatedBaseClass {
        public Min18Class(String name) {
            super(name);
        }
        public void unannotatedMethod() {}
    }

    @MinAndroidSdkLevel(20)
    private static class Min20Class extends UnannotatedBaseClass {
        public Min20Class(String name) {
            super(name);
        }
        public void unannotatedMethod() {}
    }

    private static class ExtendsMin18Class extends Min18Class {
        public ExtendsMin18Class(String name) {
            super(name);
        }
        public void unannotatedMethod() {}
    }

    private static class ExtendsMin20Class extends Min20Class {
        public ExtendsMin20Class(String name) {
            super(name);
        }
        public void unannotatedMethod() {}
    }

    @Test
    public void testAnnotatedMethodAboveMin() {
        Assert.assertFalse(new MinAndroidSdkLevelSkipCheck().shouldSkip(
                new UnannotatedBaseClass("min18Method")));
    }

    @Test
    public void testAnnotatedMethodBelowMin() {
        Assert.assertTrue(new MinAndroidSdkLevelSkipCheck().shouldSkip(
                new UnannotatedBaseClass("min20Method")));
    }

    @Test
    public void testAnnotatedClassAboveMin() {
        Assert.assertFalse(new MinAndroidSdkLevelSkipCheck().shouldSkip(
                new Min18Class("unannotatedMethod")));
    }

    @Test
    public void testAnnotatedClassBelowMin() {
        Assert.assertTrue(new MinAndroidSdkLevelSkipCheck().shouldSkip(
                new Min20Class("unannotatedMethod")));
    }

    @Test
    public void testAnnotatedSuperclassAboveMin() {
        Assert.assertFalse(new MinAndroidSdkLevelSkipCheck().shouldSkip(
                new ExtendsMin18Class("unannotatedMethod")));
    }

    @Test
    public void testAnnotatedSuperclassBelowMin() {
        Assert.assertTrue(new MinAndroidSdkLevelSkipCheck().shouldSkip(
                new ExtendsMin20Class("unannotatedMethod")));
    }
}
