// Copyright 2015 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "components/cronet/android/cronet_upload_data_stream_adapter.h"

#include <string>
#include <utility>

#include "base/android/jni_android.h"
#include "base/android/jni_string.h"
#include "base/bind.h"
#include "base/logging.h"
#include "base/single_thread_task_runner.h"
#include "base/threading/thread_task_runner_handle.h"
#include "components/cronet/android/cronet_url_request_adapter.h"
#include "jni/CronetUploadDataStream_jni.h"

using base::android::JavaParamRef;

namespace cronet {

CronetUploadDataStreamAdapter::CronetUploadDataStreamAdapter(
    JNIEnv* env,
    jobject jupload_data_stream) {
  jupload_data_stream_.Reset(env, jupload_data_stream);
}

CronetUploadDataStreamAdapter::~CronetUploadDataStreamAdapter() {
}

void CronetUploadDataStreamAdapter::InitializeOnNetworkThread(
    base::WeakPtr<CronetUploadDataStream> upload_data_stream) {
  DCHECK(!upload_data_stream_);
  DCHECK(!network_task_runner_.get());

  upload_data_stream_ = upload_data_stream;
  network_task_runner_ = base::ThreadTaskRunnerHandle::Get();
  DCHECK(network_task_runner_);
}

void CronetUploadDataStreamAdapter::Read(net::IOBuffer* buffer, int buf_len) {
  DCHECK(upload_data_stream_);
  DCHECK(network_task_runner_);
  DCHECK(network_task_runner_->BelongsToCurrentThread());
  DCHECK_GT(buf_len, 0);
  DCHECK(!buffer_.get());
  buffer_ = buffer;

  // TODO(mmenke):  Consider preserving the java buffer across reads, when the
  // IOBuffer's data pointer and its length are unchanged.
  JNIEnv* env = base::android::AttachCurrentThread();
  base::android::ScopedJavaLocalRef<jobject> java_buffer(
      env, env->NewDirectByteBuffer(buffer->data(), buf_len));
  Java_CronetUploadDataStream_readData(env, jupload_data_stream_.obj(),
                                       java_buffer.obj());
}

void CronetUploadDataStreamAdapter::Rewind() {
  DCHECK(upload_data_stream_);
  DCHECK(network_task_runner_->BelongsToCurrentThread());

  JNIEnv* env = base::android::AttachCurrentThread();
  Java_CronetUploadDataStream_rewind(env, jupload_data_stream_.obj());
}

void CronetUploadDataStreamAdapter::OnUploadDataStreamDestroyed() {
  // If CronetUploadDataStream::InitInternal was never called,
  // |upload_data_stream_| and |network_task_runner_| will be NULL.
  DCHECK(!network_task_runner_ ||
         network_task_runner_->BelongsToCurrentThread());

  JNIEnv* env = base::android::AttachCurrentThread();
  Java_CronetUploadDataStream_onUploadDataStreamDestroyed(
      env, jupload_data_stream_.obj());
  // |this| is invalid here since the Java call above effectively destroys it.
}

void CronetUploadDataStreamAdapter::OnReadSucceeded(
    JNIEnv* env,
    const JavaParamRef<jobject>& jcaller,
    int bytes_read,
    bool final_chunk) {
  DCHECK(!network_task_runner_->BelongsToCurrentThread());
  DCHECK(bytes_read > 0 || (final_chunk && bytes_read == 0));

  buffer_ = nullptr;
  network_task_runner_->PostTask(
      FROM_HERE, base::Bind(&CronetUploadDataStream::OnReadSuccess,
                            upload_data_stream_, bytes_read, final_chunk));
}

void CronetUploadDataStreamAdapter::OnRewindSucceeded(
    JNIEnv* env,
    const JavaParamRef<jobject>& jcaller) {
  DCHECK(!network_task_runner_->BelongsToCurrentThread());

  network_task_runner_->PostTask(
      FROM_HERE,
      base::Bind(&CronetUploadDataStream::OnRewindSuccess,
                 upload_data_stream_));
}

void CronetUploadDataStreamAdapter::Destroy(JNIEnv* env,
                                            const JavaParamRef<jobject>& jobj) {
  delete this;
}

bool CronetUploadDataStreamAdapterRegisterJni(JNIEnv* env) {
  return RegisterNativesImpl(env);
}

static jlong AttachUploadDataToRequest(
    JNIEnv* env,
    const JavaParamRef<jobject>& jupload_data_stream,
    jlong jcronet_url_request_adapter,
    jlong jlength) {
  CronetURLRequestAdapter* request_adapter =
      reinterpret_cast<CronetURLRequestAdapter*>(jcronet_url_request_adapter);
  DCHECK(request_adapter != nullptr);

  CronetUploadDataStreamAdapter* adapter =
      new CronetUploadDataStreamAdapter(env, jupload_data_stream);

  std::unique_ptr<CronetUploadDataStream> upload_data_stream(
      new CronetUploadDataStream(adapter, jlength));

  request_adapter->SetUpload(std::move(upload_data_stream));

  return reinterpret_cast<jlong>(adapter);
}

static jlong CreateAdapterForTesting(
    JNIEnv* env,
    const JavaParamRef<jobject>& jupload_data_stream) {
  CronetUploadDataStreamAdapter* adapter =
      new CronetUploadDataStreamAdapter(env, jupload_data_stream);
  return reinterpret_cast<jlong>(adapter);
}

static jlong CreateUploadDataStreamForTesting(
    JNIEnv* env,
    const JavaParamRef<jobject>& jupload_data_stream,
    jlong jlength,
    jlong jadapter) {
  CronetUploadDataStreamAdapter* adapter =
      reinterpret_cast<CronetUploadDataStreamAdapter*>(jadapter);
  CronetUploadDataStream* upload_data_stream =
      new CronetUploadDataStream(adapter, jlength);
  return reinterpret_cast<jlong>(upload_data_stream);
}

}  // namespace cronet
