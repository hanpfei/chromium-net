// Copyright 2015 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "components/cronet/android/test/test_upload_data_stream_handler.h"

#include <stddef.h>
#include <string>
#include <utility>

#include "base/android/jni_android.h"
#include "base/android/jni_string.h"
#include "base/bind.h"
#include "jni/TestUploadDataStreamHandler_jni.h"
#include "net/base/net_errors.h"

using base::android::JavaParamRef;

namespace cronet {

static const size_t kReadBufferSize = 32768;

TestUploadDataStreamHandler::TestUploadDataStreamHandler(
    std::unique_ptr<net::UploadDataStream> upload_data_stream,
    JNIEnv* env,
    jobject jtest_upload_data_stream_handler)
    : init_callback_invoked_(false),
      read_callback_invoked_(false),
      bytes_read_(0),
      network_thread_(new base::Thread("network")) {
  upload_data_stream_ = std::move(upload_data_stream);
  base::Thread::Options options;
  options.message_loop_type = base::MessageLoop::TYPE_IO;
  network_thread_->StartWithOptions(options);
  jtest_upload_data_stream_handler_.Reset(env,
                                          jtest_upload_data_stream_handler);
}

TestUploadDataStreamHandler::~TestUploadDataStreamHandler() {
}

void TestUploadDataStreamHandler::Destroy(
    JNIEnv* env,
    const JavaParamRef<jobject>& jcaller) {
  DCHECK(!network_thread_->task_runner()->BelongsToCurrentThread());
  // Stick network_thread_ in a local, so |this| may be destroyed from the
  // network thread before the network thread is destroyed.
  std::unique_ptr<base::Thread> network_thread = std::move(network_thread_);
  network_thread->task_runner()->DeleteSoon(FROM_HERE, this);
  // Deleting thread stops it after all tasks are completed.
  network_thread.reset();
}

void TestUploadDataStreamHandler::OnInitCompleted(int res) {
  DCHECK(network_thread_->task_runner()->BelongsToCurrentThread());
  init_callback_invoked_ = true;
  JNIEnv* env = base::android::AttachCurrentThread();
  cronet::Java_TestUploadDataStreamHandler_onInitCompleted(
      env, jtest_upload_data_stream_handler_.obj(), res);
}

void TestUploadDataStreamHandler::OnReadCompleted(int res) {
  DCHECK(network_thread_->task_runner()->BelongsToCurrentThread());
  read_callback_invoked_ = true;
  bytes_read_ = res;
  NotifyJavaReadCompleted();
}

void TestUploadDataStreamHandler::Init(JNIEnv* env,
                                       const JavaParamRef<jobject>& jcaller) {
  DCHECK(!network_thread_->task_runner()->BelongsToCurrentThread());
  network_thread_->task_runner()->PostTask(
      FROM_HERE, base::Bind(&TestUploadDataStreamHandler::InitOnNetworkThread,
                            base::Unretained(this)));
}

void TestUploadDataStreamHandler::Read(JNIEnv* env,
                                       const JavaParamRef<jobject>& jcaller) {
  DCHECK(!network_thread_->task_runner()->BelongsToCurrentThread());
  network_thread_->task_runner()->PostTask(
      FROM_HERE, base::Bind(&TestUploadDataStreamHandler::ReadOnNetworkThread,
                            base::Unretained(this)));
}

void TestUploadDataStreamHandler::Reset(JNIEnv* env,
                                        const JavaParamRef<jobject>& jcaller) {
  DCHECK(!network_thread_->task_runner()->BelongsToCurrentThread());
  network_thread_->task_runner()->PostTask(
      FROM_HERE, base::Bind(&TestUploadDataStreamHandler::ResetOnNetworkThread,
                            base::Unretained(this)));
}

void TestUploadDataStreamHandler::CheckInitCallbackNotInvoked(
    JNIEnv* env,
    const JavaParamRef<jobject>& jcaller) {
  DCHECK(!network_thread_->task_runner()->BelongsToCurrentThread());
  network_thread_->task_runner()->PostTask(
      FROM_HERE, base::Bind(&TestUploadDataStreamHandler::
                                CheckInitCallbackNotInvokedOnNetworkThread,
                            base::Unretained(this)));
}

void TestUploadDataStreamHandler::CheckReadCallbackNotInvoked(
    JNIEnv* env,
    const JavaParamRef<jobject>& jcaller) {
  DCHECK(!network_thread_->task_runner()->BelongsToCurrentThread());
  network_thread_->task_runner()->PostTask(
      FROM_HERE, base::Bind(&TestUploadDataStreamHandler::
                                CheckReadCallbackNotInvokedOnNetworkThread,
                            base::Unretained(this)));
}

void TestUploadDataStreamHandler::InitOnNetworkThread() {
  DCHECK(network_thread_->task_runner()->BelongsToCurrentThread());
  init_callback_invoked_ = false;
  read_buffer_ = nullptr;
  bytes_read_ = 0;
  int res = upload_data_stream_->Init(base::Bind(
      &TestUploadDataStreamHandler::OnInitCompleted, base::Unretained(this)));
  JNIEnv* env = base::android::AttachCurrentThread();
  cronet::Java_TestUploadDataStreamHandler_onInitCalled(
      env, jtest_upload_data_stream_handler_.obj(), res);

  if (res == net::OK) {
    cronet::Java_TestUploadDataStreamHandler_onInitCompleted(
        env, jtest_upload_data_stream_handler_.obj(), res);
  }
}

void TestUploadDataStreamHandler::ReadOnNetworkThread() {
  DCHECK(network_thread_->task_runner()->BelongsToCurrentThread());
  read_callback_invoked_ = false;
  if (!read_buffer_.get())
    read_buffer_ = new net::IOBufferWithSize(kReadBufferSize);

  int bytes_read = upload_data_stream_->Read(
      read_buffer_.get(), kReadBufferSize,
      base::Bind(&TestUploadDataStreamHandler::OnReadCompleted,
                 base::Unretained(this)));
  if (bytes_read == net::OK) {
    bytes_read_ = bytes_read;
    NotifyJavaReadCompleted();
  }
}

void TestUploadDataStreamHandler::ResetOnNetworkThread() {
  DCHECK(network_thread_->task_runner()->BelongsToCurrentThread());
  read_buffer_ = nullptr;
  bytes_read_ = 0;
  upload_data_stream_->Reset();
  JNIEnv* env = base::android::AttachCurrentThread();
  cronet::Java_TestUploadDataStreamHandler_onResetCompleted(
      env, jtest_upload_data_stream_handler_.obj());
}

void TestUploadDataStreamHandler::CheckInitCallbackNotInvokedOnNetworkThread() {
  DCHECK(network_thread_->task_runner()->BelongsToCurrentThread());
  JNIEnv* env = base::android::AttachCurrentThread();
  cronet::Java_TestUploadDataStreamHandler_onCheckInitCallbackNotInvoked(
      env, jtest_upload_data_stream_handler_.obj(), !init_callback_invoked_);
}

void TestUploadDataStreamHandler::CheckReadCallbackNotInvokedOnNetworkThread() {
  DCHECK(network_thread_->task_runner()->BelongsToCurrentThread());
  JNIEnv* env = base::android::AttachCurrentThread();
  cronet::Java_TestUploadDataStreamHandler_onCheckReadCallbackNotInvoked(
      env, jtest_upload_data_stream_handler_.obj(), !read_callback_invoked_);
}

void TestUploadDataStreamHandler::NotifyJavaReadCompleted() {
  DCHECK(network_thread_->task_runner()->BelongsToCurrentThread());
  JNIEnv* env = base::android::AttachCurrentThread();
  std::string data_read = "";
  if (read_buffer_.get() && bytes_read_ > 0)
    data_read = std::string(read_buffer_->data(), bytes_read_);
  cronet::Java_TestUploadDataStreamHandler_onReadCompleted(
      env, jtest_upload_data_stream_handler_.obj(), bytes_read_,
      base::android::ConvertUTF8ToJavaString(env, data_read).obj());
}

static jlong CreateTestUploadDataStreamHandler(
    JNIEnv* env,
    const JavaParamRef<jobject>& jtest_upload_data_stream_handler,
    jlong jupload_data_stream) {
  std::unique_ptr<net::UploadDataStream> upload_data_stream(
      reinterpret_cast<net::UploadDataStream*>(jupload_data_stream));
  TestUploadDataStreamHandler* handler = new TestUploadDataStreamHandler(
      std::move(upload_data_stream), env, jtest_upload_data_stream_handler);
  return reinterpret_cast<jlong>(handler);
}

bool TestUploadDataStreamHandlerRegisterJni(JNIEnv* env) {
  return RegisterNativesImpl(env);
}

}  // namespace cronet
