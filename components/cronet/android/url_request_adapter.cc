// Copyright 2014 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "components/cronet/android/url_request_adapter.h"

#include <stddef.h>
#include <string.h>
#include <utility>

#include "base/bind.h"
#include "base/location.h"
#include "base/logging.h"
#include "base/single_thread_task_runner.h"
#include "base/strings/string_number_conversions.h"
#include "components/cronet/android/url_request_context_adapter.h"
#include "components/cronet/android/wrapped_channel_upload_element_reader.h"
#include "net/base/elements_upload_data_stream.h"
#include "net/base/load_flags.h"
#include "net/base/net_errors.h"
#include "net/base/upload_bytes_element_reader.h"
#include "net/http/http_response_headers.h"
#include "net/http/http_status_code.h"

namespace cronet {

static const size_t kReadBufferSize = 32768;

URLRequestAdapter::URLRequestAdapter(URLRequestContextAdapter* context,
                                     URLRequestAdapterDelegate* delegate,
                                     GURL url,
                                     net::RequestPriority priority)
    : method_("GET"),
      total_bytes_read_(0),
      error_code_(0),
      http_status_code_(0),
      canceled_(false),
      expected_size_(0),
      chunked_upload_(false),
      disable_redirect_(false) {
  context_ = context;
  delegate_ = delegate;
  url_ = url;
  priority_ = priority;
}

URLRequestAdapter::~URLRequestAdapter() {
  DCHECK(OnNetworkThread());
  CHECK(url_request_ == NULL);
}

void URLRequestAdapter::SetMethod(const std::string& method) {
  method_ = method;
}

void URLRequestAdapter::AddHeader(const std::string& name,
                                  const std::string& value) {
  headers_.SetHeader(name, value);
}

void URLRequestAdapter::SetUploadContent(const char* bytes, int bytes_len) {
  std::vector<char> data(bytes, bytes + bytes_len);
  std::unique_ptr<net::UploadElementReader> reader(
      new net::UploadOwnedBytesElementReader(&data));
  upload_data_stream_ =
      net::ElementsUploadDataStream::CreateWithReader(std::move(reader), 0);
}

void URLRequestAdapter::SetUploadChannel(JNIEnv* env, int64_t content_length) {
  std::unique_ptr<net::UploadElementReader> reader(
      new WrappedChannelElementReader(delegate_, content_length));
  upload_data_stream_ =
      net::ElementsUploadDataStream::CreateWithReader(std::move(reader), 0);
}

void URLRequestAdapter::DisableRedirects() {
  disable_redirect_ = true;
}

void URLRequestAdapter::EnableChunkedUpload() {
  chunked_upload_ = true;
}

void URLRequestAdapter::AppendChunk(const char* bytes, int bytes_len,
                                    bool is_last_chunk) {
  VLOG(1) << "AppendChunk, len: " << bytes_len << ", last: " << is_last_chunk;
  std::unique_ptr<char[]> buf(new char[bytes_len]);
  memcpy(buf.get(), bytes, bytes_len);
  context_->PostTaskToNetworkThread(
      FROM_HERE,
      base::Bind(&URLRequestAdapter::OnAppendChunk, base::Unretained(this),
                 base::Passed(&buf), bytes_len, is_last_chunk));
}

std::string URLRequestAdapter::GetHeader(const std::string& name) const {
  std::string value;
  if (url_request_ != NULL) {
    url_request_->GetResponseHeaderByName(name, &value);
  }
  return value;
}

net::HttpResponseHeaders* URLRequestAdapter::GetResponseHeaders() const {
  if (url_request_ == NULL) {
    return NULL;
  }
  return url_request_->response_headers();
}

std::string URLRequestAdapter::GetNegotiatedProtocol() const {
  if (url_request_ == NULL)
    return std::string();
  return url_request_->response_info().npn_negotiated_protocol;
}

bool URLRequestAdapter::GetWasCached() const {
  if (url_request_ == NULL)
    return false;
  return url_request_->response_info().was_cached;
}

void URLRequestAdapter::Start() {
  context_->PostTaskToNetworkThread(
      FROM_HERE,
      base::Bind(&URLRequestAdapter::OnInitiateConnection,
                 base::Unretained(this)));
}

void URLRequestAdapter::OnAppendChunk(const std::unique_ptr<char[]> bytes,
                                      int bytes_len,
                                      bool is_last_chunk) {
  DCHECK(OnNetworkThread());
  // If AppendData returns false, the request has been cancelled or completed
  // without uploading the entire request body.  Either way, that result will
  // have been sent to the embedder, so there's nothing else to do here.
  chunked_upload_writer_->AppendData(bytes.get(), bytes_len, is_last_chunk);
}

void URLRequestAdapter::OnInitiateConnection() {
  DCHECK(OnNetworkThread());
  if (canceled_) {
    return;
  }

  VLOG(1) << "Starting chromium request: "
          << url_.possibly_invalid_spec().c_str()
          << " priority: " << RequestPriorityToString(priority_);
  url_request_ = context_->GetURLRequestContext()->CreateRequest(
      url_, net::DEFAULT_PRIORITY, this);
  int flags = net::LOAD_DO_NOT_SAVE_COOKIES | net::LOAD_DO_NOT_SEND_COOKIES;
  if (context_->load_disable_cache())
    flags |= net::LOAD_DISABLE_CACHE;
  url_request_->SetLoadFlags(flags);
  url_request_->set_method(method_);
  url_request_->SetExtraRequestHeaders(headers_);
  if (!headers_.HasHeader(net::HttpRequestHeaders::kUserAgent)) {
    std::string user_agent;
    user_agent = context_->GetUserAgent(url_);
    url_request_->SetExtraRequestHeaderByName(
        net::HttpRequestHeaders::kUserAgent, user_agent, true /* override */);
  }

  if (upload_data_stream_) {
    url_request_->set_upload(std::move(upload_data_stream_));
  } else if (chunked_upload_) {
    std::unique_ptr<net::ChunkedUploadDataStream> chunked_upload_data_stream(
        new net::ChunkedUploadDataStream(0));
    // Create a ChunkedUploadDataStream::Writer, which keeps a weak reference to
    // the UploadDataStream, before passing ownership of the stream to the
    // URLRequest.
    chunked_upload_writer_ = chunked_upload_data_stream->CreateWriter();
    url_request_->set_upload(std::move(chunked_upload_data_stream));
  }

  url_request_->SetPriority(priority_);

  url_request_->Start();
}

void URLRequestAdapter::Cancel() {
  context_->PostTaskToNetworkThread(
      FROM_HERE,
      base::Bind(&URLRequestAdapter::OnCancelRequest, base::Unretained(this)));
}

void URLRequestAdapter::OnCancelRequest() {
  DCHECK(OnNetworkThread());
  DCHECK(!canceled_);
  VLOG(1) << "Canceling chromium request: " << url_.possibly_invalid_spec();
  canceled_ = true;
  // Check whether request has already completed.
  if (url_request_ == nullptr)
    return;

  url_request_->Cancel();
  OnRequestCompleted();
}

void URLRequestAdapter::Destroy() {
  context_->PostTaskToNetworkThread(
      FROM_HERE, base::Bind(&URLRequestAdapter::OnDestroyRequest, this));
}

// static
void URLRequestAdapter::OnDestroyRequest(URLRequestAdapter* self) {
  DCHECK(self->OnNetworkThread());
  VLOG(1) << "Destroying chromium request: "
          << self->url_.possibly_invalid_spec();
  delete self;
}

// static
void URLRequestAdapter::OnResponseStarted(net::URLRequest* request) {
  DCHECK(OnNetworkThread());
  if (request->status().status() != net::URLRequestStatus::SUCCESS) {
    OnRequestFailed();
    return;
  }

  http_status_code_ = request->GetResponseCode();
  VLOG(1) << "Response started with status: " << http_status_code_;

  net::HttpResponseHeaders* headers = request->response_headers();
  if (headers)
    http_status_text_ = headers->GetStatusText();

  request->GetResponseHeaderByName("Content-Type", &content_type_);
  expected_size_ = request->GetExpectedContentSize();
  delegate_->OnResponseStarted(this);

  Read();
}

// Reads all available data or starts an asynchronous read.
void URLRequestAdapter::Read() {
  DCHECK(OnNetworkThread());
  if (!read_buffer_.get())
    read_buffer_ = new net::IOBufferWithSize(kReadBufferSize);

  while(true) {
    int bytes_read = 0;
    url_request_->Read(read_buffer_.get(), kReadBufferSize, &bytes_read);
    // If IO is pending, wait for the URLRequest to call OnReadCompleted.
    if (url_request_->status().is_io_pending())
      return;
    // Stop when request has failed or succeeded.
    if (!HandleReadResult(bytes_read))
      return;
  }
}

bool URLRequestAdapter::HandleReadResult(int bytes_read) {
  DCHECK(OnNetworkThread());
  if (!url_request_->status().is_success()) {
    OnRequestFailed();
    return false;
  } else if (bytes_read == 0) {
    OnRequestSucceeded();
    return false;
  }

  total_bytes_read_ += bytes_read;
  delegate_->OnBytesRead(this, bytes_read);

  return true;
}

void URLRequestAdapter::OnReadCompleted(net::URLRequest* request,
                                        int bytes_read) {
  if (!HandleReadResult(bytes_read))
    return;

  Read();
}

void URLRequestAdapter::OnReceivedRedirect(net::URLRequest* request,
                                           const net::RedirectInfo& info,
                                           bool* defer_redirect) {
  DCHECK(OnNetworkThread());
  if (disable_redirect_) {
    http_status_code_ = request->GetResponseCode();
    request->CancelWithError(net::ERR_TOO_MANY_REDIRECTS);
    error_code_ = net::ERR_TOO_MANY_REDIRECTS;
    canceled_ = true;
    *defer_redirect = false;
    OnRequestCompleted();
  }
}

void URLRequestAdapter::OnRequestSucceeded() {
  DCHECK(OnNetworkThread());
  if (canceled_) {
    return;
  }

  VLOG(1) << "Request completed with HTTP status: " << http_status_code_
          << ". Total bytes read: " << total_bytes_read_;

  OnRequestCompleted();
}

void URLRequestAdapter::OnRequestFailed() {
  DCHECK(OnNetworkThread());
  if (canceled_) {
    return;
  }

  error_code_ = url_request_->status().error();
  VLOG(1) << "Request failed with status: " << url_request_->status().status()
          << " and error: " << net::ErrorToString(error_code_);
  OnRequestCompleted();
}

void URLRequestAdapter::OnRequestCompleted() {
  DCHECK(OnNetworkThread());
  VLOG(1) << "Completed: " << url_.possibly_invalid_spec();

  DCHECK(url_request_ != nullptr);

  delegate_->OnRequestFinished(this);
  url_request_.reset();
}

unsigned char* URLRequestAdapter::Data() const {
  DCHECK(OnNetworkThread());
  return reinterpret_cast<unsigned char*>(read_buffer_->data());
}

bool URLRequestAdapter::OnNetworkThread() const {
  return context_->GetNetworkTaskRunner()->BelongsToCurrentThread();
}

}  // namespace cronet
