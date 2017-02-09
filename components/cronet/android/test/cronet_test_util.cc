// Copyright 2016 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "components/cronet/android/test/cronet_test_util.h"

#include "base/android/jni_android.h"
#include "base/android/jni_string.h"
#include "components/cronet/android/cronet_url_request_adapter.h"
#include "components/cronet/android/cronet_url_request_context_adapter.h"
#include "components/cronet/android/test/native_test_server.h"
#include "components/cronet/android/url_request_context_adapter.h"
#include "jni/CronetTestUtil_jni.h"
#include "net/dns/host_resolver_impl.h"
#include "net/dns/mock_host_resolver.h"
#include "net/url_request/url_request.h"

using base::android::JavaParamRef;

namespace cronet {

const char kFakeSdchDomain[] = "fake.sdch.domain";
// This must match the certificate used
// (quic_test.example.com.crt and quic_test.example.com.key.pkcs8), and
// the file served (
// components/cronet/android/test/assets/test/quic_data/simple.txt).
const char kFakeQuicDomain[] = "test.example.com";

namespace {

// Install host resolver rules to map fake domains to |destination|, usually an
// IP address.
void RegisterHostResolverProcHelper(net::URLRequestContext* url_request_context,
                                    const std::string& destination) {
  net::HostResolverImpl* resolver =
      static_cast<net::HostResolverImpl*>(url_request_context->host_resolver());
  scoped_refptr<net::RuleBasedHostResolverProc> proc =
      new net::RuleBasedHostResolverProc(NULL);
  proc->AddRule(kFakeSdchDomain, destination);
  proc->AddRule(kFakeQuicDomain, destination);
  resolver->set_proc_params_for_test(
      net::HostResolverImpl::ProcTaskParams(proc.get(), 1u));
  JNIEnv* env = base::android::AttachCurrentThread();
  Java_CronetTestUtil_onHostResolverProcRegistered(env);
}

void RegisterHostResolverProcOnNetworkThread(
    CronetURLRequestContextAdapter* context_adapter,
    const std::string& destination) {
  RegisterHostResolverProcHelper(context_adapter->GetURLRequestContext(),
                                 destination);
}

// TODO(xunjieli): Delete this once legacy API is removed.
void RegisterHostResolverProcOnNetworkThreadLegacyAPI(
    URLRequestContextAdapter* context_adapter,
    const std::string& destination) {
  RegisterHostResolverProcHelper(context_adapter->GetURLRequestContext(),
                                 destination);
}

}  // namespace

void RegisterHostResolverProc(JNIEnv* env,
                              const JavaParamRef<jclass>& jcaller,
                              jlong jadapter,
                              jboolean jlegacy_api,
                              const JavaParamRef<jstring>& jdestination) {
  std::string destination(
      base::android::ConvertJavaStringToUTF8(env, jdestination));
  if (jlegacy_api == JNI_TRUE) {
    URLRequestContextAdapter* context_adapter =
        reinterpret_cast<URLRequestContextAdapter*>(jadapter);
    context_adapter->PostTaskToNetworkThread(
        FROM_HERE, base::Bind(&RegisterHostResolverProcOnNetworkThreadLegacyAPI,
                              base::Unretained(context_adapter), destination));
  } else {
    CronetURLRequestContextAdapter* context_adapter =
        reinterpret_cast<CronetURLRequestContextAdapter*>(jadapter);
    context_adapter->PostTaskToNetworkThread(
        FROM_HERE, base::Bind(&RegisterHostResolverProcOnNetworkThread,
                              base::Unretained(context_adapter), destination));
  }
}

jint GetLoadFlags(JNIEnv* env,
                  const JavaParamRef<jclass>& jcaller,
                  const jlong urlRequest) {
  return reinterpret_cast<CronetURLRequestAdapter*>(urlRequest)
      ->GetURLRequestForTesting()
      ->load_flags();
}

bool RegisterCronetTestUtil(JNIEnv* env) {
  return RegisterNativesImpl(env);
}

}  // namespace cronet
