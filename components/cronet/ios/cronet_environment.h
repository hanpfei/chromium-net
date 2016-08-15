// Copyright 2016 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef COMPONENTS_CRONET_IOS_CRONET_ENVIRONMENT_H_
#define COMPONENTS_CRONET_IOS_CRONET_ENVIRONMENT_H_

#include <list>

#include "base/files/file_path.h"
#include "base/macros.h"
#include "base/message_loop/message_loop.h"
#include "base/synchronization/waitable_event.h"
#include "base/threading/thread.h"
#include "components/cronet/url_request_context_config.h"
#include "net/cert/cert_verifier.h"
#include "net/url_request/url_request.h"
#include "net/url_request/url_request_context.h"

class JsonPrefStore;

namespace base {
class WaitableEvent;
}  // namespace base

namespace net {
class HttpCache;
class NetworkChangeNotifier;
class NetLog;
class ProxyConfigService;
class WriteToFileNetLogObserver;
}  // namespace net

namespace cronet {
// CronetEnvironment contains all the network stack configuration
// and initialization.
class CronetEnvironment {
 public:
  // Initialize Cronet environment globals. Must be called only once on the
  // main thread.
  static void Initialize();

  // |user_agent_product_name| will be used to generate the user-agent.
  CronetEnvironment(const std::string& user_agent_product_name);
  ~CronetEnvironment();

  // Starts this instance of Cronet environment.
  void Start();

  // The full user-agent.
  std::string user_agent();

  // Creates a new net log (overwrites existing file with this name). If
  // actively logging, this call is ignored.
  void StartNetLog(base::FilePath::StringType file_name, bool log_bytes);
  // Stops logging and flushes file. If not currently logging this call is
  // ignored.
  void StopNetLog();

  void AddQuicHint(const std::string& host, int port, int alternate_port);

  // Setters and getters for |http2_enabled_|, |quic_enabled_|, and
  // |forced_quic_origin_|. These only have any effect before Start() is
  // called.
  void set_http2_enabled(bool enabled) { http2_enabled_ = enabled; }
  void set_quic_enabled(bool enabled) { quic_enabled_ = enabled; }

  bool http2_enabled() const { return http2_enabled_; }
  bool quic_enabled() const { return quic_enabled_; }

  void set_cert_verifier(std::unique_ptr<net::CertVerifier> cert_verifier) {
    cert_verifier_ = std::move(cert_verifier);
  }

  void set_host_resolver_rules(const std::string& host_resolver_rules) {
    host_resolver_rules_ = host_resolver_rules;
  }

  void set_ssl_key_log_file_name(const std::string& ssl_key_log_file_name) {
    ssl_key_log_file_name_ = ssl_key_log_file_name;
  }

  net::URLRequestContext* GetURLRequestContext() const;

  bool IsOnNetworkThread();

  // Runs a closure on the network thread.
  void PostToNetworkThread(const tracked_objects::Location& from_here,
                           const base::Closure& task);

 private:
  // Performs initialization tasks that must happen on the network thread.
  void InitializeOnNetworkThread();

  // Runs a closure on the file user blocking thread.
  void PostToFileUserBlockingThread(const tracked_objects::Location& from_here,
                                    const base::Closure& task);

  // Helper methods that start/stop net logging on the network thread.
  void StartNetLogOnNetworkThread(const base::FilePath::StringType& file_name,
                                  bool log_bytes);
  void StopNetLogOnNetworkThread(base::WaitableEvent* log_stopped_event);

  // Returns the HttpNetworkSession object from the passed in
  // URLRequestContext or NULL if none exists.
  net::HttpNetworkSession* GetHttpNetworkSession(
      net::URLRequestContext* context);

  bool http2_enabled_;
  bool quic_enabled_;
  std::string host_resolver_rules_;
  std::string ssl_key_log_file_name_;

  std::list<net::HostPortPair> quic_hints_;

  std::unique_ptr<base::Thread> network_io_thread_;
  std::unique_ptr<base::Thread> network_cache_thread_;
  std::unique_ptr<base::Thread> file_thread_;
  std::unique_ptr<base::Thread> file_user_blocking_thread_;
  scoped_refptr<base::SequencedTaskRunner> pref_store_worker_pool_;
  scoped_refptr<JsonPrefStore> net_pref_store_;
  std::unique_ptr<net::CertVerifier> cert_verifier_;
  std::unique_ptr<net::ProxyConfigService> proxy_config_service_;
  std::unique_ptr<net::HttpServerProperties> http_server_properties_;
  std::unique_ptr<net::URLRequestContext> main_context_;
  std::string user_agent_product_name_;
  std::unique_ptr<net::NetLog> net_log_;
  std::unique_ptr<net::WriteToFileNetLogObserver> net_log_observer_;

  DISALLOW_COPY_AND_ASSIGN(CronetEnvironment);
};

}  // namespace cronet

#endif  // COMPONENTS_CRONET_IOS_CRONET_ENVIRONMENT_H_
