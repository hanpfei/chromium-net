// Copyright 2015 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "cronet_bidirectional_stream_adapter.h"

#include <string>
#include <vector>

#include "base/bind.h"
#include "base/location.h"
#include "base/logging.h"
#include "base/strings/string_number_conversions.h"
#include "components/cronet/android/cronet_url_request_context_adapter.h"
#include "components/cronet/android/io_buffer_with_byte_buffer.h"
#include "components/cronet/android/url_request_error.h"
#include "jni/CronetBidirectionalStream_jni.h"
#include "net/base/net_errors.h"
#include "net/base/request_priority.h"
#include "net/http/bidirectional_stream_request_info.h"
#include "net/http/http_network_session.h"
#include "net/http/http_response_headers.h"
#include "net/http/http_status_code.h"
#include "net/http/http_transaction_factory.h"
#include "net/http/http_util.h"
#include "net/quic/core/quic_protocol.h"
#include "net/spdy/spdy_header_block.h"
#include "net/ssl/ssl_info.h"
#include "net/url_request/http_user_agent_settings.h"
#include "net/url_request/url_request_context.h"
#include "url/gurl.h"

using base::android::ConvertUTF8ToJavaString;
using base::android::ConvertJavaStringToUTF8;
using base::android::ScopedJavaLocalRef;

namespace cronet {

namespace {

// As |GetArrayLength| makes no guarantees about the returned value (e.g., it
// may be -1 if |array| is not a valid Java array), provide a safe wrapper
// that always returns a valid, non-negative size.
template <typename JavaArrayType>
size_t SafeGetArrayLength(JNIEnv* env, JavaArrayType jarray) {
  DCHECK(jarray);
  jsize length = env->GetArrayLength(jarray);
  DCHECK_GE(length, 0) << "Invalid array length: " << length;
  return static_cast<size_t>(std::max(0, length));
}

}  // namespace

PendingWriteData::PendingWriteData(JNIEnv* env,
                                   jobjectArray jwrite_buffer_list,
                                   jintArray jwrite_buffer_pos_list,
                                   jintArray jwrite_buffer_limit_list,
                                   jboolean jwrite_end_of_stream) {
  this->jwrite_buffer_list.Reset(env, jwrite_buffer_list);
  this->jwrite_buffer_pos_list.Reset(env, jwrite_buffer_pos_list);
  this->jwrite_buffer_limit_list.Reset(env, jwrite_buffer_limit_list);
  this->jwrite_end_of_stream = jwrite_end_of_stream;
}

PendingWriteData::~PendingWriteData() {
  // Reset global references.
  jwrite_buffer_list.Reset();
  jwrite_buffer_pos_list.Reset();
  jwrite_buffer_limit_list.Reset();
}

static jlong CreateBidirectionalStream(
    JNIEnv* env,
    const base::android::JavaParamRef<jobject>& jbidi_stream,
    jlong jurl_request_context_adapter,
    jboolean jsend_request_headers_automatically) {
  CronetURLRequestContextAdapter* context_adapter =
      reinterpret_cast<CronetURLRequestContextAdapter*>(
          jurl_request_context_adapter);
  DCHECK(context_adapter);

  CronetBidirectionalStreamAdapter* adapter =
      new CronetBidirectionalStreamAdapter(context_adapter, env, jbidi_stream,
                                           jsend_request_headers_automatically);

  return reinterpret_cast<jlong>(adapter);
}

// static
bool CronetBidirectionalStreamAdapter::RegisterJni(JNIEnv* env) {
  return RegisterNativesImpl(env);
}

CronetBidirectionalStreamAdapter::CronetBidirectionalStreamAdapter(
    CronetURLRequestContextAdapter* context,
    JNIEnv* env,
    const base::android::JavaParamRef<jobject>& jbidi_stream,
    bool send_request_headers_automatically)
    : context_(context),
      owner_(env, jbidi_stream),
      send_request_headers_automatically_(send_request_headers_automatically),
      stream_failed_(false) {}

CronetBidirectionalStreamAdapter::~CronetBidirectionalStreamAdapter() {
  DCHECK(context_->IsOnNetworkThread());
}

void CronetBidirectionalStreamAdapter::SendRequestHeaders(
    JNIEnv* env,
    const base::android::JavaParamRef<jobject>& jcaller) {
  context_->PostTaskToNetworkThread(
      FROM_HERE,
      base::Bind(
          &CronetBidirectionalStreamAdapter::SendRequestHeadersOnNetworkThread,
          base::Unretained(this)));
}

jint CronetBidirectionalStreamAdapter::Start(
    JNIEnv* env,
    const base::android::JavaParamRef<jobject>& jcaller,
    const base::android::JavaParamRef<jstring>& jurl,
    jint jpriority,
    const base::android::JavaParamRef<jstring>& jmethod,
    const base::android::JavaParamRef<jobjectArray>& jheaders,
    jboolean jend_of_stream) {
  // Prepare request info here to be able to return the error.
  std::unique_ptr<net::BidirectionalStreamRequestInfo> request_info(
      new net::BidirectionalStreamRequestInfo());
  request_info->url = GURL(ConvertJavaStringToUTF8(env, jurl));
  request_info->priority = static_cast<net::RequestPriority>(jpriority);
  // Http method is a token, just as header name.
  request_info->method = ConvertJavaStringToUTF8(env, jmethod);
  if (!net::HttpUtil::IsValidHeaderName(request_info->method))
    return -1;

  std::vector<std::string> headers;
  base::android::AppendJavaStringArrayToStringVector(env, jheaders, &headers);
  for (size_t i = 0; i < headers.size(); i += 2) {
    std::string name(headers[i]);
    std::string value(headers[i + 1]);
    if (!net::HttpUtil::IsValidHeaderName(name) ||
        !net::HttpUtil::IsValidHeaderValue(value)) {
      return i + 1;
    }
    request_info->extra_headers.SetHeader(name, value);
  }
  request_info->end_stream_on_headers = jend_of_stream;

  context_->PostTaskToNetworkThread(
      FROM_HERE,
      base::Bind(&CronetBidirectionalStreamAdapter::StartOnNetworkThread,
                 base::Unretained(this), base::Passed(&request_info)));
  return 0;
}

jboolean CronetBidirectionalStreamAdapter::ReadData(
    JNIEnv* env,
    const base::android::JavaParamRef<jobject>& jcaller,
    const base::android::JavaParamRef<jobject>& jbyte_buffer,
    jint jposition,
    jint jlimit) {
  DCHECK_LT(jposition, jlimit);

  void* data = env->GetDirectBufferAddress(jbyte_buffer);
  if (!data)
    return JNI_FALSE;

  scoped_refptr<IOBufferWithByteBuffer> read_buffer(
      new IOBufferWithByteBuffer(env, jbyte_buffer, data, jposition, jlimit));

  int remaining_capacity = jlimit - jposition;

  context_->PostTaskToNetworkThread(
      FROM_HERE,
      base::Bind(&CronetBidirectionalStreamAdapter::ReadDataOnNetworkThread,
                 base::Unretained(this), read_buffer, remaining_capacity));
  return JNI_TRUE;
}

jboolean CronetBidirectionalStreamAdapter::WritevData(
    JNIEnv* env,
    const base::android::JavaParamRef<jobject>& jcaller,
    const base::android::JavaParamRef<jobjectArray>& jbyte_buffers,
    const base::android::JavaParamRef<jintArray>& jbyte_buffers_pos,
    const base::android::JavaParamRef<jintArray>& jbyte_buffers_limit,
    jboolean jend_of_stream) {
  size_t buffers_array_size = SafeGetArrayLength(env, jbyte_buffers.obj());
  size_t pos_array_size = SafeGetArrayLength(env, jbyte_buffers.obj());
  size_t limit_array_size = SafeGetArrayLength(env, jbyte_buffers.obj());
  if (buffers_array_size != pos_array_size ||
      buffers_array_size != limit_array_size) {
    DLOG(ERROR) << "Illegal arguments.";
    return JNI_FALSE;
  }

  std::unique_ptr<PendingWriteData> pending_write_data;
  pending_write_data.reset(
      new PendingWriteData(env, jbyte_buffers.obj(), jbyte_buffers_pos.obj(),
                           jbyte_buffers_limit.obj(), jend_of_stream));
  for (size_t i = 0; i < buffers_array_size; ++i) {
    ScopedJavaLocalRef<jobject> jbuffer(
        env, env->GetObjectArrayElement(
                 pending_write_data->jwrite_buffer_list.obj(), i));
    void* data = env->GetDirectBufferAddress(jbuffer.obj());
    if (!data)
      return JNI_FALSE;
    jint pos;
    env->GetIntArrayRegion(pending_write_data->jwrite_buffer_pos_list.obj(), i,
                           1, &pos);
    jint limit;
    env->GetIntArrayRegion(pending_write_data->jwrite_buffer_limit_list.obj(),
                           i, 1, &limit);
    DCHECK_LE(pos, limit);
    scoped_refptr<net::WrappedIOBuffer> write_buffer(
        new net::WrappedIOBuffer(static_cast<char*>(data) + pos));
    pending_write_data->write_buffer_list.push_back(write_buffer);
    pending_write_data->write_buffer_len_list.push_back(limit - pos);
  }

  context_->PostTaskToNetworkThread(
      FROM_HERE,
      base::Bind(&CronetBidirectionalStreamAdapter::WritevDataOnNetworkThread,
                 base::Unretained(this),
                 base::Passed(std::move(pending_write_data))));
  return JNI_TRUE;
}

void CronetBidirectionalStreamAdapter::Destroy(
    JNIEnv* env,
    const base::android::JavaParamRef<jobject>& jcaller,
    jboolean jsend_on_canceled) {
  // Destroy could be called from any thread, including network thread (if
  // posting task to executor throws an exception), but is posted, so |this|
  // is valid until calling task is complete. Destroy() is always called from
  // within a synchronized java block that guarantees no future posts to the
  // network thread with the adapter pointer.
  context_->PostTaskToNetworkThread(
      FROM_HERE,
      base::Bind(&CronetBidirectionalStreamAdapter::DestroyOnNetworkThread,
                 base::Unretained(this), jsend_on_canceled));
}

void CronetBidirectionalStreamAdapter::OnStreamReady(
    bool request_headers_sent) {
  DCHECK(context_->IsOnNetworkThread());
  JNIEnv* env = base::android::AttachCurrentThread();
  cronet::Java_CronetBidirectionalStream_onStreamReady(
      env, owner_.obj(), request_headers_sent ? JNI_TRUE : JNI_FALSE);
}

void CronetBidirectionalStreamAdapter::OnHeadersReceived(
    const net::SpdyHeaderBlock& response_headers) {
  DCHECK(context_->IsOnNetworkThread());
  JNIEnv* env = base::android::AttachCurrentThread();
  // Get http status code from response headers.
  jint http_status_code = 0;
  const auto http_status_header = response_headers.find(":status");
  if (http_status_header != response_headers.end())
    base::StringToInt(http_status_header->second, &http_status_code);

  std::string protocol;
  switch (bidi_stream_->GetProtocol()) {
    case net::kProtoHTTP2:
      protocol = "h2";
      break;
    case net::kProtoQUIC1SPDY3:
      protocol = "quic/1+spdy/3";
      break;
    default:
      break;
  }

  cronet::Java_CronetBidirectionalStream_onResponseHeadersReceived(
      env, owner_.obj(), http_status_code,
      ConvertUTF8ToJavaString(env, protocol).obj(),
      GetHeadersArray(env, response_headers).obj(),
      bidi_stream_->GetTotalReceivedBytes());
}

void CronetBidirectionalStreamAdapter::OnDataRead(int bytes_read) {
  DCHECK(context_->IsOnNetworkThread());
  JNIEnv* env = base::android::AttachCurrentThread();
  cronet::Java_CronetBidirectionalStream_onReadCompleted(
      env, owner_.obj(), read_buffer_->byte_buffer(), bytes_read,
      read_buffer_->initial_position(), read_buffer_->initial_limit(),
      bidi_stream_->GetTotalReceivedBytes());
  // Free the read buffer. This lets the Java ByteBuffer be freed, if the
  // embedder releases it, too.
  read_buffer_ = nullptr;
}

void CronetBidirectionalStreamAdapter::OnDataSent() {
  DCHECK(context_->IsOnNetworkThread());
  DCHECK(pending_write_data_);

  JNIEnv* env = base::android::AttachCurrentThread();
  // Call into Java.
  cronet::Java_CronetBidirectionalStream_onWritevCompleted(
      env, owner_.obj(), pending_write_data_->jwrite_buffer_list.obj(),
      pending_write_data_->jwrite_buffer_pos_list.obj(),
      pending_write_data_->jwrite_buffer_limit_list.obj(),
      pending_write_data_->jwrite_end_of_stream);
  // Free the java objects. This lets the Java ByteBuffers be freed, if the
  // embedder releases it, too.
  pending_write_data_.reset();
}

void CronetBidirectionalStreamAdapter::OnTrailersReceived(
    const net::SpdyHeaderBlock& response_trailers) {
  DCHECK(context_->IsOnNetworkThread());
  JNIEnv* env = base::android::AttachCurrentThread();
  cronet::Java_CronetBidirectionalStream_onResponseTrailersReceived(
      env, owner_.obj(), GetHeadersArray(env, response_trailers).obj());
}

void CronetBidirectionalStreamAdapter::OnFailed(int error) {
  DCHECK(context_->IsOnNetworkThread());
  stream_failed_ = true;
  JNIEnv* env = base::android::AttachCurrentThread();
  // TODO(mgersh): Add support for NetErrorDetails (http://crbug.com/624942)
  cronet::Java_CronetBidirectionalStream_onError(
      env, owner_.obj(), NetErrorToUrlRequestError(error), error,
      net::QUIC_NO_ERROR,
      ConvertUTF8ToJavaString(env, net::ErrorToString(error)).obj(),
      bidi_stream_->GetTotalReceivedBytes());
}

void CronetBidirectionalStreamAdapter::StartOnNetworkThread(
    std::unique_ptr<net::BidirectionalStreamRequestInfo> request_info) {
  DCHECK(context_->IsOnNetworkThread());
  DCHECK(!bidi_stream_);
  request_info->extra_headers.SetHeaderIfMissing(
      net::HttpRequestHeaders::kUserAgent, context_->GetURLRequestContext()
                                               ->http_user_agent_settings()
                                               ->GetUserAgent());
  bidi_stream_.reset(new net::BidirectionalStream(
      std::move(request_info), context_->GetURLRequestContext()
                                   ->http_transaction_factory()
                                   ->GetSession(),
      send_request_headers_automatically_, this));
}

void CronetBidirectionalStreamAdapter::SendRequestHeadersOnNetworkThread() {
  DCHECK(context_->IsOnNetworkThread());
  DCHECK(!send_request_headers_automatically_);

  if (stream_failed_) {
    // If stream failed between the time when SendRequestHeaders is invoked and
    // SendRequestHeadersOnNetworkThread is executed, do not call into
    // |bidi_stream_| since the underlying stream might have been destroyed.
    // Do not invoke Java callback either, since onError is posted when
    // |stream_failed_| is set to true.
    return;
  }
  bidi_stream_->SendRequestHeaders();
}

void CronetBidirectionalStreamAdapter::ReadDataOnNetworkThread(
    scoped_refptr<IOBufferWithByteBuffer> read_buffer,
    int buffer_size) {
  DCHECK(context_->IsOnNetworkThread());
  DCHECK(read_buffer);
  DCHECK(!read_buffer_);

  read_buffer_ = read_buffer;

  int bytes_read = bidi_stream_->ReadData(read_buffer_.get(), buffer_size);
  // If IO is pending, wait for the BidirectionalStream to call OnDataRead.
  if (bytes_read == net::ERR_IO_PENDING)
    return;

  if (bytes_read < 0) {
    OnFailed(bytes_read);
    return;
  }
  OnDataRead(bytes_read);
}

void CronetBidirectionalStreamAdapter::WritevDataOnNetworkThread(
    std::unique_ptr<PendingWriteData> pending_write_data) {
  DCHECK(context_->IsOnNetworkThread());
  DCHECK(pending_write_data);
  DCHECK(!pending_write_data_);

  if (stream_failed_) {
    // If stream failed between the time when WritevData is invoked and
    // WritevDataOnNetworkThread is executed, do not call into |bidi_stream_|
    // since the underlying stream might have been destroyed. Do not invoke
    // Java callback either, since onError is posted when |stream_failed_| is
    // set to true.
    return;
  }

  pending_write_data_ = std::move(pending_write_data);
  bool end_of_stream = pending_write_data_->jwrite_end_of_stream == JNI_TRUE;
  if (pending_write_data_->write_buffer_list.size() == 1) {
    bidi_stream_->SendData(pending_write_data_->write_buffer_list[0],
                           pending_write_data_->write_buffer_len_list[0],
                           end_of_stream);
  } else {
    bidi_stream_->SendvData(pending_write_data_->write_buffer_list,
                            pending_write_data_->write_buffer_len_list,
                            end_of_stream);
  }
}

void CronetBidirectionalStreamAdapter::DestroyOnNetworkThread(
    bool send_on_canceled) {
  DCHECK(context_->IsOnNetworkThread());
  if (send_on_canceled) {
    JNIEnv* env = base::android::AttachCurrentThread();
    cronet::Java_CronetBidirectionalStream_onCanceled(env, owner_.obj());
  }
  delete this;
}

base::android::ScopedJavaLocalRef<jobjectArray>
CronetBidirectionalStreamAdapter::GetHeadersArray(
    JNIEnv* env,
    const net::SpdyHeaderBlock& header_block) {
  DCHECK(context_->IsOnNetworkThread());

  std::vector<std::string> headers;
  for (const auto& header : header_block) {
    headers.push_back(header.first.as_string());
    headers.push_back(header.second.as_string());
  }
  return base::android::ToJavaArrayOfStrings(env, headers);
}

}  // namespace cronet
