// Copyright 2016 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "components/cronet/ios/test/quic_test_server.h"

#include <utility>

#include "base/bind.h"
#include "base/files/file_path.h"
#include "base/files/file_util.h"
#include "base/path_service.h"
#include "base/synchronization/waitable_event.h"
#include "base/threading/thread.h"
#include "net/base/ip_address.h"
#include "net/base/ip_endpoint.h"
#include "net/quic/chromium/crypto/proof_source_chromium.h"
#include "net/spdy/spdy_header_block.h"
#include "net/test/test_data_directory.h"
#include "net/tools/quic/quic_in_memory_cache.h"
#include "net/tools/quic/quic_simple_server.h"

namespace cronet {

// This must match the certificate used (quic_test.example.com.crt and
// quic_test.example.com.key.pkcs8).
const char kTestServerDomain[] = "test.example.com";
const int kTestServerPort = 6121;
const char kTestServerHost[] = "test.example.com:6121";
const char kTestServerUrl[] = "https://test.example.com:6121/hello.txt";

const char kStatusHeader[] = ":status";

const char kHelloPath[] = "/hello.txt";
const char kHelloBodyValue[] = "Hello from QUIC Server";
const char kHelloStatus[] = "200";

const char kHelloHeaderName[] = "hello_header";
const char kHelloHeaderValue[] = "hello header value";

const char kHelloTrailerName[] = "hello_trailer";
const char kHelloTrailerValue[] = "hello trailer value";

base::Thread* g_quic_server_thread = nullptr;
net::QuicSimpleServer* g_quic_server = nullptr;

void SetupQuicInMemoryCache() {
  static bool setup_done = false;
  if (setup_done)
    return;
  setup_done = true;
  net::SpdyHeaderBlock headers;
  headers.ReplaceOrAppendHeader(kHelloHeaderName, kHelloHeaderValue);
  headers.ReplaceOrAppendHeader(kStatusHeader, kHelloStatus);
  net::SpdyHeaderBlock trailers;
  trailers.ReplaceOrAppendHeader(kHelloTrailerName, kHelloTrailerValue);
  net::QuicInMemoryCache::GetInstance()->AddResponse(
      kTestServerHost, kHelloPath, std::move(headers), kHelloBodyValue,
      std::move(trailers));
}

void StartQuicServerOnServerThread(const base::FilePath& test_files_root,
                                   base::WaitableEvent* server_started_event) {
  DCHECK(g_quic_server_thread->task_runner()->BelongsToCurrentThread());
  DCHECK(!g_quic_server);

  // Set up in-memory cache.
  SetupQuicInMemoryCache();
  net::QuicConfig config;
  // Set up server certs.
  base::FilePath directory;
  directory = test_files_root;
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
      net::IPEndPoint(net::IPAddress::IPv4AllZeros(), kTestServerPort));
  CHECK_GE(rv, 0) << "Quic server fails to start";
  server_started_event->Signal();
}

void ShutdownOnServerThread(base::WaitableEvent* server_stopped_event) {
  DCHECK(g_quic_server_thread->task_runner()->BelongsToCurrentThread());
  g_quic_server->Shutdown();
  delete g_quic_server;
  g_quic_server = nullptr;
  server_stopped_event->Signal();
}

// Quic server is currently hardcoded to run on port 6121 of the localhost on
// the device.
bool StartQuicTestServer() {
  DCHECK(!g_quic_server_thread);
  g_quic_server_thread = new base::Thread("quic server thread");
  base::Thread::Options thread_options;
  thread_options.message_loop_type = base::MessageLoop::TYPE_IO;
  bool started = g_quic_server_thread->StartWithOptions(thread_options);
  DCHECK(started);
  base::FilePath test_files_root;
  if (!PathService::Get(base::DIR_EXE, &test_files_root))
    return false;

  base::WaitableEvent server_started_event(
      base::WaitableEvent::ResetPolicy::MANUAL,
      base::WaitableEvent::InitialState::NOT_SIGNALED);
  g_quic_server_thread->task_runner()->PostTask(
      FROM_HERE, base::Bind(&StartQuicServerOnServerThread, test_files_root,
                            &server_started_event));
  server_started_event.Wait();
  return true;
}

void ShutdownQuicTestServer() {
  if (!g_quic_server_thread)
    return;
  DCHECK(!g_quic_server_thread->task_runner()->BelongsToCurrentThread());
  base::WaitableEvent server_stopped_event(
      base::WaitableEvent::ResetPolicy::MANUAL,
      base::WaitableEvent::InitialState::NOT_SIGNALED);
  g_quic_server_thread->task_runner()->PostTask(
      FROM_HERE, base::Bind(&ShutdownOnServerThread, &server_stopped_event));
  server_stopped_event.Wait();
  delete g_quic_server_thread;
  g_quic_server_thread = nullptr;
}

}  // namespace cronet
