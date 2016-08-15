// Copyright 2015 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "cronet_url_request_context_config_test.h"

#include <jni.h>

#include "base/android/jni_android.h"
#include "base/android/jni_string.h"
#include "base/android/scoped_java_ref.h"
#include "base/logging.h"
#include "components/cronet/url_request_context_config.h"
#include "components/cronet/version.h"
#include "jni/CronetUrlRequestContextTest_jni.h"

using base::android::JavaParamRef;

namespace cronet {

// Verifies that all the configuration options set by
// CronetUrlRequestContextTest.testCronetEngineBuilderConfig
// made it from the CronetEngine.Builder to the URLRequestContextConfig.
static void VerifyUrlRequestContextConfig(
    JNIEnv* env,
    const JavaParamRef<jclass>& jcaller,
    jlong jurl_request_context_config,
    const JavaParamRef<jstring>& jstorage_path) {
  URLRequestContextConfig* config =
      reinterpret_cast<URLRequestContextConfig*>(jurl_request_context_config);
  CHECK_EQ(config->enable_spdy, false);
  CHECK_EQ(config->enable_quic, true);
  CHECK_EQ(config->enable_sdch, true);
  CHECK_EQ(config->bypass_public_key_pinning_for_local_trust_anchors, false);
  CHECK_EQ(config->quic_hints.size(), 1u);
  CHECK_EQ((*config->quic_hints.begin())->host, "example.com");
  CHECK_EQ((*config->quic_hints.begin())->port, 12);
  CHECK_EQ((*config->quic_hints.begin())->alternate_port, 34);
  CHECK_NE(config->quic_user_agent_id.find("Cronet/" CRONET_VERSION),
           std::string::npos);
  CHECK_EQ(config->load_disable_cache, false);
  CHECK_EQ(config->cert_verifier_data, "test_cert_verifier_data");
  CHECK_EQ(config->http_cache, URLRequestContextConfig::HttpCacheType::MEMORY);
  CHECK_EQ(config->http_cache_max_size, 54321);
  CHECK_EQ(config->data_reduction_proxy_key, "abcd");
  CHECK_EQ(config->user_agent, "efgh");
  CHECK_EQ(config->experimental_options, "ijkl");
  CHECK_EQ(config->data_reduction_primary_proxy, "mnop");
  CHECK_EQ(config->data_reduction_fallback_proxy, "qrst");
  CHECK_EQ(config->data_reduction_secure_proxy_check_url, "uvwx");
  CHECK_EQ(config->storage_path,
           base::android::ConvertJavaStringToUTF8(env, jstorage_path));
}

bool RegisterCronetUrlRequestContextConfigTest(JNIEnv* env) {
  return RegisterNativesImpl(env);
}

}  // namespace cronet
