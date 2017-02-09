// Copyright 2014 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef COMPONENTS_CRONET_ANDROID_URLREQUEST_CONTEXT_H_
#define COMPONENTS_CRONET_ANDROID_URLREQUEST_CONTEXT_H_

#include <jni.h>

namespace cronet {

bool ChromiumUrlRequestContextRegisterJni(JNIEnv* env);

}  // namespace cronet

#endif  // COMPONENTS_CRONET_ANDROID_URLREQUEST_CONTEXT_H_
