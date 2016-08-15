// Copyright 2014 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "components/cronet/url_request_context_config.h"

#include <utility>

#include "base/json/json_reader.h"
#include "base/logging.h"
#include "base/memory/ptr_util.h"
#include "base/sequenced_task_runner.h"
#include "base/strings/string_number_conversions.h"
#include "base/strings/string_piece.h"
#include "base/strings/string_split.h"
#include "base/values.h"
#include "net/cert/caching_cert_verifier.h"
#include "net/cert/cert_verifier.h"
#include "net/cert/cert_verify_proc.h"
#include "net/cert/multi_threaded_cert_verifier.h"
#include "net/dns/host_resolver.h"
#include "net/http/http_server_properties.h"
#include "net/quic/core/quic_protocol.h"
#include "net/quic/core/quic_utils.h"
#include "net/socket/ssl_client_socket.h"
#include "net/url_request/url_request_context_builder.h"

namespace cronet {

namespace {

// Name of disk cache directory.
const char kDiskCacheDirectoryName[] = "disk_cache";
// TODO(xunjieli): Refactor constants in io_thread.cc.
const char kQuicFieldTrialName[] = "QUIC";
const char kQuicConnectionOptions[] = "connection_options";
const char kQuicStoreServerConfigsInProperties[] =
    "store_server_configs_in_properties";
const char kQuicMaxServerConfigsStoredInProperties[] =
    "max_server_configs_stored_in_properties";
const char kQuicDelayTcpRace[] = "delay_tcp_race";
const char kQuicMaxNumberOfLossyConnections[] =
    "max_number_of_lossy_connections";
const char kQuicPacketLossThreshold[] = "packet_loss_threshold";
const char kQuicIdleConnectionTimeoutSeconds[] =
    "idle_connection_timeout_seconds";
const char kQuicHostWhitelist[] = "host_whitelist";
const char kQuicCloseSessionsOnIpChange[] = "close_sessions_on_ip_change";
const char kQuicMigrateSessionsOnNetworkChange[] =
    "migrate_sessions_on_network_change";
const char kQuicPreferAes[] = "prefer_aes";
const char kQuicUserAgentId[] = "user_agent_id";
const char kQuicMigrateSessionsEarly[] = "migrate_sessions_early";
const char kQuicDisableBidirectionalStreams[] =
    "quic_disable_bidirectional_streams";
const char kQuicRaceCertVerification[] = "race_cert_verification";

// AsyncDNS experiment dictionary name.
const char kAsyncDnsFieldTrialName[] = "AsyncDNS";
// Name of boolean to enable AsyncDNS experiment.
const char kAsyncDnsEnable[] = "enable";

const char kSSLKeyLogFile[] = "ssl_key_log_file";

void ParseAndSetExperimentalOptions(
    const std::string& experimental_options,
    net::URLRequestContextBuilder* context_builder,
    net::NetLog* net_log,
    const scoped_refptr<base::SequencedTaskRunner>& file_task_runner) {
  if (experimental_options.empty())
    return;

  DVLOG(1) << "Experimental Options:" << experimental_options;
  std::unique_ptr<base::Value> options =
      base::JSONReader::Read(experimental_options);

  if (!options) {
    DCHECK(false) << "Parsing experimental options failed: "
                  << experimental_options;
    return;
  }

  std::unique_ptr<base::DictionaryValue> dict =
      base::DictionaryValue::From(std::move(options));

  if (!dict) {
    DCHECK(false) << "Experimental options string is not a dictionary: "
                  << experimental_options;
    return;
  }

  const base::DictionaryValue* quic_args = nullptr;
  if (dict->GetDictionary(kQuicFieldTrialName, &quic_args)) {
    std::string quic_connection_options;
    if (quic_args->GetString(kQuicConnectionOptions,
                             &quic_connection_options)) {
      context_builder->set_quic_connection_options(
          net::QuicUtils::ParseQuicConnectionOptions(quic_connection_options));
    }

    // TODO(rtenneti): Delete this option after apps stop using it.
    // Added this for backward compatibility.
    bool quic_store_server_configs_in_properties = false;
    if (quic_args->GetBoolean(kQuicStoreServerConfigsInProperties,
                              &quic_store_server_configs_in_properties)) {
      context_builder->set_quic_max_server_configs_stored_in_properties(
          net::kMaxQuicServersToPersist);
    }

    int quic_max_server_configs_stored_in_properties = 0;
    if (quic_args->GetInteger(kQuicMaxServerConfigsStoredInProperties,
                              &quic_max_server_configs_stored_in_properties)) {
      context_builder->set_quic_max_server_configs_stored_in_properties(
          static_cast<size_t>(quic_max_server_configs_stored_in_properties));
    }

    bool quic_delay_tcp_race = false;
    if (quic_args->GetBoolean(kQuicDelayTcpRace, &quic_delay_tcp_race)) {
      context_builder->set_quic_delay_tcp_race(quic_delay_tcp_race);
    }

    int quic_max_number_of_lossy_connections = 0;
    if (quic_args->GetInteger(kQuicMaxNumberOfLossyConnections,
                              &quic_max_number_of_lossy_connections)) {
      context_builder->set_quic_max_number_of_lossy_connections(
          quic_max_number_of_lossy_connections);
    }

    double quic_packet_loss_threshold = 0.0;
    if (quic_args->GetDouble(kQuicPacketLossThreshold,
                             &quic_packet_loss_threshold)) {
      context_builder->set_quic_packet_loss_threshold(
          quic_packet_loss_threshold);
    }

    int quic_idle_connection_timeout_seconds = 0;
    if (quic_args->GetInteger(kQuicIdleConnectionTimeoutSeconds,
                              &quic_idle_connection_timeout_seconds)) {
      context_builder->set_quic_idle_connection_timeout_seconds(
          quic_idle_connection_timeout_seconds);
    }

    std::string quic_host_whitelist;
    if (quic_args->GetString(kQuicHostWhitelist, &quic_host_whitelist)) {
      std::unordered_set<std::string> hosts;
      for (const std::string& host :
           base::SplitString(quic_host_whitelist, ",", base::TRIM_WHITESPACE,
                             base::SPLIT_WANT_ALL)) {
        hosts.insert(host);
      }
      context_builder->set_quic_host_whitelist(hosts);
    }

    bool quic_close_sessions_on_ip_change = false;
    if (quic_args->GetBoolean(kQuicCloseSessionsOnIpChange,
                              &quic_close_sessions_on_ip_change)) {
      context_builder->set_quic_close_sessions_on_ip_change(
          quic_close_sessions_on_ip_change);
    }

    bool quic_migrate_sessions_on_network_change = false;
    if (quic_args->GetBoolean(kQuicMigrateSessionsOnNetworkChange,
                              &quic_migrate_sessions_on_network_change)) {
      context_builder->set_quic_migrate_sessions_on_network_change(
          quic_migrate_sessions_on_network_change);
    }

    bool quic_prefer_aes = false;
    if (quic_args->GetBoolean(kQuicPreferAes, &quic_prefer_aes)) {
      context_builder->set_quic_prefer_aes(quic_prefer_aes);
    }

    std::string quic_user_agent_id;
    if (quic_args->GetString(kQuicUserAgentId, &quic_user_agent_id)) {
      context_builder->set_quic_user_agent_id(quic_user_agent_id);
    }

    bool quic_migrate_sessions_early = false;
    if (quic_args->GetBoolean(kQuicMigrateSessionsEarly,
                              &quic_migrate_sessions_early)) {
      context_builder->set_quic_migrate_sessions_early(
          quic_migrate_sessions_early);
    }

    bool quic_disable_bidirectional_streams = false;
    if (quic_args->GetBoolean(kQuicDisableBidirectionalStreams,
                              &quic_disable_bidirectional_streams)) {
      context_builder->set_quic_disable_bidirectional_streams(
          quic_disable_bidirectional_streams);
    }

    bool quic_race_cert_verification = false;
    if (quic_args->GetBoolean(kQuicRaceCertVerification,
                              &quic_race_cert_verification)) {
      context_builder->set_quic_race_cert_verification(
          quic_race_cert_verification);
    }
  }

  const base::DictionaryValue* async_dns_args = nullptr;
  if (dict->GetDictionary(kAsyncDnsFieldTrialName, &async_dns_args)) {
    bool async_dns_enable = false;
    if (async_dns_args->GetBoolean(kAsyncDnsEnable, &async_dns_enable) &&
        async_dns_enable) {
      if (net_log == nullptr) {
        DCHECK(false) << "AsyncDNS experiment requires NetLog.";
      } else {
        std::unique_ptr<net::HostResolver> host_resolver(
            net::HostResolver::CreateDefaultResolver(net_log));
        host_resolver->SetDnsClientEnabled(true);
        context_builder->set_host_resolver(std::move(host_resolver));
      }
    }
  }

  std::string ssl_key_log_file_string;
  if (dict->GetString(kSSLKeyLogFile, &ssl_key_log_file_string)) {
    DCHECK(file_task_runner);
    base::FilePath ssl_key_log_file(ssl_key_log_file_string);
    if (!ssl_key_log_file.empty() && file_task_runner) {
      // SetSSLKeyLogFile is only safe to call before any SSLClientSockets are
      // created. This should not be used if there are multiple CronetEngine.
      // TODO(xunjieli): Expose this as a stable API after crbug.com/458365 is
      // resolved.
      net::SSLClientSocket::SetSSLKeyLogFile(ssl_key_log_file,
                                             file_task_runner);
    }
  }
}

}  // namespace

URLRequestContextConfig::QuicHint::QuicHint(const std::string& host,
                                            int port,
                                            int alternate_port)
    : host(host), port(port), alternate_port(alternate_port) {}

URLRequestContextConfig::QuicHint::~QuicHint() {}

URLRequestContextConfig::Pkp::Pkp(const std::string& host,
                                  bool include_subdomains,
                                  const base::Time& expiration_date)
    : host(host),
      include_subdomains(include_subdomains),
      expiration_date(expiration_date) {}

URLRequestContextConfig::Pkp::~Pkp() {}

URLRequestContextConfig::URLRequestContextConfig(
    bool enable_quic,
    const std::string& quic_user_agent_id,
    bool enable_spdy,
    bool enable_sdch,
    HttpCacheType http_cache,
    int http_cache_max_size,
    bool load_disable_cache,
    const std::string& storage_path,
    const std::string& user_agent,
    const std::string& experimental_options,
    const std::string& data_reduction_proxy_key,
    const std::string& data_reduction_primary_proxy,
    const std::string& data_reduction_fallback_proxy,
    const std::string& data_reduction_secure_proxy_check_url,
    std::unique_ptr<net::CertVerifier> mock_cert_verifier,
    bool enable_network_quality_estimator,
    bool bypass_public_key_pinning_for_local_trust_anchors,
    const std::string& cert_verifier_data)
    : enable_quic(enable_quic),
      quic_user_agent_id(quic_user_agent_id),
      enable_spdy(enable_spdy),
      enable_sdch(enable_sdch),
      http_cache(http_cache),
      http_cache_max_size(http_cache_max_size),
      load_disable_cache(load_disable_cache),
      storage_path(storage_path),
      user_agent(user_agent),
      experimental_options(experimental_options),
      data_reduction_proxy_key(data_reduction_proxy_key),
      data_reduction_primary_proxy(data_reduction_primary_proxy),
      data_reduction_fallback_proxy(data_reduction_fallback_proxy),
      data_reduction_secure_proxy_check_url(
          data_reduction_secure_proxy_check_url),
      mock_cert_verifier(std::move(mock_cert_verifier)),
      enable_network_quality_estimator(enable_network_quality_estimator),
      bypass_public_key_pinning_for_local_trust_anchors(
          bypass_public_key_pinning_for_local_trust_anchors),
      cert_verifier_data(cert_verifier_data) {}

URLRequestContextConfig::~URLRequestContextConfig() {}

void URLRequestContextConfig::ConfigureURLRequestContextBuilder(
    net::URLRequestContextBuilder* context_builder,
    net::NetLog* net_log,
    const scoped_refptr<base::SequencedTaskRunner>& file_task_runner) {
  std::string config_cache;
  if (http_cache != DISABLED) {
    net::URLRequestContextBuilder::HttpCacheParams cache_params;
    if (http_cache == DISK && !storage_path.empty()) {
      cache_params.type = net::URLRequestContextBuilder::HttpCacheParams::DISK;
      cache_params.path =
          base::FilePath(storage_path)
              .Append(FILE_PATH_LITERAL(kDiskCacheDirectoryName));
    } else {
      cache_params.type =
          net::URLRequestContextBuilder::HttpCacheParams::IN_MEMORY;
    }
    cache_params.max_size = http_cache_max_size;
    context_builder->EnableHttpCache(cache_params);
  } else {
    context_builder->DisableHttpCache();
  }
  context_builder->set_user_agent(user_agent);
  context_builder->SetSpdyAndQuicEnabled(enable_spdy, enable_quic);
  context_builder->set_sdch_enabled(enable_sdch);
  if (enable_quic)
    context_builder->set_quic_user_agent_id(quic_user_agent_id);

  ParseAndSetExperimentalOptions(experimental_options, context_builder, net_log,
                                 file_task_runner);

  std::unique_ptr<net::CertVerifier> cert_verifier;
  if (mock_cert_verifier) {
    // Because |context_builder| expects CachingCertVerifier, wrap
    // |mock_cert_verifier| into a CachingCertVerifier.
    cert_verifier = base::MakeUnique<net::CachingCertVerifier>(
        std::move(mock_cert_verifier));
  } else {
    // net::CertVerifier::CreateDefault() returns a CachingCertVerifier.
    cert_verifier = net::CertVerifier::CreateDefault();
  }
  context_builder->SetCertVerifier(std::move(cert_verifier));
  // TODO(mef): Use |config| to set cookies.
}

}  // namespace cronet
