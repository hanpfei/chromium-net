// Copyright 2014 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "components/cronet/android/chromium_url_request.h"

#include "base/android/jni_android.h"
#include "base/android/jni_string.h"
#include "base/macros.h"
#include "components/cronet/android/url_request_adapter.h"
#include "components/cronet/android/url_request_context_adapter.h"
#include "jni/ChromiumUrlRequest_jni.h"
#include "net/base/net_errors.h"
#include "net/base/request_priority.h"
#include "net/http/http_response_headers.h"

using base::android::ConvertUTF8ToJavaString;
using base::android::ConvertJavaStringToUTF8;
using base::android::JavaParamRef;
using base::android::ScopedJavaLocalRef;

namespace cronet {
namespace {

net::RequestPriority ConvertRequestPriority(jint request_priority) {
  switch (request_priority) {
    case REQUEST_PRIORITY_IDLE:
      return net::IDLE;
    case REQUEST_PRIORITY_LOWEST:
      return net::LOWEST;
    case REQUEST_PRIORITY_LOW:
      return net::LOW;
    case REQUEST_PRIORITY_MEDIUM:
      return net::MEDIUM;
    case REQUEST_PRIORITY_HIGHEST:
      return net::HIGHEST;
    default:
      return net::LOWEST;
  }
}

void SetPostContentType(JNIEnv* env,
                        URLRequestAdapter* request_adapter,
                        jstring content_type) {
  std::string method_post("POST");
  request_adapter->SetMethod(method_post);

  std::string content_type_header("Content-Type");
  std::string content_type_string(ConvertJavaStringToUTF8(env, content_type));

  request_adapter->AddHeader(content_type_header, content_type_string);
}

// A delegate of URLRequestAdapter that delivers callbacks to the Java layer.
class JniURLRequestAdapterDelegate
    : public URLRequestAdapter::URLRequestAdapterDelegate {
 public:
  JniURLRequestAdapterDelegate(JNIEnv* env, jobject owner) {
    owner_ = env->NewGlobalRef(owner);
  }

  void OnResponseStarted(URLRequestAdapter* request_adapter) override {
    JNIEnv* env = base::android::AttachCurrentThread();
    cronet::Java_ChromiumUrlRequest_onResponseStarted(env, owner_);
  }

  void OnBytesRead(URLRequestAdapter* request_adapter,
                   int bytes_read) override {
    if (bytes_read != 0) {
      JNIEnv* env = base::android::AttachCurrentThread();
      base::android::ScopedJavaLocalRef<jobject> java_buffer(
          env, env->NewDirectByteBuffer(request_adapter->Data(), bytes_read));
      cronet::Java_ChromiumUrlRequest_onBytesRead(
          env, owner_, java_buffer.obj());
    }
  }

  void OnRequestFinished(URLRequestAdapter* request_adapter) override {
    JNIEnv* env = base::android::AttachCurrentThread();
    cronet::Java_ChromiumUrlRequest_finish(env, owner_);
  }

  int ReadFromUploadChannel(net::IOBuffer* buf, int buf_length) override {
    JNIEnv* env = base::android::AttachCurrentThread();
    base::android::ScopedJavaLocalRef<jobject> java_buffer(
        env, env->NewDirectByteBuffer(buf->data(), buf_length));
    jint bytes_read = cronet::Java_ChromiumUrlRequest_readFromUploadChannel(
        env, owner_, java_buffer.obj());
    return bytes_read;
  }

 protected:
  ~JniURLRequestAdapterDelegate() override {
    JNIEnv* env = base::android::AttachCurrentThread();
    env->DeleteGlobalRef(owner_);
  }

 private:
  jobject owner_;

  DISALLOW_COPY_AND_ASSIGN(JniURLRequestAdapterDelegate);
};

}  // namespace

// Explicitly register static JNI functions.
bool ChromiumUrlRequestRegisterJni(JNIEnv* env) {
  return RegisterNativesImpl(env);
}

static jlong CreateRequestAdapter(JNIEnv* env,
                                  const JavaParamRef<jobject>& jcaller,
                                  jlong jurl_request_context_adapter,
                                  const JavaParamRef<jstring>& jurl,
                                  jint jrequest_priority) {
  URLRequestContextAdapter* context_adapter =
      reinterpret_cast<URLRequestContextAdapter*>(jurl_request_context_adapter);
  DCHECK(context_adapter);

  GURL url(ConvertJavaStringToUTF8(env, jurl));

  VLOG(1) << "New chromium network request: " << url.possibly_invalid_spec();

  URLRequestAdapter* adapter = new URLRequestAdapter(
      context_adapter, new JniURLRequestAdapterDelegate(env, jcaller), url,
      ConvertRequestPriority(jrequest_priority));

  return reinterpret_cast<jlong>(adapter);
}

// synchronized
static void AddHeader(JNIEnv* env,
                      const JavaParamRef<jobject>& jcaller,
                      jlong jurl_request_adapter,
                      const JavaParamRef<jstring>& jheader_name,
                      const JavaParamRef<jstring>& jheader_value) {
  URLRequestAdapter* request_adapter =
      reinterpret_cast<URLRequestAdapter*>(jurl_request_adapter);
  DCHECK(request_adapter);

  std::string header_name(ConvertJavaStringToUTF8(env, jheader_name));
  std::string header_value(ConvertJavaStringToUTF8(env, jheader_value));

  request_adapter->AddHeader(header_name, header_value);
}

static void SetMethod(JNIEnv* env,
                      const JavaParamRef<jobject>& jcaller,
                      jlong jurl_request_adapter,
                      const JavaParamRef<jstring>& jmethod) {
  URLRequestAdapter* request_adapter =
      reinterpret_cast<URLRequestAdapter*>(jurl_request_adapter);
  DCHECK(request_adapter);

  std::string method(ConvertJavaStringToUTF8(env, jmethod));

  request_adapter->SetMethod(method);
}

static void SetUploadData(JNIEnv* env,
                          const JavaParamRef<jobject>& jcaller,
                          jlong jurl_request_adapter,
                          const JavaParamRef<jstring>& jcontent_type,
                          const JavaParamRef<jbyteArray>& jcontent) {
  URLRequestAdapter* request_adapter =
      reinterpret_cast<URLRequestAdapter*>(jurl_request_adapter);
  DCHECK(request_adapter);
  SetPostContentType(env, request_adapter, jcontent_type);

  if (jcontent != nullptr) {
    jsize size = env->GetArrayLength(jcontent);
    if (size > 0) {
      jbyte* content_bytes = env->GetByteArrayElements(jcontent, nullptr);
      request_adapter->SetUploadContent(
          reinterpret_cast<const char*>(content_bytes), size);
      env->ReleaseByteArrayElements(jcontent, content_bytes, 0);
    }
  }
}

static void SetUploadChannel(JNIEnv* env,
                             const JavaParamRef<jobject>& jcaller,
                             jlong jurl_request_adapter,
                             const JavaParamRef<jstring>& jcontent_type,
                             jlong jcontent_length) {
  URLRequestAdapter* request_adapter =
      reinterpret_cast<URLRequestAdapter*>(jurl_request_adapter);
  DCHECK(request_adapter);
  SetPostContentType(env, request_adapter, jcontent_type);

  request_adapter->SetUploadChannel(env, jcontent_length);
}

static void EnableChunkedUpload(JNIEnv* env,
                                const JavaParamRef<jobject>& jcaller,
                                jlong jurl_request_adapter,
                                const JavaParamRef<jstring>& jcontent_type) {
  URLRequestAdapter* request_adapter =
      reinterpret_cast<URLRequestAdapter*>(jurl_request_adapter);
  DCHECK(request_adapter);
  SetPostContentType(env, request_adapter, jcontent_type);

  request_adapter->EnableChunkedUpload();
}

static void AppendChunk(JNIEnv* env,
                        const JavaParamRef<jobject>& jcaller,
                        jlong jurl_request_adapter,
                        const JavaParamRef<jobject>& jchunk_byte_buffer,
                        jint jchunk_size,
                        jboolean jis_last_chunk) {
  URLRequestAdapter* request_adapter =
      reinterpret_cast<URLRequestAdapter*>(jurl_request_adapter);
  DCHECK(request_adapter);
  DCHECK(jchunk_byte_buffer);

  void* chunk = env->GetDirectBufferAddress(jchunk_byte_buffer);
  request_adapter->AppendChunk(reinterpret_cast<const char*>(chunk),
                               jchunk_size, jis_last_chunk);
}

/* synchronized */
static void Start(JNIEnv* env,
                  const JavaParamRef<jobject>& jcaller,
                  jlong jurl_request_adapter) {
  URLRequestAdapter* request_adapter =
      reinterpret_cast<URLRequestAdapter*>(jurl_request_adapter);
  if (request_adapter != nullptr)
    request_adapter->Start();
}

/* synchronized */
static void DestroyRequestAdapter(JNIEnv* env,
                                  const JavaParamRef<jobject>& jcaller,
                                  jlong jurl_request_adapter) {
  URLRequestAdapter* request_adapter =
      reinterpret_cast<URLRequestAdapter*>(jurl_request_adapter);
  if (request_adapter != nullptr)
    request_adapter->Destroy();
}

/* synchronized */
static void Cancel(JNIEnv* env,
                   const JavaParamRef<jobject>& jcaller,
                   jlong jurl_request_adapter) {
  URLRequestAdapter* request_adapter =
      reinterpret_cast<URLRequestAdapter*>(jurl_request_adapter);
  if (request_adapter != nullptr)
    request_adapter->Cancel();
}

static jint GetErrorCode(JNIEnv* env,
                         const JavaParamRef<jobject>& jcaller,
                         jlong jurl_request_adapter) {
  URLRequestAdapter* request_adapter =
      reinterpret_cast<URLRequestAdapter*>(jurl_request_adapter);
  DCHECK(request_adapter);
  int error_code = request_adapter->error_code();
  switch (error_code) {
    // TODO(mef): Investigate returning success on positive values, too, as
    // they technically indicate success.
    case net::OK:
      return REQUEST_ERROR_SUCCESS;

    // TODO(mef): Investigate this. The fact is that Chrome does not do this,
    // and this library is not just being used for downloads.

    // Comment from src/content/browser/download/download_resource_handler.cc:
    // ERR_CONTENT_LENGTH_MISMATCH and ERR_INCOMPLETE_CHUNKED_ENCODING are
    // allowed since a number of servers in the wild close the connection too
    // early by mistake. Other browsers - IE9, Firefox 11.0, and Safari 5.1.4 -
    // treat downloads as complete in both cases, so we follow their lead.
    case net::ERR_CONTENT_LENGTH_MISMATCH:
    case net::ERR_INCOMPLETE_CHUNKED_ENCODING:
      return REQUEST_ERROR_SUCCESS;

    case net::ERR_INVALID_URL:
    case net::ERR_DISALLOWED_URL_SCHEME:
    case net::ERR_UNKNOWN_URL_SCHEME:
      return REQUEST_ERROR_MALFORMED_URL;

    case net::ERR_CONNECTION_TIMED_OUT:
      return REQUEST_ERROR_CONNECTION_TIMED_OUT;

    case net::ERR_NAME_NOT_RESOLVED:
      return REQUEST_ERROR_UNKNOWN_HOST;
    case net::ERR_TOO_MANY_REDIRECTS:
      return REQUEST_ERROR_TOO_MANY_REDIRECTS;
  }
  return REQUEST_ERROR_UNKNOWN;
}

static ScopedJavaLocalRef<jstring> GetErrorString(
    JNIEnv* env,
    const JavaParamRef<jobject>& jcaller,
    jlong jurl_request_adapter) {
  URLRequestAdapter* request_adapter =
      reinterpret_cast<URLRequestAdapter*>(jurl_request_adapter);
  DCHECK(request_adapter);
  int error_code = request_adapter->error_code();
  char buffer[200];
  std::string error_string = net::ErrorToString(error_code);
  snprintf(buffer,
           sizeof(buffer),
           "System error: %s(%d)",
           error_string.c_str(),
           error_code);
  return ConvertUTF8ToJavaString(env, buffer);
}

static jint GetHttpStatusCode(JNIEnv* env,
                              const JavaParamRef<jobject>& jcaller,
                              jlong jurl_request_adapter) {
  URLRequestAdapter* request_adapter =
      reinterpret_cast<URLRequestAdapter*>(jurl_request_adapter);
  DCHECK(request_adapter);
  return request_adapter->http_status_code();
}

static ScopedJavaLocalRef<jstring> GetHttpStatusText(
    JNIEnv* env,
    const JavaParamRef<jobject>& jcaller,
    jlong jurl_request_adapter) {
  URLRequestAdapter* request_adapter =
      reinterpret_cast<URLRequestAdapter*>(jurl_request_adapter);
  DCHECK(request_adapter);
  return ConvertUTF8ToJavaString(env, request_adapter->http_status_text());
}

static ScopedJavaLocalRef<jstring> GetContentType(
    JNIEnv* env,
    const JavaParamRef<jobject>& jcaller,
    jlong jurl_request_adapter) {
  URLRequestAdapter* request_adapter =
      reinterpret_cast<URLRequestAdapter*>(jurl_request_adapter);
  DCHECK(request_adapter);
  std::string type = request_adapter->content_type();
  if (!type.empty()) {
    return ConvertUTF8ToJavaString(env, type.c_str());
  } else {
    return ScopedJavaLocalRef<jstring>();
  }
}

static jlong GetContentLength(JNIEnv* env,
                              const JavaParamRef<jobject>& jcaller,
                              jlong jurl_request_adapter) {
  URLRequestAdapter* request_adapter =
      reinterpret_cast<URLRequestAdapter*>(jurl_request_adapter);
  DCHECK(request_adapter);
  return request_adapter->content_length();
}

static ScopedJavaLocalRef<jstring> GetHeader(
    JNIEnv* env,
    const JavaParamRef<jobject>& jcaller,
    jlong jurl_request_adapter,
    const JavaParamRef<jstring>& jheader_name) {
  URLRequestAdapter* request_adapter =
      reinterpret_cast<URLRequestAdapter*>(jurl_request_adapter);
  DCHECK(request_adapter);
  std::string header_name = ConvertJavaStringToUTF8(env, jheader_name);
  std::string header_value = request_adapter->GetHeader(header_name);
  if (!header_value.empty())
    return ConvertUTF8ToJavaString(env, header_value.c_str());
  return ScopedJavaLocalRef<jstring>();
}

static void GetAllHeaders(JNIEnv* env,
                          const JavaParamRef<jobject>& jcaller,
                          jlong jurl_request_adapter,
                          const JavaParamRef<jobject>& jheaders_map) {
  URLRequestAdapter* request_adapter =
      reinterpret_cast<URLRequestAdapter*>(jurl_request_adapter);
  DCHECK(request_adapter);

  net::HttpResponseHeaders* headers = request_adapter->GetResponseHeaders();
  if (headers == nullptr)
    return;

  size_t iter = 0;
  std::string header_name;
  std::string header_value;
  while (headers->EnumerateHeaderLines(&iter, &header_name, &header_value)) {
    ScopedJavaLocalRef<jstring> name =
        ConvertUTF8ToJavaString(env, header_name);
    ScopedJavaLocalRef<jstring> value =
        ConvertUTF8ToJavaString(env, header_value);
    Java_ChromiumUrlRequest_onAppendResponseHeader(env, jcaller, jheaders_map,
                                                   name.obj(), value.obj());
  }

  // Some implementations (notably HttpURLConnection) include a mapping for the
  // null key; in HTTP's case, this maps to the HTTP status line.
  ScopedJavaLocalRef<jstring> status_line =
      ConvertUTF8ToJavaString(env, headers->GetStatusLine());
  Java_ChromiumUrlRequest_onAppendResponseHeader(env, jcaller, jheaders_map,
                                                 nullptr, status_line.obj());
}

static ScopedJavaLocalRef<jstring> GetNegotiatedProtocol(
    JNIEnv* env,
    const JavaParamRef<jobject>& jcaller,
    jlong jurl_request_adapter) {
  URLRequestAdapter* request_adapter =
      reinterpret_cast<URLRequestAdapter*>(jurl_request_adapter);
  DCHECK(request_adapter);

  std::string negotiated_protocol = request_adapter->GetNegotiatedProtocol();
  return ConvertUTF8ToJavaString(env, negotiated_protocol.c_str());
}

static jboolean GetWasCached(JNIEnv* env,
                             const JavaParamRef<jobject>& jcaller,
                             jlong jurl_request_adapter) {
  URLRequestAdapter* request_adapter =
      reinterpret_cast<URLRequestAdapter*>(jurl_request_adapter);
  DCHECK(request_adapter);

  bool was_cached = request_adapter->GetWasCached();
  return was_cached ? JNI_TRUE : JNI_FALSE;
}

static void DisableRedirects(JNIEnv* env,
                             const JavaParamRef<jobject>& jcaller,
                             jlong jrequest_adapter) {
  URLRequestAdapter* request_adapter =
      reinterpret_cast<URLRequestAdapter*>(jrequest_adapter);
  DCHECK(request_adapter);
  request_adapter->DisableRedirects();
}

}  // namespace cronet
