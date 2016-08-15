// Copyright 2014 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef COMPONENTS_VARIATIONS_ANDROID_COMPONENT_JNI_REGISTRAR_H_
#define COMPONENTS_VARIATIONS_ANDROID_COMPONENT_JNI_REGISTRAR_H_

#include <jni.h>

namespace variations {

namespace android {

// Register all JNI bindings necessary for the variations component.
bool RegisterVariations(JNIEnv* env);

}  // namespace android

}  // namespace variations

#endif  // COMPONENTS_VARIATIONS_ANDROID_COMPONENT_JNI_REGISTRAR_H_
