// Copyright 2015 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef COMPONENTS_CRONET_ANDROID_CRONET_DATA_REDUCTION_PROXY_H_
#define COMPONENTS_CRONET_ANDROID_CRONET_DATA_REDUCTION_PROXY_H_

#include <memory>
#include <string>

#include "base/macros.h"
#include "base/memory/ref_counted.h"

class PrefService;

namespace base {
class SingleThreadTaskRunner;
}

namespace data_reduction_proxy {
class DataReductionProxyIOData;
class DataReductionProxySettings;
}

namespace net {
class NetLog;
class NetworkDelegate;
class ProxyDelegate;
class URLRequestContext;
class URLRequestContextGetter;
class URLRequestInterceptor;
}

namespace cronet {

// Wrapper and configurator of Data Reduction Proxy objects for Cronet. It
// configures the Data Reduction Proxy to run both its UI and IO classes on
// Cronet's network thread.
class CronetDataReductionProxy {
 public:
  // Construct Data Reduction Proxy Settings and IOData objects and set
  // the authentication key. The |task_runner| should be suitable for running
  // tasks on the network thread. The primary proxy, fallback proxy, and secure
  // proxy check url can override defaults. All or none must be specified.
  CronetDataReductionProxy(
      const std::string& key,
      const std::string& primary_proxy,
      const std::string& fallback_proxy,
      const std::string& secure_proxy_check_url,
      const std::string& user_agent,
      scoped_refptr<base::SingleThreadTaskRunner> task_runner,
      net::NetLog* net_log);

  ~CronetDataReductionProxy();

  // Constructs a network delegate suitable for adding Data Reduction Proxy
  // request headers.
  std::unique_ptr<net::NetworkDelegate> CreateNetworkDelegate(
      std::unique_ptr<net::NetworkDelegate> wrapped_network_delegate);

  // Constructs a proxy delegate suitable for adding Data Reduction Proxy
  // proxy resolution.
  std::unique_ptr<net::ProxyDelegate> CreateProxyDelegate();

  // Constructs a URLRequestInterceptor suitable for carrying out the Data
  // Reduction Proxy's bypass protocol.
  std::unique_ptr<net::URLRequestInterceptor> CreateInterceptor();

  // Constructs a bridge between the Settings and IOData objects, sets up a
  // context for secure proxy check requests, and enables the proxy, if
  // |enable| is true.
  void Init(bool enable, net::URLRequestContext* context);

 private:
  scoped_refptr<base::SingleThreadTaskRunner> task_runner_;
  std::unique_ptr<PrefService> prefs_;
  scoped_refptr<net::URLRequestContextGetter> url_request_context_getter_;
  std::unique_ptr<data_reduction_proxy::DataReductionProxySettings> settings_;
  std::unique_ptr<data_reduction_proxy::DataReductionProxyIOData> io_data_;

  DISALLOW_COPY_AND_ASSIGN(CronetDataReductionProxy);
};

}  // namespace cronet

#endif  // COMPONENTS_CRONET_ANDROID_CRONET_DATA_REDUCTION_PROXY_H_
