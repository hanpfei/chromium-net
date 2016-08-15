// Copyright 2016 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef COMPONENTS_CRONET_ANDROID_TEST_CRONET_TEST_UTIL_H_
#define COMPONENTS_CRONET_ANDROID_TEST_CRONET_TEST_UTIL_H_

#include <jni.h>

namespace cronet {

// Host used in NativeTestServer for SDCH requests.
extern const char kFakeSdchDomain[];
// Host used in QuicTestServer.
extern const char kFakeQuicDomain[];

bool RegisterCronetTestUtil(JNIEnv* env);

}  // namespace cronet

#endif  // COMPONENTS_CRONET_ANDROID_TEST_CRONET_TEST_UTIL_H_
