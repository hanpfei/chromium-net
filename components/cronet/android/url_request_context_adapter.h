// Copyright 2014 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef COMPONENTS_CRONET_ANDROID_URL_REQUEST_CONTEXT_ADAPTER_H_
#define COMPONENTS_CRONET_ANDROID_URL_REQUEST_CONTEXT_ADAPTER_H_

#include <memory>
#include <queue>
#include <string>

#include "base/callback.h"
#include "base/compiler_specific.h"
#include "base/location.h"
#include "base/macros.h"
#include "base/memory/ref_counted.h"
#include "base/threading/thread.h"
#include "net/log/net_log.h"
#include "net/url_request/url_request_context.h"
#include "net/url_request/url_request_context_getter.h"

namespace net {
class WriteToFileNetLogObserver;
class ProxyConfigService;
class SdchOwner;
}  // namespace net

namespace cronet {

struct URLRequestContextConfig;
typedef base::Callback<void(void)> RunAfterContextInitTask;

// Implementation of the Chromium NetLog observer interface.
class NetLogObserver : public net::NetLog::ThreadSafeObserver {
 public:
  NetLogObserver() {}

  ~NetLogObserver() override {}

  void OnAddEntry(const net::NetLog::Entry& entry) override;

 private:
  DISALLOW_COPY_AND_ASSIGN(NetLogObserver);
};

// Fully configured |URLRequestContext|.
class URLRequestContextAdapter : public net::URLRequestContextGetter {
 public:
  class URLRequestContextAdapterDelegate
      : public base::RefCountedThreadSafe<URLRequestContextAdapterDelegate> {
   public:
    virtual void OnContextInitialized(URLRequestContextAdapter* context) = 0;

   protected:
    friend class base::RefCountedThreadSafe<URLRequestContextAdapterDelegate>;

    virtual ~URLRequestContextAdapterDelegate() {}
  };

  URLRequestContextAdapter(URLRequestContextAdapterDelegate* delegate,
                           std::string user_agent);
  void Initialize(std::unique_ptr<URLRequestContextConfig> config);

  // Posts a task that might depend on the context being initialized
  // to the network thread.
  void PostTaskToNetworkThread(const tracked_objects::Location& posted_from,
                               const RunAfterContextInitTask& callback);

  // Runs a task that might depend on the context being initialized.
  // This method should only be run on the network thread.
  void RunTaskAfterContextInitOnNetworkThread(
      const RunAfterContextInitTask& callback);

  const std::string& GetUserAgent(const GURL& url) const;

  bool load_disable_cache() const { return load_disable_cache_; }

  // net::URLRequestContextGetter implementation:
  net::URLRequestContext* GetURLRequestContext() override;
  scoped_refptr<base::SingleThreadTaskRunner> GetNetworkTaskRunner()
      const override;

  void StartNetLogToFile(const std::string& file_name, bool log_all);
  void StopNetLog();

  // Called on main Java thread to initialize URLRequestContext.
  void InitRequestContextOnMainThread();

 private:
  ~URLRequestContextAdapter() override;

  // Initializes |context_| on the Network thread.
  void InitRequestContextOnNetworkThread();

  // Helper function to start writing NetLog data to file. This should only be
  // run after context is initialized.
  void StartNetLogToFileHelper(const std::string& file_name, bool log_all);
  // Helper function to stop writing NetLog data to file. This should only be
  // run after context is initialized.
  void StopNetLogHelper();

  scoped_refptr<URLRequestContextAdapterDelegate> delegate_;
  std::unique_ptr<net::URLRequestContext> context_;
  std::string user_agent_;
  bool load_disable_cache_;
  base::Thread* network_thread_;
  std::unique_ptr<NetLogObserver> net_log_observer_;
  std::unique_ptr<net::WriteToFileNetLogObserver> write_to_file_observer_;
  std::unique_ptr<net::ProxyConfigService> proxy_config_service_;
  std::unique_ptr<net::SdchOwner> sdch_owner_;
  std::unique_ptr<URLRequestContextConfig> config_;

  // A queue of tasks that need to be run after context has been initialized.
  std::queue<RunAfterContextInitTask> tasks_waiting_for_context_;
  bool is_context_initialized_;

  DISALLOW_COPY_AND_ASSIGN(URLRequestContextAdapter);
};

}  // namespace cronet

#endif  // COMPONENTS_CRONET_ANDROID_URL_REQUEST_CONTEXT_ADAPTER_H_
