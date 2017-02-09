// Copyright 2014 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef COMPONENTS_CRONET_ANDROID_CRONET_URL_REQUEST_ADAPTER_H_
#define COMPONENTS_CRONET_ANDROID_CRONET_URL_REQUEST_ADAPTER_H_

#include <jni.h>

#include <memory>
#include <string>

#include "base/android/jni_android.h"
#include "base/android/jni_array.h"
#include "base/android/jni_string.h"
#include "base/android/scoped_java_ref.h"
#include "base/callback.h"
#include "base/location.h"
#include "base/macros.h"
#include "base/memory/ref_counted.h"
#include "net/base/request_priority.h"
#include "net/url_request/url_request.h"
#include "url/gurl.h"

namespace base {
class SingleThreadTaskRunner;
}  // namespace base

namespace net {
class HttpRequestHeaders;
class HttpResponseHeaders;
class SSLCertRequestInfo;
class SSLInfo;
class UploadDataStream;
}  // namespace net

namespace cronet {

class CronetURLRequestContextAdapter;
class IOBufferWithByteBuffer;

bool CronetUrlRequestAdapterRegisterJni(JNIEnv* env);

// An adapter from Java CronetUrlRequest object to net::URLRequest.
// Created and configured from a Java thread. Start, ReadData, and Destroy are
// posted to network thread and all callbacks into the Java CronetUrlRequest are
// done on the network thread. Java CronetUrlRequest is expected to initiate the
// next step like FollowDeferredRedirect, ReadData or Destroy. Public methods
// can be called on any thread.
class CronetURLRequestAdapter : public net::URLRequest::Delegate {
 public:
  // Bypasses cache if |jdisable_cache| is true. If context is not set up to
  // use cache, |jdisable_cache| has no effect. |jdisable_connection_migration|
  // causes connection migration to be disabled for this request if true. If
  // global connection migration flag is not enabled,
  // |jdisable_connection_migration| has no effect.
  CronetURLRequestAdapter(CronetURLRequestContextAdapter* context,
                          JNIEnv* env,
                          jobject jurl_request,
                          const GURL& url,
                          net::RequestPriority priority,
                          jboolean jdisable_cache,
                          jboolean jdisable_connection_migration);
  ~CronetURLRequestAdapter() override;

  // Methods called prior to Start are never called on network thread.

  // Sets the request method GET, POST etc.
  jboolean SetHttpMethod(JNIEnv* env,
                         const base::android::JavaParamRef<jobject>& jcaller,
                         const base::android::JavaParamRef<jstring>& jmethod);

  // Adds a header to the request before it starts.
  jboolean AddRequestHeader(JNIEnv* env,
                            const base::android::JavaParamRef<jobject>& jcaller,
                            const base::android::JavaParamRef<jstring>& jname,
                            const base::android::JavaParamRef<jstring>& jvalue);

  // Adds a request body to the request before it starts.
  void SetUpload(std::unique_ptr<net::UploadDataStream> upload);

  // Starts the request.
  void Start(JNIEnv* env, const base::android::JavaParamRef<jobject>& jcaller);

  void GetStatus(
      JNIEnv* env,
      const base::android::JavaParamRef<jobject>& jcaller,
      const base::android::JavaParamRef<jobject>& jstatus_listener) const;

  // Follows redirect.
  void FollowDeferredRedirect(
      JNIEnv* env,
      const base::android::JavaParamRef<jobject>& jcaller);

  // Reads more data.
  jboolean ReadData(JNIEnv* env,
                    const base::android::JavaParamRef<jobject>& jcaller,
                    const base::android::JavaParamRef<jobject>& jbyte_buffer,
                    jint jposition,
                    jint jcapacity);

  // Releases all resources for the request and deletes the object itself.
  // |jsend_on_canceled| indicates if Java onCanceled callback should be
  // issued to indicate when no more callbacks will be issued.
  void Destroy(JNIEnv* env,
               const base::android::JavaParamRef<jobject>& jcaller,
               jboolean jsend_on_canceled);

  // net::URLRequest::Delegate implementations:

  void OnReceivedRedirect(net::URLRequest* request,
                          const net::RedirectInfo& redirect_info,
                          bool* defer_redirect) override;
  void OnCertificateRequested(
      net::URLRequest* request,
      net::SSLCertRequestInfo* cert_request_info) override;
  void OnSSLCertificateError(net::URLRequest* request,
                             const net::SSLInfo& ssl_info,
                             bool fatal) override;
  void OnResponseStarted(net::URLRequest* request) override;
  void OnReadCompleted(net::URLRequest* request, int bytes_read) override;

  net::URLRequest* GetURLRequestForTesting();

 private:
  void StartOnNetworkThread();
  void GetStatusOnNetworkThread(
      const base::android::ScopedJavaGlobalRef<jobject>& jstatus_listener_ref)
      const;
  // Gets response headers on network thread.
  base::android::ScopedJavaLocalRef<jobjectArray> GetResponseHeaders(
      JNIEnv* env);
  void FollowDeferredRedirectOnNetworkThread();
  void ReadDataOnNetworkThread(
      scoped_refptr<IOBufferWithByteBuffer> read_buffer,
      int buffer_size);
  void DestroyOnNetworkThread(bool send_on_canceled);

  // Checks status of the request_adapter, return false if |is_success()| is
  // true, otherwise report error and cancel request_adapter.
  bool MaybeReportError(net::URLRequest* request) const;

  CronetURLRequestContextAdapter* context_;

  // Java object that owns this CronetURLRequestContextAdapter.
  base::android::ScopedJavaGlobalRef<jobject> owner_;

  const GURL initial_url_;
  const net::RequestPriority initial_priority_;
  std::string initial_method_;
  int load_flags_;
  net::HttpRequestHeaders initial_request_headers_;
  std::unique_ptr<net::UploadDataStream> upload_;

  scoped_refptr<IOBufferWithByteBuffer> read_buffer_;
  std::unique_ptr<net::URLRequest> url_request_;

  DISALLOW_COPY_AND_ASSIGN(CronetURLRequestAdapter);
};

}  // namespace cronet

#endif  // COMPONENTS_CRONET_ANDROID_CRONET_URL_REQUEST_ADAPTER_H_
