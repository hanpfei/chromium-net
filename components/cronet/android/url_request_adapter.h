// Copyright 2014 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef COMPONENTS_CRONET_ANDROID_URL_REQUEST_ADAPTER_H_
#define COMPONENTS_CRONET_ANDROID_URL_REQUEST_ADAPTER_H_

#include <jni.h>
#include <stdint.h>

#include <memory>
#include <string>

#include "base/macros.h"
#include "base/memory/ref_counted.h"
#include "net/base/chunked_upload_data_stream.h"
#include "net/base/request_priority.h"
#include "net/http/http_request_headers.h"
#include "net/url_request/url_request.h"

namespace net {
class HttpResponseHeaders;
class IOBufferWithSize;
class UploadDataStream;
struct RedirectInfo;
}  // namespace net

namespace cronet {

class URLRequestContextAdapter;

// An adapter from the JNI |UrlRequest| object and the Chromium |URLRequest|
// object.
class URLRequestAdapter : public net::URLRequest::Delegate {
 public:
  // The delegate which is called when the request finishes.
  class URLRequestAdapterDelegate
      : public base::RefCountedThreadSafe<URLRequestAdapterDelegate> {
   public:
    virtual void OnResponseStarted(URLRequestAdapter* request) = 0;
    virtual void OnBytesRead(URLRequestAdapter* request, int bytes_read) = 0;
    virtual void OnRequestFinished(URLRequestAdapter* request) = 0;
    virtual int ReadFromUploadChannel(net::IOBuffer* buf, int buf_length) = 0;

   protected:
    friend class base::RefCountedThreadSafe<URLRequestAdapterDelegate>;
    virtual ~URLRequestAdapterDelegate() {}
  };

  URLRequestAdapter(URLRequestContextAdapter* context,
                    URLRequestAdapterDelegate* delegate,
                    GURL url,
                    net::RequestPriority priority);
  ~URLRequestAdapter() override;

  // Sets the request method GET, POST etc
  void SetMethod(const std::string& method);

  // Adds a header to the request
  void AddHeader(const std::string& name, const std::string& value);

  // Sets the contents of the POST or PUT request
  void SetUploadContent(const char* bytes, int bytes_len);

  // Sets the request to streaming upload.
  void SetUploadChannel(JNIEnv* env, int64_t content_length);

  // Disables redirect. Note that redirect is enabled by default.
  void DisableRedirects();

  // Indicates that the request body will be streamed by calling AppendChunk()
  // repeatedly. This must be called before Start().
  void EnableChunkedUpload();

  // Appends a chunk to the POST body.
  // This must be called after EnableChunkedUpload() and Start().
  void AppendChunk(const char* bytes, int bytes_len, bool is_last_chunk);

  // Starts the request.
  void Start();

  // Cancels the request.
  void Cancel();

  // Releases all resources for the request and deletes the object itself.
  void Destroy();

  // Returns the URL of the request.
  GURL url() const { return url_; }

  // Returns the error code after the request is complete.
  // Negative codes indicate system errors.
  int error_code() const { return error_code_; }

  // Returns the HTTP status code.
  int http_status_code() const {
    return http_status_code_;
  };

  // Returns the HTTP status text of the normalized status line.
  const std::string& http_status_text() const {
    return http_status_text_;
  }

  // Returns the value of the content-length response header.
  int64_t content_length() const { return expected_size_; }

  // Returns the value of the content-type response header.
  std::string content_type() const { return content_type_; }

  // Returns the value of the specified response header.
  std::string GetHeader(const std::string& name) const;

  // Get all response headers, as a HttpResponseHeaders object.
  net::HttpResponseHeaders* GetResponseHeaders() const;

  // Returns a pointer to the downloaded data.
  unsigned char* Data() const;

  // Get NPN or ALPN Negotiated Protocol (if any) from HttpResponseInfo.
  std::string GetNegotiatedProtocol() const;

  // Returns whether the response is serviced from cache.
  bool GetWasCached() const;

  // net::URLRequest::Delegate implementation:
  void OnResponseStarted(net::URLRequest* request) override;
  void OnReadCompleted(net::URLRequest* request, int bytes_read) override;
  void OnReceivedRedirect(net::URLRequest* request,
                          const net::RedirectInfo& redirect_info,
                          bool* defer_redirect) override;

  bool OnNetworkThread() const;

 private:
  static void OnDestroyRequest(URLRequestAdapter* self);

  void OnInitiateConnection();
  void OnCancelRequest();
  void OnRequestSucceeded();
  void OnRequestFailed();
  void OnRequestCompleted();
  void OnAppendChunk(const std::unique_ptr<char[]> bytes,
                     int bytes_len,
                     bool is_last_chunk);

  void Read();

  // Handles synchronous or asynchronous read result, calls |delegate_| with
  // bytes read and returns true unless request has succeeded or failed.
  bool HandleReadResult(int bytes_read);

  URLRequestContextAdapter* context_;
  scoped_refptr<URLRequestAdapterDelegate> delegate_;
  GURL url_;
  net::RequestPriority priority_;
  std::string method_;
  net::HttpRequestHeaders headers_;
  std::unique_ptr<net::URLRequest> url_request_;
  std::unique_ptr<net::UploadDataStream> upload_data_stream_;
  std::unique_ptr<net::ChunkedUploadDataStream::Writer> chunked_upload_writer_;
  scoped_refptr<net::IOBufferWithSize> read_buffer_;
  int total_bytes_read_;
  int error_code_;
  int http_status_code_;
  std::string http_status_text_;
  std::string content_type_;
  bool canceled_;
  int64_t expected_size_;
  bool chunked_upload_;
  // Indicates whether redirect has been disabled.
  bool disable_redirect_;

  DISALLOW_COPY_AND_ASSIGN(URLRequestAdapter);
};

}  // namespace cronet

#endif  // COMPONENTS_CRONET_ANDROID_URL_REQUEST_ADAPTER_H_
