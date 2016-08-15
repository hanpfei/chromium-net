// Copyright 2014 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef COMPONENTS_CRONET_URL_REQUEST_CONTEXT_CONFIG_H_
#define COMPONENTS_CRONET_URL_REQUEST_CONTEXT_CONFIG_H_

#include <memory>
#include <string>

#include "base/macros.h"
#include "base/memory/ref_counted.h"
#include "base/memory/scoped_vector.h"
#include "base/time/time.h"
#include "net/base/hash_value.h"

namespace base {
class SequencedTaskRunner;
}  // namespace base

namespace net {
class CertVerifier;
class NetLog;
class URLRequestContextBuilder;
}  // namespace net

namespace cronet {

// Common configuration parameters used by Cronet to configure
// URLRequestContext.
struct URLRequestContextConfig {
  // Type of HTTP cache.
  // GENERATED_JAVA_ENUM_PACKAGE: org.chromium.net
  enum HttpCacheType {
    // No HTTP cache.
    DISABLED,
    // HTTP cache persisted to disk.
    DISK,
    // HTTP cache kept in memory.
    MEMORY,
  };

  // App-provided hint that server supports QUIC.
  struct QuicHint {
    QuicHint(const std::string& host, int port, int alternate_port);
    ~QuicHint();

    // Host name of the server that supports QUIC.
    const std::string host;
    // Port of the server that supports QUIC.
    const int port;
    // Alternate protocol port.
    const int alternate_port;

   private:
    DISALLOW_COPY_AND_ASSIGN(QuicHint);
  };

  // Public-Key-Pinning configuration structure.
  struct Pkp {
    Pkp(const std::string& host,
        bool include_subdomains,
        const base::Time& expiration_date);
    ~Pkp();

    // Host name.
    const std::string host;
    // Pin hashes (currently SHA256 only).
    net::HashValueVector pin_hashes;
    // Indicates whether the pinning should apply to the pinned host subdomains.
    const bool include_subdomains;
    // Expiration date for the pins.
    const base::Time expiration_date;

   private:
    DISALLOW_COPY_AND_ASSIGN(Pkp);
  };

  URLRequestContextConfig(
      // Enable QUIC.
      bool enable_quic,
      // QUIC User Agent ID.
      const std::string& quic_user_agent_id,
      // Enable SPDY.
      bool enable_spdy,
      // Enable SDCH.
      bool enable_sdch,
      // Type of http cache.
      HttpCacheType http_cache,
      // Max size of http cache in bytes.
      int http_cache_max_size,
      // Disable caching for HTTP responses. Other information may be stored in
      // the cache.
      bool load_disable_cache,
      // Storage path for http cache and cookie storage.
      const std::string& storage_path,
      // User-Agent request header field.
      const std::string& user_agent,
      // JSON encoded experimental options.
      const std::string& experimental_options,
      // Data reduction proxy key.
      const std::string& data_reduction_proxy_key,
      // Data reduction proxy.
      const std::string& data_reduction_primary_proxy,
      // Fallback data reduction proxy.
      const std::string& data_reduction_fallback_proxy,
      // Data reduction proxy secure proxy check URL.
      const std::string& data_reduction_secure_proxy_check_url,
      // MockCertVerifier to use for testing purposes.
      std::unique_ptr<net::CertVerifier> mock_cert_verifier,
      // Enable network quality estimator.
      bool enable_network_quality_estimator,
      // Enable bypassing of public key pinning for local trust anchors
      bool bypass_public_key_pinning_for_local_trust_anchors,
      // Certificate verifier cache data.
      const std::string& cert_verifier_data);
  ~URLRequestContextConfig();

  // Configure |context_builder| based on |this|.
  void ConfigureURLRequestContextBuilder(
      net::URLRequestContextBuilder* context_builder,
      net::NetLog* net_log,
      const scoped_refptr<base::SequencedTaskRunner>& file_task_runner);

  // Enable QUIC.
  const bool enable_quic;
  // QUIC User Agent ID.
  const std::string quic_user_agent_id;
  // Enable SPDY.
  const bool enable_spdy;
  // Enable SDCH.
  const bool enable_sdch;
  // Type of http cache.
  const HttpCacheType http_cache;
  // Max size of http cache in bytes.
  const int http_cache_max_size;
  // Disable caching for HTTP responses. Other information may be stored in
  // the cache.
  const bool load_disable_cache;
  // Storage path for http cache and cookie storage.
  const std::string storage_path;
  // User-Agent request header field.
  const std::string user_agent;
  // Experimental options encoded as a string in a JSON format containing
  // experiments and their corresponding configuration options. The format
  // is a JSON object with the name of the experiment as the key, and the
  // configuration options as the value. An example:
  //   {"experiment1": {"option1": "option_value1", "option2": "option_value2",
  //    ...}, "experiment2: {"option3", "option_value3", ...}, ...}
  const std::string experimental_options;
  // Enable Data Reduction Proxy with authentication key.
  const std::string data_reduction_proxy_key;
  const std::string data_reduction_primary_proxy;
  const std::string data_reduction_fallback_proxy;
  const std::string data_reduction_secure_proxy_check_url;

  // Certificate verifier for testing.
  std::unique_ptr<net::CertVerifier> mock_cert_verifier;

  // Enable network quality estimator.
  const bool enable_network_quality_estimator;

  // Enable public key pinning bypass for local trust anchors.
  const bool bypass_public_key_pinning_for_local_trust_anchors;

  // Data to populte CertVerifierCache.
  const std::string cert_verifier_data;

  // App-provided list of servers that support QUIC.
  ScopedVector<QuicHint> quic_hints;

  // The list of public key pins.
  ScopedVector<Pkp> pkp_list;

 private:
  DISALLOW_COPY_AND_ASSIGN(URLRequestContextConfig);
};

}  // namespace cronet

#endif  // COMPONENTS_CRONET_URL_REQUEST_CONTEXT_CONFIG_H_
