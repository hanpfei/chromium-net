// Copyright 2014 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef COMPONENTS_CRONET_ANDROID_CRONET_URL_REQUEST_CONTEXT_ADAPTER_H_
#define COMPONENTS_CRONET_ANDROID_CRONET_URL_REQUEST_CONTEXT_ADAPTER_H_

#include <jni.h>
#include <stdint.h>

#include <memory>
#include <queue>
#include <string>

#include "base/android/scoped_java_ref.h"
#include "base/callback.h"
#include "base/macros.h"
#include "base/memory/ref_counted.h"
#include "base/threading/thread.h"
#include "components/prefs/json_pref_store.h"
#include "net/nqe/network_quality_estimator.h"
#include "net/nqe/network_quality_observation_source.h"

class PrefService;

namespace base {
class SingleThreadTaskRunner;
class TimeTicks;
}  // namespace base

namespace net {
class HttpServerPropertiesManager;
class NetLog;
class ProxyConfigService;
class SdchOwner;
class URLRequestContext;
class WriteToFileNetLogObserver;
}  // namespace net

namespace cronet {

#if defined(DATA_REDUCTION_PROXY_SUPPORT)
class CronetDataReductionProxy;
#endif

struct URLRequestContextConfig;

bool CronetUrlRequestContextAdapterRegisterJni(JNIEnv* env);

// Adapter between Java CronetUrlRequestContext and net::URLRequestContext.
class CronetURLRequestContextAdapter
    : public net::NetworkQualityEstimator::RTTObserver,
      public net::NetworkQualityEstimator::ThroughputObserver {
 public:
  explicit CronetURLRequestContextAdapter(
      std::unique_ptr<URLRequestContextConfig> context_config);

  ~CronetURLRequestContextAdapter() override;

  // Called on main Java thread to initialize URLRequestContext.
  void InitRequestContextOnMainThread(
      JNIEnv* env,
      const base::android::JavaParamRef<jobject>& jcaller);

  // Releases all resources for the request context and deletes the object.
  // Blocks until network thread is destroyed after running all pending tasks.
  void Destroy(JNIEnv* env,
               const base::android::JavaParamRef<jobject>& jcaller);

  // Posts a task that might depend on the context being initialized
  // to the network thread.
  void PostTaskToNetworkThread(const tracked_objects::Location& posted_from,
                               const base::Closure& callback);

  bool IsOnNetworkThread() const;

  net::URLRequestContext* GetURLRequestContext();

  // Starts NetLog logging to file. This can be called on any thread.
  void StartNetLogToFile(JNIEnv* env,
                         const base::android::JavaParamRef<jobject>& jcaller,
                         const base::android::JavaParamRef<jstring>& jfile_name,
                         jboolean jlog_all);

  // Stops NetLog logging to file. This can be called on any thread. This will
  // flush any remaining writes to disk.
  void StopNetLog(JNIEnv* env,
                  const base::android::JavaParamRef<jobject>& jcaller);

  // Posts a task to Network thread to get serialized results of certificate
  // verifications of |context_|'s |cert_verifier|.
  void GetCertVerifierData(JNIEnv* env,
                           const base::android::JavaParamRef<jobject>& jcaller);

  // Default net::LOAD flags used to create requests.
  int default_load_flags() const { return default_load_flags_; }

  // Called on main Java thread to initialize URLRequestContext.
  void InitRequestContextOnMainThread();

  // Configures the network quality estimator to observe localhost requests, and
  // to consider smaller responses when observing throughput for testing. This
  // should be called after the network quality estimator has been enabled.
  void ConfigureNetworkQualityEstimatorForTesting(
      JNIEnv* env,
      const base::android::JavaParamRef<jobject>& jcaller,
      jboolean use_local_host_requests,
      jboolean use_smaller_responses);

  // Request that RTT and/or throughput observations should or should not be
  // provided by the network quality estimator.
  void ProvideRTTObservations(
      JNIEnv* env,
      const base::android::JavaParamRef<jobject>& jcaller,
      bool should);
  void ProvideThroughputObservations(
      JNIEnv* env,
      const base::android::JavaParamRef<jobject>& jcaller,
      bool should);

 private:
  // Initializes |context_| on the Network thread.
  void InitializeOnNetworkThread(
      std::unique_ptr<URLRequestContextConfig> config,
      const base::android::ScopedJavaGlobalRef<jobject>&
          jcronet_url_request_context);

  // Runs a task that might depend on the context being initialized.
  // This method should only be run on the network thread.
  void RunTaskAfterContextInitOnNetworkThread(
      const base::Closure& task_to_run_after_context_init);

  scoped_refptr<base::SingleThreadTaskRunner> GetNetworkTaskRunner() const;

  // Serializes results of certificate verifications of |context_|'s
  // |cert_verifier| on the Network thread.
  void GetCertVerifierDataOnNetworkThread();

  // Gets the file thread. Create one if there is none.
  base::Thread* GetFileThread();

  // Configures the network quality estimator to observe requests to localhost,
  // as well as to use smaller responses when estimating throughput. This
  // should only be used for testing.
  void ConfigureNetworkQualityEstimatorOnNetworkThreadForTesting(
      bool use_local_host_requests,
      bool use_smaller_responses);

  void ProvideRTTObservationsOnNetworkThread(bool should);
  void ProvideThroughputObservationsOnNetworkThread(bool should);

  // net::NetworkQualityEstimator::RTTObserver implementation.
  void OnRTTObservation(int32_t rtt_ms,
                        const base::TimeTicks& timestamp,
                        net::NetworkQualityObservationSource source) override;

  // net::NetworkQualityEstimator::ThroughputObserver implementation.
  void OnThroughputObservation(
      int32_t throughput_kbps,
      const base::TimeTicks& timestamp,
      net::NetworkQualityObservationSource source) override;

  // Helper method to stop NetLog logging to file. This can be called on any
  // thread. This will flush any remaining writes to disk.
  void StopNetLogHelper();

  // Network thread is owned by |this|, but is destroyed from java thread.
  base::Thread* network_thread_;

  // File thread should be destroyed last.
  std::unique_ptr<base::Thread> file_thread_;

  // |write_to_file_observer_| should only be accessed with
  // |write_to_file_observer_lock_|.
  std::unique_ptr<net::WriteToFileNetLogObserver> write_to_file_observer_;
  base::Lock write_to_file_observer_lock_;

  // |pref_service_| should outlive the HttpServerPropertiesManager owned by
  // |context_|.
  std::unique_ptr<PrefService> pref_service_;
  std::unique_ptr<net::URLRequestContext> context_;
  std::unique_ptr<net::ProxyConfigService> proxy_config_service_;
  scoped_refptr<JsonPrefStore> json_pref_store_;
  net::HttpServerPropertiesManager* http_server_properties_manager_;

  // |sdch_owner_| should be destroyed before |json_pref_store_|, because
  // tearing down |sdch_owner_| forces |json_pref_store_| to flush pending
  // writes to the disk.
  std::unique_ptr<net::SdchOwner> sdch_owner_;

  // Context config is only valid until context is initialized.
  std::unique_ptr<URLRequestContextConfig> context_config_;

  // A queue of tasks that need to be run after context has been initialized.
  std::queue<base::Closure> tasks_waiting_for_context_;
  bool is_context_initialized_;
  int default_load_flags_;

  // A network quality estimator.
  std::unique_ptr<net::NetworkQualityEstimator> network_quality_estimator_;

  // Java object that owns this CronetURLRequestContextAdapter.
  base::android::ScopedJavaGlobalRef<jobject> jcronet_url_request_context_;

#if defined(DATA_REDUCTION_PROXY_SUPPORT)
  std::unique_ptr<CronetDataReductionProxy> data_reduction_proxy_;
#endif

  DISALLOW_COPY_AND_ASSIGN(CronetURLRequestContextAdapter);
};

}  // namespace cronet

#endif  // COMPONENTS_CRONET_ANDROID_CRONET_URL_REQUEST_CONTEXT_ADAPTER_H_
