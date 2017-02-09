// Copyright 2015 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef COMPONENTS_CRONET_ANDROID_TEST_MOCK_CERT_VERIFIER_H_
#define COMPONENTS_CRONET_ANDROID_TEST_MOCK_CERT_VERIFIER_H_

#include <jni.h>

namespace cronet {

bool RegisterMockCertVerifier(JNIEnv* env);

}  // namespace cronet

#endif  // COMPONENTS_CRONET_ANDROID_TEST_MOCK_CERT_VERIFIER_H_
