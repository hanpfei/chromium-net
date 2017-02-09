// Copyright 2016 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "components/cronet/ios/cronet_environment.h"

#include <utility>

#include "base/at_exit.h"
#include "base/atomicops.h"
#include "base/command_line.h"
#include "base/feature_list.h"
#include "base/files/file_path.h"
#include "base/files/file_util.h"
#include "base/files/scoped_file.h"
#include "base/json/json_writer.h"
#include "base/mac/bind_objc_block.h"
#include "base/mac/foundation_util.h"
#include "base/macros.h"
#include "base/metrics/statistics_recorder.h"
#include "base/path_service.h"
#include "base/synchronization/waitable_event.h"
#include "base/threading/worker_pool.h"
#include "components/cronet/ios/version.h"
#include "components/prefs/json_pref_store.h"
#include "components/prefs/pref_filter.h"
#include "net/base/net_errors.h"
#include "net/base/network_change_notifier.h"
#include "net/cert/cert_verify_result.h"
#include "net/cert/ct_policy_enforcer.h"
#include "net/cert/multi_log_ct_verifier.h"
#include "net/dns/host_resolver.h"
#include "net/dns/mapped_host_resolver.h"
#include "net/http/http_auth_handler_factory.h"
#include "net/http/http_cache.h"
#include "net/http/http_response_headers.h"
#include "net/http/http_server_properties_impl.h"
#include "net/http/http_stream_factory.h"
#include "net/http/http_util.h"
#include "net/log/net_log.h"
#include "net/log/write_to_file_net_log_observer.h"
#include "net/proxy/proxy_service.h"
#include "net/socket/ssl_client_socket.h"
#include "net/ssl/channel_id_service.h"
#include "net/ssl/default_channel_id_store.h"
#include "net/ssl/ssl_config_service_defaults.h"
#include "net/url_request/static_http_user_agent_settings.h"
#include "net/url_request/url_request_context_storage.h"
#include "net/url_request/url_request_job_factory_impl.h"
#include "url/scheme_host_port.h"
#include "url/url_util.h"

namespace {

base::AtExitManager* g_at_exit_ = nullptr;
net::NetworkChangeNotifier* g_network_change_notifier = nullptr;
// MessageLoop on the main thread.
base::MessageLoop* g_main_message_loop = nullptr;

}  // namespace

namespace cronet {

bool CronetEnvironment::IsOnNetworkThread() {
  return network_io_thread_->task_runner()->BelongsToCurrentThread();
}

void CronetEnvironment::PostToNetworkThread(
    const tracked_objects::Location& from_here,
    const base::Closure& task) {
  network_io_thread_->task_runner()->PostTask(from_here, task);
}

void CronetEnvironment::PostToFileUserBlockingThread(
    const tracked_objects::Location& from_here,
    const base::Closure& task) {
  file_user_blocking_thread_->message_loop()->PostTask(from_here, task);
}

net::URLRequestContext* CronetEnvironment::GetURLRequestContext() const {
  return main_context_.get();
}

// static
void CronetEnvironment::Initialize() {
  // DCHECK_EQ([NSThread currentThread], [NSThread mainThread]);
  // This method must be called once from the main thread.
  if (!g_at_exit_)
    g_at_exit_ = new base::AtExitManager;

  url::Initialize();
  base::CommandLine::Init(0, nullptr);

  // Without doing this, StatisticsRecorder::FactoryGet() leaks one histogram
  // per call after the first for a given name.
  base::StatisticsRecorder::Initialize();

  // Create a message loop on the UI thread.
  DCHECK(!base::MessageLoop::current());
  DCHECK(!g_main_message_loop);
  g_main_message_loop = new base::MessageLoopForUI();
  base::MessageLoopForUI::current()->Attach();
  // The network change notifier must be initialized so that registered
  // delegates will receive callbacks.
  DCHECK(!g_network_change_notifier);
  g_network_change_notifier = net::NetworkChangeNotifier::Create();
}

void CronetEnvironment::StartNetLog(base::FilePath::StringType file_name,
                                    bool log_bytes) {
  DCHECK(file_name.length());
  PostToNetworkThread(FROM_HERE,
                      base::Bind(&CronetEnvironment::StartNetLogOnNetworkThread,
                                 base::Unretained(this), file_name, log_bytes));
}

void CronetEnvironment::StartNetLogOnNetworkThread(
    const base::FilePath::StringType& file_name,
    bool log_bytes) {
  DCHECK(file_name.length());
  DCHECK(net_log_);

  if (net_log_observer_)
    return;

  base::FilePath files_root;
  if (!PathService::Get(base::DIR_HOME, &files_root))
    return;

  base::FilePath full_path = files_root.Append(file_name);
  base::ScopedFILE file(base::OpenFile(full_path, "w"));
  if (!file) {
    LOG(ERROR) << "Can not start NetLog to " << full_path.value();
    return;
  }

  net::NetLogCaptureMode capture_mode =
      log_bytes ? net::NetLogCaptureMode::IncludeSocketBytes()
                : net::NetLogCaptureMode::Default();

  net_log_observer_.reset(new net::WriteToFileNetLogObserver());
  net_log_observer_->set_capture_mode(capture_mode);
  net_log_observer_->StartObserving(main_context_->net_log(), std::move(file),
                                    nullptr, main_context_.get());
  LOG(WARNING) << "Started NetLog to " << full_path.value();
}

void CronetEnvironment::StopNetLog() {
  base::WaitableEvent log_stopped_event(
      base::WaitableEvent::ResetPolicy::MANUAL,
      base::WaitableEvent::InitialState::NOT_SIGNALED);
  PostToNetworkThread(FROM_HERE,
                      base::Bind(&CronetEnvironment::StopNetLogOnNetworkThread,
                                 base::Unretained(this), &log_stopped_event));
  log_stopped_event.Wait();
}

void CronetEnvironment::StopNetLogOnNetworkThread(
    base::WaitableEvent* log_stopped_event) {
  if (net_log_observer_) {
    DLOG(WARNING) << "Stopped NetLog.";
    net_log_observer_->StopObserving(main_context_.get());
    net_log_observer_.reset();
  }
  log_stopped_event->Signal();
}

net::HttpNetworkSession* CronetEnvironment::GetHttpNetworkSession(
    net::URLRequestContext* context) {
  DCHECK(context);
  if (!context->http_transaction_factory())
    return nullptr;

  return context->http_transaction_factory()->GetSession();
}

void CronetEnvironment::AddQuicHint(const std::string& host,
                                    int port,
                                    int alternate_port) {
  DCHECK(port == alternate_port);
  quic_hints_.push_back(net::HostPortPair(host, port));
}

CronetEnvironment::CronetEnvironment(const std::string& user_agent_product_name)
    : http2_enabled_(false),
      quic_enabled_(false),
      user_agent_product_name_(user_agent_product_name),
      net_log_(new net::NetLog) {}

void CronetEnvironment::Start() {
  // Threads setup.
  network_cache_thread_.reset(new base::Thread("Chrome Network Cache Thread"));
  network_cache_thread_->StartWithOptions(
      base::Thread::Options(base::MessageLoop::TYPE_IO, 0));
  network_io_thread_.reset(new base::Thread("Chrome Network IO Thread"));
  network_io_thread_->StartWithOptions(
      base::Thread::Options(base::MessageLoop::TYPE_IO, 0));
  file_thread_.reset(new base::Thread("Chrome File Thread"));
  file_thread_->StartWithOptions(
      base::Thread::Options(base::MessageLoop::TYPE_IO, 0));
  file_user_blocking_thread_.reset(
      new base::Thread("Chrome File User Blocking Thread"));
  file_user_blocking_thread_->StartWithOptions(
      base::Thread::Options(base::MessageLoop::TYPE_IO, 0));

  static bool ssl_key_log_file_set = false;
  if (!ssl_key_log_file_set && !ssl_key_log_file_name_.empty()) {
    ssl_key_log_file_set = true;
    base::FilePath ssl_key_log_file;
    if (!PathService::Get(base::DIR_HOME, &ssl_key_log_file))
      return;
    net::SSLClientSocket::SetSSLKeyLogFile(
        ssl_key_log_file.Append(ssl_key_log_file_name_),
        file_thread_->task_runner());
  }

  proxy_config_service_ = net::ProxyService::CreateSystemProxyConfigService(
      network_io_thread_->task_runner(), nullptr);

#if defined(USE_NSS_CERTS)
  net::SetURLRequestContextForNSSHttpIO(main_context_.get());
#endif
  base::subtle::MemoryBarrier();
  PostToNetworkThread(FROM_HERE,
                      base::Bind(&CronetEnvironment::InitializeOnNetworkThread,
                                 base::Unretained(this)));
}

CronetEnvironment::~CronetEnvironment() {
// net::HTTPProtocolHandlerDelegate::SetInstance(nullptr);
#if defined(USE_NSS_CERTS)
  net::SetURLRequestContextForNSSHttpIO(nullptr);
#endif
}

void CronetEnvironment::InitializeOnNetworkThread() {
  DCHECK(network_io_thread_->task_runner()->BelongsToCurrentThread());
  base::FeatureList::InitializeInstance(std::string(), std::string());
  // TODO(mef): Use net:UrlRequestContextBuilder instead of manual build.
  main_context_.reset(new net::URLRequestContext);
  main_context_->set_net_log(net_log_.get());
  std::string user_agent(user_agent_product_name_ +
                         " (iOS); Cronet/" CRONET_VERSION);
  main_context_->set_http_user_agent_settings(
      new net::StaticHttpUserAgentSettings("en", user_agent));

  main_context_->set_ssl_config_service(new net::SSLConfigServiceDefaults);
  main_context_->set_transport_security_state(
      new net::TransportSecurityState());
  http_server_properties_.reset(new net::HttpServerPropertiesImpl());
  main_context_->set_http_server_properties(http_server_properties_.get());

  // TODO(rdsmith): Note that the ".release()" calls below are leaking
  // the objects in question; this should be fixed by having an object
  // corresponding to URLRequestContextStorage that actually owns those
  // objects.  See http://crbug.com/523858.
  std::unique_ptr<net::MappedHostResolver> mapped_host_resolver(
      new net::MappedHostResolver(
          net::HostResolver::CreateDefaultResolver(nullptr)));

  mapped_host_resolver->SetRulesFromString(host_resolver_rules_);
  main_context_->set_host_resolver(mapped_host_resolver.release());

  if (!cert_verifier_)
    cert_verifier_ = net::CertVerifier::CreateDefault();
  main_context_->set_cert_verifier(cert_verifier_.get());

  main_context_->set_cert_transparency_verifier(new net::MultiLogCTVerifier());
  main_context_->set_ct_policy_enforcer(new net::CTPolicyEnforcer());

  main_context_->set_http_auth_handler_factory(
      net::HttpAuthHandlerRegistryFactory::CreateDefault(
          main_context_->host_resolver())
          .release());
  main_context_->set_proxy_service(
      net::ProxyService::CreateUsingSystemProxyResolver(
          std::move(proxy_config_service_), 0, nullptr)
          .release());

  // Cache
  base::FilePath cache_path;
  if (!PathService::Get(base::DIR_CACHE, &cache_path))
    return;
  cache_path = cache_path.Append(FILE_PATH_LITERAL("cronet"));
  std::unique_ptr<net::HttpCache::DefaultBackend> main_backend(
      new net::HttpCache::DefaultBackend(net::DISK_CACHE,
                                         net::CACHE_BACKEND_SIMPLE, cache_path,
                                         0,  // Default cache size.
                                         network_cache_thread_->task_runner()));

  net::HttpNetworkSession::Params params;

  params.host_resolver = main_context_->host_resolver();
  params.cert_verifier = main_context_->cert_verifier();
  params.cert_transparency_verifier =
      main_context_->cert_transparency_verifier();
  params.ct_policy_enforcer = main_context_->ct_policy_enforcer();
  params.channel_id_service = main_context_->channel_id_service();
  params.transport_security_state = main_context_->transport_security_state();
  params.proxy_service = main_context_->proxy_service();
  params.ssl_config_service = main_context_->ssl_config_service();
  params.http_auth_handler_factory = main_context_->http_auth_handler_factory();
  params.http_server_properties = main_context_->http_server_properties();
  params.net_log = main_context_->net_log();
  params.enable_http2 = http2_enabled();
  params.enable_quic = quic_enabled();

  for (const auto& quic_hint : quic_hints_) {
    net::AlternativeService alternative_service(net::AlternateProtocol::QUIC,
                                                "", quic_hint.port());
    url::SchemeHostPort quic_hint_server("https", quic_hint.host(),
                                         quic_hint.port());
    main_context_->http_server_properties()->SetAlternativeService(
        quic_hint_server, alternative_service, base::Time::Max());
    params.quic_host_whitelist.insert(quic_hint.host());
  }

  if (!params.channel_id_service) {
    // The main context may not have a ChannelIDService, since it is lazily
    // constructed. If not, build an ephemeral ChannelIDService with no backing
    // disk store.
    // TODO(ellyjones): support persisting ChannelID.
    params.channel_id_service =
        new net::ChannelIDService(new net::DefaultChannelIDStore(NULL),
                                  base::WorkerPool::GetTaskRunner(true));
  }

  // TODO(mmenke):  These really shouldn't be leaked.
  //                See https://crbug.com/523858.
  net::HttpNetworkSession* http_network_session =
      new net::HttpNetworkSession(params);
  net::HttpCache* main_cache =
      new net::HttpCache(http_network_session, std::move(main_backend),
                         true /* set_up_quic_server_info */);
  main_context_->set_http_transaction_factory(main_cache);

  net::URLRequestJobFactoryImpl* job_factory =
      new net::URLRequestJobFactoryImpl;
  main_context_->set_job_factory(job_factory);
  main_context_->set_net_log(net_log_.get());
}

std::string CronetEnvironment::user_agent() {
  const net::HttpUserAgentSettings* user_agent_settings =
      main_context_->http_user_agent_settings();
  if (!user_agent_settings) {
    return nullptr;
  }

  return user_agent_settings->GetUserAgent();
}

}  // namespace cronet
