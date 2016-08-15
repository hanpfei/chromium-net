// Copyright 2014 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef CRONET_NETWORK_CHANGE_NOTIFIER_UTIL_H_
#define CRONET_NETWORK_CHANGE_NOTIFIER_UTIL_H_

#include <jni.h>

namespace cronet {

bool RegisterNetworkChangeNotifierUtil(JNIEnv* jenv);

}  // namespace cronet

#endif  // CRONET_NETWORK_CHANGE_NOTIFIER_UTIL_H_
