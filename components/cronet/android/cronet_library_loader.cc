// Copyright 2014 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "components/cronet/android/cronet_library_loader.h"

#include <jni.h>
#include <vector>

#include "base/android/base_jni_onload.h"
#include "base/android/base_jni_registrar.h"
#include "base/android/jni_android.h"
#include "base/android/jni_registrar.h"
#include "base/android/jni_utils.h"
#include "base/android/library_loader/library_loader_hooks.h"
#include "base/command_line.h"
#include "base/feature_list.h"
#include "base/logging.h"
#include "base/macros.h"
#include "base/message_loop/message_loop.h"
#include "components/cronet/android/chromium_url_request.h"
#include "components/cronet/android/chromium_url_request_context.h"
#include "components/cronet/android/cronet_bidirectional_stream_adapter.h"
#include "components/cronet/android/cronet_upload_data_stream_adapter.h"
#include "components/cronet/android/cronet_url_request_adapter.h"
#include "components/cronet/android/cronet_url_request_context_adapter.h"
#include "components/cronet/version.h"
#include "jni/CronetLibraryLoader_jni.h"
#include "net/android/net_jni_registrar.h"
#include "net/android/network_change_notifier_factory_android.h"
#include "net/base/network_change_notifier.h"
#include "url/url_features.h"
#include "url/url_util.h"

#if !BUILDFLAG(USE_PLATFORM_ICU_ALTERNATIVES)
#include "base/i18n/icu_util.h"  // nogncheck
#endif

using base::android::JavaParamRef;
using base::android::ScopedJavaLocalRef;

namespace cronet {
namespace {

const base::android::RegistrationMethod kCronetRegisteredMethods[] = {
    {"BaseAndroid", base::android::RegisterJni},
    {"ChromiumUrlRequest", ChromiumUrlRequestRegisterJni},
    {"ChromiumUrlRequestContext", ChromiumUrlRequestContextRegisterJni},
    {"CronetBidirectionalStreamAdapter",
     CronetBidirectionalStreamAdapter::RegisterJni},
    {"CronetLibraryLoader", RegisterNativesImpl},
    {"CronetUploadDataStreamAdapter", CronetUploadDataStreamAdapterRegisterJni},
    {"CronetUrlRequestAdapter", CronetUrlRequestAdapterRegisterJni},
    {"CronetUrlRequestContextAdapter",
     CronetUrlRequestContextAdapterRegisterJni},
    {"NetAndroid", net::android::RegisterJni},
};

// MessageLoop on the main thread, which is where objects that receive Java
// notifications generally live.
base::MessageLoop* g_main_message_loop = nullptr;

net::NetworkChangeNotifier* g_network_change_notifier = nullptr;

bool RegisterJNI(JNIEnv* env) {
  return base::android::RegisterNativeMethods(
      env, kCronetRegisteredMethods, arraysize(kCronetRegisteredMethods));
}

bool Init() {
  url::Initialize();
  return true;
}

}  // namespace

// Checks the available version of JNI. Also, caches Java reflection artifacts.
jint CronetOnLoad(JavaVM* vm, void* reserved) {
  std::vector<base::android::RegisterCallback> register_callbacks;
  register_callbacks.push_back(base::Bind(&RegisterJNI));
  std::vector<base::android::InitCallback> init_callbacks;
  init_callbacks.push_back(base::Bind(&Init));
  if (!base::android::OnJNIOnLoadRegisterJNI(vm, register_callbacks) ||
      !base::android::OnJNIOnLoadInit(init_callbacks)) {
    return -1;
  }
  return JNI_VERSION_1_6;
}

void CronetOnUnLoad(JavaVM* jvm, void* reserved) {
  base::android::LibraryLoaderExitHook();
}

void CronetInitOnMainThread(JNIEnv* env, const JavaParamRef<jclass>& jcaller) {
#if !BUILDFLAG(USE_PLATFORM_ICU_ALTERNATIVES)
  base::i18n::InitializeICU();
#endif

  base::FeatureList::InitializeInstance(std::string(), std::string());
  // TODO(bengr): Remove once Data Reduction Proxy no longer needs this for
  // configuration information.
  base::CommandLine::Init(0, nullptr);
  DCHECK(!base::MessageLoop::current());
  DCHECK(!g_main_message_loop);
  g_main_message_loop = new base::MessageLoopForUI();
  base::MessageLoopForUI::current()->Start();
  DCHECK(!g_network_change_notifier);
  net::NetworkChangeNotifier::SetFactory(
      new net::NetworkChangeNotifierFactoryAndroid());
  g_network_change_notifier = net::NetworkChangeNotifier::Create();
}

ScopedJavaLocalRef<jstring> GetCronetVersion(
    JNIEnv* env,
    const JavaParamRef<jclass>& jcaller) {
  return base::android::ConvertUTF8ToJavaString(env, CRONET_VERSION);
}

}  // namespace cronet
