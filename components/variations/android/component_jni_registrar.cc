// Copyright 2014 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "components/variations/android/component_jni_registrar.h"

#include "base/android/jni_android.h"
#include "base/android/jni_registrar.h"
#include "base/macros.h"
#include "components/variations/android/variations_associated_data_android.h"

namespace variations {

namespace android {

static base::android::RegistrationMethod kVariationsRegisteredMethods[] = {
    {"VariationsAssociatedData", RegisterVariationsAssociatedData},
};

bool RegisterVariations(JNIEnv* env) {
  return base::android::RegisterNativeMethods(
      env, kVariationsRegisteredMethods,
      arraysize(kVariationsRegisteredMethods));
}

}  // namespace android

}  // namespace variations
