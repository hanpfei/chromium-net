// Copyright 2015 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "quic_test_server.h"

#include "base/android/jni_android.h"
#include "base/android/jni_string.h"
#include "base/android/path_utils.h"
#include "base/bind.h"
#include "base/files/file_path.h"
#include "base/files/file_util.h"
#include "base/test/test_support_android.h"
#include "base/threading/thread.h"
#include "components/cronet/android/test/cronet_test_util.h"
#include "jni/QuicTestServer_jni.h"
#include "net/base/ip_address.h"
#include "net/base/ip_endpoint.h"
#include "net/quic/chromium/crypto/proof_source_chromium.h"
#include "net/test/test_data_directory.h"
#include "net/tools/quic/quic_in_memory_cache.h"
#include "net/tools/quic/quic_simple_server.h"

using base::android::JavaParamRef;
using base::android::ScopedJavaLocalRef;

namespace cronet {

namespace {

static const int kServerPort = 6121;

base::Thread* g_quic_server_thread = nullptr;
net::QuicSimpleServer* g_quic_server = nullptr;

void StartOnServerThread(const base::FilePath& test_files_root,
                         const base::FilePath& test_data_dir) {
  DCHECK(g_quic_server_thread->task_runner()->BelongsToCurrentThread());
  DCHECK(!g_quic_server);

  // Set up in-memory cache.
  base::FilePath file_dir = test_files_root.Append("quic_data");
  CHECK(base::PathExists(file_dir)) << "Quic data does not exist";
  net::QuicInMemoryCache::GetInstance()->InitializeFromDirectory(
      file_dir.value());
  net::QuicConfig config;

  // Set up server certs.
  base::FilePath directory = test_data_dir.Append("net/data/ssl/certificates");
  std::unique_ptr<net::ProofSourceChromium> proof_source(
      new net::ProofSourceChromium());
  CHECK(proof_source->Initialize(
      directory.Append("quic_test.example.com.crt"),
      directory.Append("quic_test.example.com.key.pkcs8"),
      directory.Append("quic_test.example.com.key.sct")));
  g_quic_server = new net::QuicSimpleServer(std::move(proof_source), config,
                                            net::QuicSupportedVersions());

  // Start listening.
  int rv = g_quic_server->Listen(
      net::IPEndPoint(net::IPAddress::IPv4AllZeros(), kServerPort));
  CHECK_GE(rv, 0) << "Quic server fails to start";
  JNIEnv* env = base::android::AttachCurrentThread();
  Java_QuicTestServer_onServerStarted(env);
}

void ShutdownOnServerThread() {
  DCHECK(g_quic_server_thread->task_runner()->BelongsToCurrentThread());
  g_quic_server->Shutdown();
  delete g_quic_server;
}

}  // namespace

// Quic server is currently hardcoded to run on port 6121 of the localhost on
// the device.
void StartQuicTestServer(JNIEnv* env,
                         const JavaParamRef<jclass>& /*jcaller*/,
                         const JavaParamRef<jstring>& jtest_files_root,
                         const JavaParamRef<jstring>& jtest_data_dir) {
  DCHECK(!g_quic_server_thread);
  base::FilePath test_data_dir(
      base::android::ConvertJavaStringToUTF8(env, jtest_data_dir));
  base::InitAndroidTestPaths(test_data_dir);

  g_quic_server_thread = new base::Thread("quic server thread");
  base::Thread::Options thread_options;
  thread_options.message_loop_type = base::MessageLoop::TYPE_IO;
  bool started = g_quic_server_thread->StartWithOptions(thread_options);
  DCHECK(started);
  base::FilePath test_files_root(
      base::android::ConvertJavaStringToUTF8(env, jtest_files_root));
  g_quic_server_thread->task_runner()->PostTask(
      FROM_HERE,
      base::Bind(&StartOnServerThread, test_files_root, test_data_dir));
}

void ShutdownQuicTestServer(JNIEnv* env,
                            const JavaParamRef<jclass>& /*jcaller*/) {
  DCHECK(!g_quic_server_thread->task_runner()->BelongsToCurrentThread());
  g_quic_server_thread->task_runner()->PostTask(
      FROM_HERE, base::Bind(&ShutdownOnServerThread));
  delete g_quic_server_thread;
}

ScopedJavaLocalRef<jstring> GetServerHost(
    JNIEnv* env,
    const JavaParamRef<jclass>& /*jcaller*/) {
  return base::android::ConvertUTF8ToJavaString(env, kFakeQuicDomain);
}

int GetServerPort(JNIEnv* env, const JavaParamRef<jclass>& /*jcaller*/) {
  return kServerPort;
}

bool RegisterQuicTestServer(JNIEnv* env) {
  return RegisterNativesImpl(env);
}

}  // namespace cronet
