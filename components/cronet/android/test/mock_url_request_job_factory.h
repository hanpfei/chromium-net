// Copyright 2014 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef CRONET_MOCK_URL_REQUEST_JOB_FACTORY_H_
#define CRONET_MOCK_URL_REQUEST_JOB_FACTORY_H_

#include <jni.h>

namespace cronet {

bool RegisterMockUrlRequestJobFactory(JNIEnv* env);

}  // namespace cronet

#endif  // CRONET_MOCK_URL_REQUEST_JOB_FACTORY_H_
