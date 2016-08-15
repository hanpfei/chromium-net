// Copyright 2015 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "components/cronet/url_request_context_config.h"

#include "base/memory/ptr_util.h"
#include "base/values.h"
#include "net/cert/cert_verifier.h"
#include "net/http/http_network_session.h"
#include "net/proxy/proxy_config.h"
#include "net/proxy/proxy_config_service_fixed.h"
#include "net/url_request/url_request_context.h"
#include "net/url_request/url_request_context_builder.h"
#include "testing/gtest/include/gtest/gtest.h"

namespace cronet {

TEST(URLRequestContextConfigTest, SetQuicExperimentalOptions) {
  URLRequestContextConfig config(
      // Enable QUIC.
      true,
      // QUIC User Agent ID.
      "Default QUIC User Agent ID",
      // Enable SPDY.
      true,
      // Enable SDCH.
      false,
      // Type of http cache.
      URLRequestContextConfig::HttpCacheType::DISK,
      // Max size of http cache in bytes.
      1024000,
      // Disable caching for HTTP responses. Other information may be stored in
      // the cache.
      false,
      // Storage path for http cache and cookie storage.
      "/data/data/org.chromium.net/app_cronet_test/test_storage",
      // User-Agent request header field.
      "fake agent",
      // JSON encoded experimental options.
      "{\"QUIC\":{\"max_server_configs_stored_in_properties\":2,"
      "\"delay_tcp_race\":true,"
      "\"max_number_of_lossy_connections\":10,"
      "\"prefer_aes\":true,"
      "\"user_agent_id\":\"Custom QUIC UAID\","
      "\"packet_loss_threshold\":0.5,"
      "\"idle_connection_timeout_seconds\":300,"
      "\"close_sessions_on_ip_change\":true,"
      "\"race_cert_verification\":true,"
      "\"connection_options\":\"TIME,TBBR,REJ\"},"
      "\"AsyncDNS\":{\"enable\":true}}",
      // Data reduction proxy key.
      "",
      // Data reduction proxy.
      "",
      // Fallback data reduction proxy.
      "",
      // Data reduction proxy secure proxy check URL.
      "",
      // MockCertVerifier to use for testing purposes.
      std::unique_ptr<net::CertVerifier>(),
      // Enable network quality estimator.
      false,
      // Enable Public Key Pinning bypass for local trust anchors.
      true,
      // Certificate verifier cache data.
      "");

  net::URLRequestContextBuilder builder;
  net::NetLog net_log;
  config.ConfigureURLRequestContextBuilder(&builder, &net_log, nullptr);
  // Set a ProxyConfigService to avoid DCHECK failure when building.
  builder.set_proxy_config_service(base::WrapUnique(
      new net::ProxyConfigServiceFixed(net::ProxyConfig::CreateDirect())));
  std::unique_ptr<net::URLRequestContext> context(builder.Build());
  const net::HttpNetworkSession::Params* params =
      context->GetNetworkSessionParams();
  // Check Quic Connection options.
  net::QuicTagVector quic_connection_options;
  quic_connection_options.push_back(net::kTIME);
  quic_connection_options.push_back(net::kTBBR);
  quic_connection_options.push_back(net::kREJ);
  EXPECT_EQ(quic_connection_options, params->quic_connection_options);

  // Check Custom QUIC User Agent Id.
  EXPECT_EQ("Custom QUIC UAID", params->quic_user_agent_id);

  // Check max_server_configs_stored_in_properties.
  EXPECT_EQ(2u, params->quic_max_server_configs_stored_in_properties);

  // Check delay_tcp_race.
  EXPECT_TRUE(params->quic_delay_tcp_race);

  // Check prefer_aes.
  EXPECT_TRUE(params->quic_prefer_aes);

  // Check max_number_of_lossy_connections and packet_loss_threshold.
  EXPECT_EQ(10, params->quic_max_number_of_lossy_connections);
  EXPECT_FLOAT_EQ(0.5f, params->quic_packet_loss_threshold);

  // Check idle_connection_timeout_seconds.
  EXPECT_EQ(300, params->quic_idle_connection_timeout_seconds);

  EXPECT_TRUE(params->quic_close_sessions_on_ip_change);
  EXPECT_FALSE(params->quic_migrate_sessions_on_network_change);

  // Check race_cert_verification.
  EXPECT_TRUE(params->quic_race_cert_verification);

  // Check AsyncDNS resolver is enabled.
  EXPECT_TRUE(context->host_resolver()->GetDnsConfigAsValue());
}

TEST(URLRequestContextConfigTest, SetQuicConnectionMigrationOptions) {
  URLRequestContextConfig config(
      // Enable QUIC.
      true,
      // QUIC User Agent ID.
      "Default QUIC User Agent ID",
      // Enable SPDY.
      true,
      // Enable SDCH.
      false,
      // Type of http cache.
      URLRequestContextConfig::HttpCacheType::DISK,
      // Max size of http cache in bytes.
      1024000,
      // Disable caching for HTTP responses. Other information may be stored in
      // the cache.
      false,
      // Storage path for http cache and cookie storage.
      "/data/data/org.chromium.net/app_cronet_test/test_storage",
      // User-Agent request header field.
      "fake agent",
      // JSON encoded experimental options.
      "{\"QUIC\":{\"migrate_sessions_on_network_change\":true,"
      "\"migrate_sessions_early\":true}}",
      // Data reduction proxy key.
      "",
      // Data reduction proxy.
      "",
      // Fallback data reduction proxy.
      "",
      // Data reduction proxy secure proxy check URL.
      "",
      // MockCertVerifier to use for testing purposes.
      std::unique_ptr<net::CertVerifier>(),
      // Enable network quality estimator.
      false,
      // Enable Public Key Pinning bypass for local trust anchors.
      true,
      // Certificate verifier cache data.
      "");

  net::URLRequestContextBuilder builder;
  net::NetLog net_log;
  config.ConfigureURLRequestContextBuilder(&builder, &net_log, nullptr);
  // Set a ProxyConfigService to avoid DCHECK failure when building.
  builder.set_proxy_config_service(base::WrapUnique(
      new net::ProxyConfigServiceFixed(net::ProxyConfig::CreateDirect())));
  std::unique_ptr<net::URLRequestContext> context(builder.Build());
  const net::HttpNetworkSession::Params* params =
      context->GetNetworkSessionParams();

  EXPECT_FALSE(params->quic_close_sessions_on_ip_change);
  EXPECT_TRUE(params->quic_migrate_sessions_on_network_change);
  EXPECT_TRUE(params->quic_migrate_sessions_early);
}

}  // namespace cronet
