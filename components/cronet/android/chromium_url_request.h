// Copyright 2014 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef COMPONENTS_CRONET_ANDROID_CHROMIUM_URL_REQUEST_H_
#define COMPONENTS_CRONET_ANDROID_CHROMIUM_URL_REQUEST_H_

#include <jni.h>

namespace cronet {

// Define request priority values like REQUEST_PRIORITY_IDLE in a
// way that ensures they're always the same than their Java counterpart.
//
// A Java counterpart will be generated for this enum.
// GENERATED_JAVA_ENUM_PACKAGE: org.chromium.net
// GENERATED_JAVA_CLASS_NAME_OVERRIDE: ChromiumUrlRequestPriority
// GENERATED_JAVA_PREFIX_TO_STRIP: REQUEST_PRIORITY_
enum UrlRequestPriority {
  REQUEST_PRIORITY_IDLE = 0,
  REQUEST_PRIORITY_LOWEST = 1,
  REQUEST_PRIORITY_LOW = 2,
  REQUEST_PRIORITY_MEDIUM = 3,
  REQUEST_PRIORITY_HIGHEST = 4,
};

// Define request error values like REQUEST_ERROR_SUCCESS in a
// way that ensures they're always the same than their Java counterpart.
//
// A Java counterpart will be generated for this enum.
// GENERATED_JAVA_ENUM_PACKAGE: org.chromium.net
// GENERATED_JAVA_CLASS_NAME_OVERRIDE: ChromiumUrlRequestError
// GENERATED_JAVA_PREFIX_TO_STRIP: REQUEST_ERROR_
enum UrlRequestError {
  REQUEST_ERROR_SUCCESS = 0,
  REQUEST_ERROR_UNKNOWN = 1,
  REQUEST_ERROR_MALFORMED_URL = 2,
  REQUEST_ERROR_CONNECTION_TIMED_OUT = 3,
  REQUEST_ERROR_UNKNOWN_HOST = 4,
  REQUEST_ERROR_TOO_MANY_REDIRECTS = 5,
};

bool ChromiumUrlRequestRegisterJni(JNIEnv* env);

}  // namespace cronet

#endif  // COMPONENTS_CRONET_ANDROID_CHROMIUM_URL_REQUEST_H_
