// Copyright 2014 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include <jni.h>

#include "base/android/base_jni_registrar.h"
#include "base/android/jni_android.h"
#include "base/android/jni_registrar.h"
#include "base/macros.h"
#include "components/cronet/android/cronet_library_loader.h"
#include "cronet_test_util.h"
#include "cronet_url_request_context_config_test.h"
#include "mock_cert_verifier.h"
#include "mock_url_request_job_factory.h"
#include "native_test_server.h"
#include "network_change_notifier_util.h"
#include "quic_test_server.h"
#include "sdch_test_util.h"
#include "test_upload_data_stream_handler.h"

namespace {

const base::android::RegistrationMethod kCronetTestsRegisteredMethods[] = {
    {"MockCertVerifier", cronet::RegisterMockCertVerifier},
    {"MockUrlRequestJobFactory", cronet::RegisterMockUrlRequestJobFactory},
    {"NativeTestServer", cronet::RegisterNativeTestServer},
    {"NetworkChangeNotifierUtil", cronet::RegisterNetworkChangeNotifierUtil},
    {"QuicTestServer", cronet::RegisterQuicTestServer},
    {"SdchTestUtil", cronet::RegisterSdchTestUtil},
    {"TestUploadDataStreamHandlerRegisterJni",
     cronet::TestUploadDataStreamHandlerRegisterJni},
    {"CronetUrlRequestContextConfigTest",
     cronet::RegisterCronetUrlRequestContextConfigTest},
    {"CronetTestUtil", cronet::RegisterCronetTestUtil},
};

}  // namespace

// This is called by the VM when the shared library is first loaded.
// Checks the available version of JNI. Also, caches Java reflection artifacts.
extern "C" jint JNI_OnLoad(JavaVM* vm, void* reserved) {
  JNIEnv* env;
  if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
    return -1;
  }

  jint cronet_onload = cronet::CronetOnLoad(vm, reserved);
  if (cronet_onload == -1)
    return cronet_onload;

  if (!base::android::RegisterNativeMethods(
          env,
          kCronetTestsRegisteredMethods,
          arraysize(kCronetTestsRegisteredMethods))) {
    return -1;
  }
  return cronet_onload;
}

extern "C" void JNI_OnUnLoad(JavaVM* vm, void* reserved) {
  cronet::CronetOnUnLoad(vm, reserved);
}

