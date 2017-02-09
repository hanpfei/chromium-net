// Copyright 2014 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "components/cronet/android/url_request_context_adapter.h"

#include <stddef.h>
#include <stdint.h>

#include <limits>
#include <utility>

#include "base/bind.h"
#include "base/files/file_util.h"
#include "base/files/scoped_file.h"
#include "base/macros.h"
#include "base/memory/ptr_util.h"
#include "base/message_loop/message_loop.h"
#include "base/single_thread_task_runner.h"
#include "base/time/time.h"
#include "components/cronet/url_request_context_config.h"
#include "net/android/network_change_notifier_factory_android.h"
#include "net/base/net_errors.h"
#include "net/base/network_change_notifier.h"
#include "net/base/network_delegate_impl.h"
#include "net/base/url_util.h"
#include "net/cert/cert_verifier.h"
#include "net/http/http_auth_handler_factory.h"
#include "net/http/http_network_layer.h"
#include "net/http/http_server_properties.h"
#include "net/log/write_to_file_net_log_observer.h"
#include "net/proxy/proxy_service.h"
#include "net/sdch/sdch_owner.h"
#include "net/ssl/ssl_config_service_defaults.h"
#include "net/url_request/static_http_user_agent_settings.h"
#include "net/url_request/url_request_context_builder.h"
#include "net/url_request/url_request_context_storage.h"
#include "net/url_request/url_request_job_factory_impl.h"
#include "url/scheme_host_port.h"

namespace {

class BasicNetworkDelegate : public net::NetworkDelegateImpl {
 public:
  BasicNetworkDelegate() {}
  ~BasicNetworkDelegate() override {}

 private:
  // net::NetworkDelegate implementation.
  int OnBeforeURLRequest(net::URLRequest* request,
                         const net::CompletionCallback& callback,
                         GURL* new_url) override {
    return net::OK;
  }

  int OnBeforeStartTransaction(net::URLRequest* request,
                               const net::CompletionCallback& callback,
                               net::HttpRequestHeaders* headers) override {
    return net::OK;
  }

  void OnStartTransaction(net::URLRequest* request,
                          const net::HttpRequestHeaders& headers) override {}

  int OnHeadersReceived(
      net::URLRequest* request,
      const net::CompletionCallback& callback,
      const net::HttpResponseHeaders* original_response_headers,
      scoped_refptr<net::HttpResponseHeaders>* _response_headers,
      GURL* allowed_unsafe_redirect_url) override {
    return net::OK;
  }

  void OnBeforeRedirect(net::URLRequest* request,
                        const GURL& new_location) override {}

  void OnResponseStarted(net::URLRequest* request) override {}

  void OnCompleted(net::URLRequest* request, bool started) override {}

  void OnURLRequestDestroyed(net::URLRequest* request) override {}

  void OnPACScriptError(int line_number,
                        const base::string16& error) override {}

  NetworkDelegate::AuthRequiredResponse OnAuthRequired(
      net::URLRequest* request,
      const net::AuthChallengeInfo& auth_info,
      const AuthCallback& callback,
      net::AuthCredentials* credentials) override {
    return net::NetworkDelegate::AUTH_REQUIRED_RESPONSE_NO_ACTION;
  }

  bool OnCanGetCookies(const net::URLRequest& request,
                       const net::CookieList& cookie_list) override {
    return false;
  }

  bool OnCanSetCookie(const net::URLRequest& request,
                      const std::string& cookie_line,
                      net::CookieOptions* options) override {
    return false;
  }

  bool OnCanAccessFile(const net::URLRequest& request,
                       const base::FilePath& path) const override {
    return false;
  }

  DISALLOW_COPY_AND_ASSIGN(BasicNetworkDelegate);
};

}  // namespace

namespace cronet {

URLRequestContextAdapter::URLRequestContextAdapter(
    URLRequestContextAdapterDelegate* delegate,
    std::string user_agent)
    : load_disable_cache_(true),
      is_context_initialized_(false) {
  delegate_ = delegate;
  user_agent_ = user_agent;
}

void URLRequestContextAdapter::Initialize(
    std::unique_ptr<URLRequestContextConfig> config) {
  network_thread_ = new base::Thread("network");
  base::Thread::Options options;
  options.message_loop_type = base::MessageLoop::TYPE_IO;
  network_thread_->StartWithOptions(options);
  config_ = std::move(config);
}

void URLRequestContextAdapter::InitRequestContextOnMainThread() {
  proxy_config_service_ = net::ProxyService::CreateSystemProxyConfigService(
      GetNetworkTaskRunner(), NULL);
  GetNetworkTaskRunner()->PostTask(
      FROM_HERE,
      base::Bind(&URLRequestContextAdapter::InitRequestContextOnNetworkThread,
                 this));
}

void URLRequestContextAdapter::InitRequestContextOnNetworkThread() {
  DCHECK(GetNetworkTaskRunner()->BelongsToCurrentThread());
  DCHECK(config_);
  // TODO(mmenke):  Add method to have the builder enable SPDY.
  net::URLRequestContextBuilder context_builder;

  context_builder.set_network_delegate(
      base::WrapUnique(new BasicNetworkDelegate()));
  context_builder.set_proxy_config_service(std::move(proxy_config_service_));
  config_->ConfigureURLRequestContextBuilder(&context_builder, nullptr,
                                             nullptr);

  context_ = context_builder.Build();

  if (config_->enable_sdch) {
    DCHECK(context_->sdch_manager());
    sdch_owner_.reset(
        new net::SdchOwner(context_->sdch_manager(), context_.get()));
  }

  if (config_->enable_quic) {
    for (size_t hint = 0; hint < config_->quic_hints.size(); ++hint) {
      const URLRequestContextConfig::QuicHint& quic_hint =
          *config_->quic_hints[hint];
      if (quic_hint.host.empty()) {
        LOG(ERROR) << "Empty QUIC hint host: " << quic_hint.host;
        continue;
      }

      url::CanonHostInfo host_info;
      std::string canon_host(net::CanonicalizeHost(quic_hint.host, &host_info));
      if (!host_info.IsIPAddress() &&
          !net::IsCanonicalizedHostCompliant(canon_host)) {
        LOG(ERROR) << "Invalid QUIC hint host: " << quic_hint.host;
        continue;
      }

      if (quic_hint.port <= std::numeric_limits<uint16_t>::min() ||
          quic_hint.port > std::numeric_limits<uint16_t>::max()) {
        LOG(ERROR) << "Invalid QUIC hint port: "
                   << quic_hint.port;
        continue;
      }

      if (quic_hint.alternate_port <= std::numeric_limits<uint16_t>::min() ||
          quic_hint.alternate_port > std::numeric_limits<uint16_t>::max()) {
        LOG(ERROR) << "Invalid QUIC hint alternate port: "
                   << quic_hint.alternate_port;
        continue;
      }

      url::SchemeHostPort quic_server("https", canon_host, quic_hint.port);
      net::AlternativeService alternative_service(
          net::AlternateProtocol::QUIC, "",
          static_cast<uint16_t>(quic_hint.alternate_port));
      context_->http_server_properties()->SetAlternativeService(
          quic_server, alternative_service, base::Time::Max());
    }
  }
  load_disable_cache_ = config_->load_disable_cache;
  config_.reset(NULL);

  if (VLOG_IS_ON(2)) {
    net_log_observer_.reset(new NetLogObserver());
    context_->net_log()->DeprecatedAddObserver(
        net_log_observer_.get(),
        net::NetLogCaptureMode::IncludeCookiesAndCredentials());
  }

  is_context_initialized_ = true;
  while (!tasks_waiting_for_context_.empty()) {
    tasks_waiting_for_context_.front().Run();
    tasks_waiting_for_context_.pop();
  }

  delegate_->OnContextInitialized(this);
}

void URLRequestContextAdapter::PostTaskToNetworkThread(
    const tracked_objects::Location& posted_from,
    const RunAfterContextInitTask& callback) {
  GetNetworkTaskRunner()->PostTask(
      posted_from,
      base::Bind(
          &URLRequestContextAdapter::RunTaskAfterContextInitOnNetworkThread,
          this,
          callback));
}

void URLRequestContextAdapter::RunTaskAfterContextInitOnNetworkThread(
    const RunAfterContextInitTask& callback) {
  DCHECK(GetNetworkTaskRunner()->BelongsToCurrentThread());
  if (is_context_initialized_) {
    callback.Run();
    return;
  }
  tasks_waiting_for_context_.push(callback);
}

URLRequestContextAdapter::~URLRequestContextAdapter() {
  DCHECK(GetNetworkTaskRunner()->BelongsToCurrentThread());
  if (net_log_observer_) {
    context_->net_log()->DeprecatedRemoveObserver(net_log_observer_.get());
    net_log_observer_.reset();
  }
  StopNetLogHelper();
  // TODO(mef): Ensure that |network_thread_| is destroyed properly.
}

const std::string& URLRequestContextAdapter::GetUserAgent(
    const GURL& url) const {
  return user_agent_;
}

net::URLRequestContext* URLRequestContextAdapter::GetURLRequestContext() {
  DCHECK(GetNetworkTaskRunner()->BelongsToCurrentThread());
  if (!context_) {
    LOG(ERROR) << "URLRequestContext is not set up";
  }
  return context_.get();
}

scoped_refptr<base::SingleThreadTaskRunner>
URLRequestContextAdapter::GetNetworkTaskRunner() const {
  return network_thread_->task_runner();
}

void URLRequestContextAdapter::StartNetLogToFile(const std::string& file_name,
                                                 bool log_all) {
  PostTaskToNetworkThread(
      FROM_HERE,
      base::Bind(&URLRequestContextAdapter::StartNetLogToFileHelper, this,
                 file_name, log_all));
}

void URLRequestContextAdapter::StopNetLog() {
  PostTaskToNetworkThread(
      FROM_HERE, base::Bind(&URLRequestContextAdapter::StopNetLogHelper, this));
}

void URLRequestContextAdapter::StartNetLogToFileHelper(
    const std::string& file_name, bool log_all) {
  DCHECK(GetNetworkTaskRunner()->BelongsToCurrentThread());
  // Do nothing if already logging to a file.
  if (write_to_file_observer_)
    return;

  base::FilePath file_path(file_name);
  base::ScopedFILE file(base::OpenFile(file_path, "w"));
  if (!file)
    return;

  write_to_file_observer_.reset(new net::WriteToFileNetLogObserver());
  if (log_all) {
    write_to_file_observer_->set_capture_mode(
        net::NetLogCaptureMode::IncludeSocketBytes());
  }
  write_to_file_observer_->StartObserving(context_->net_log(), std::move(file),
                                          nullptr, context_.get());
}

void URLRequestContextAdapter::StopNetLogHelper() {
  DCHECK(GetNetworkTaskRunner()->BelongsToCurrentThread());
  if (write_to_file_observer_) {
    write_to_file_observer_->StopObserving(context_.get());
    write_to_file_observer_.reset();
  }
}

void NetLogObserver::OnAddEntry(const net::NetLog::Entry& entry) {
  VLOG(2) << "Net log entry: type=" << entry.type()
          << ", source=" << entry.source().type << ", phase=" << entry.phase();
}

}  // namespace cronet
