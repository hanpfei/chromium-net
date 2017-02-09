// Copyright 2016 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#import "components/cronet/ios/Cronet.h"

#include <memory>

#include "base/lazy_instance.h"
#include "base/logging.h"
#include "base/memory/scoped_vector.h"
#include "base/strings/sys_string_conversions.h"
#include "components/cronet/ios/cronet_environment.h"
#include "components/cronet/url_request_context_config.h"

namespace {

// Currently there is one and only one instance of CronetEnvironment,
// which is leaked at the shutdown. We should consider allowing multiple
// instances if that makes sense in the future.
base::LazyInstance<std::unique_ptr<cronet::CronetEnvironment>>::Leaky
    gChromeNet = LAZY_INSTANCE_INITIALIZER;

BOOL gHttp2Enabled = YES;
BOOL gQuicEnabled = NO;
ScopedVector<cronet::URLRequestContextConfig::QuicHint> gQuicHints;
NSString* gUserAgent = nil;
NSString* gSslKeyLogFileName = nil;

}  // namespace

@implementation Cronet

+ (void)checkNotStarted {
  CHECK(gChromeNet == NULL) << "Cronet is already started.";
}

+ (void)setHttp2Enabled:(BOOL)http2Enabled {
  [self checkNotStarted];
  gHttp2Enabled = http2Enabled;
}

+ (void)setQuicEnabled:(BOOL)quicEnabled {
  [self checkNotStarted];
  gQuicEnabled = quicEnabled;
}

+ (void)addQuicHint:(NSString*)host port:(int)port altPort:(int)altPort {
  [self checkNotStarted];
  gQuicHints.push_back(new cronet::URLRequestContextConfig::QuicHint(
      base::SysNSStringToUTF8(host), port, altPort));
}

+ (void)setPartialUserAgent:(NSString*)userAgent {
  [self checkNotStarted];
  gUserAgent = userAgent;
}

+ (void)setSslKeyLogFileName:(NSString*)sslKeyLogFileName {
  [self checkNotStarted];
  gSslKeyLogFileName = sslKeyLogFileName;
}

+ (void)startInternal {
  cronet::CronetEnvironment::Initialize();
  std::string partialUserAgent = base::SysNSStringToUTF8(gUserAgent);
  gChromeNet.Get().reset(new cronet::CronetEnvironment(partialUserAgent));

  gChromeNet.Get()->set_http2_enabled(gHttp2Enabled);
  gChromeNet.Get()->set_quic_enabled(gQuicEnabled);
  gChromeNet.Get()->set_ssl_key_log_file_name(
      base::SysNSStringToUTF8(gSslKeyLogFileName));
  for (const auto* quicHint : gQuicHints) {
    gChromeNet.Get()->AddQuicHint(quicHint->host, quicHint->port,
                                  quicHint->alternate_port);
  }
  gChromeNet.Get()->Start();
}

+ (void)start {
  static dispatch_once_t onceToken;
  dispatch_once(&onceToken, ^{
    if (![NSThread isMainThread]) {
      dispatch_sync(dispatch_get_main_queue(), ^(void) {
        [self startInternal];
      });
    } else {
      [self startInternal];
    }
  });
}

+ (void)startNetLogToFile:(NSString*)fileName logBytes:(BOOL)logBytes {
  if (gChromeNet.Get().get() && [fileName length]) {
    gChromeNet.Get()->StartNetLog([fileName UTF8String], logBytes);
  }
}

+ (void)stopNetLog {
  if (gChromeNet.Get().get()) {
    gChromeNet.Get()->StopNetLog();
  }
}

+ (NSString*)getUserAgent {
  if (!gChromeNet.Get().get()) {
    return nil;
  }

  return [NSString stringWithCString:gChromeNet.Get()->user_agent().c_str()
                            encoding:[NSString defaultCStringEncoding]];
}

+ (cronet_engine*)getGlobalEngine {
  DCHECK(gChromeNet.Get().get());
  if (gChromeNet.Get().get()) {
    static cronet_engine engine;
    engine.obj = gChromeNet.Get().get();
    return &engine;
  }
  return nil;
}

// This is a non-public dummy method that prevents the linker from stripping out
// the otherwise non-referenced methods from 'cronet_bidirectional_stream.cc'.
+ (void)preventStrippingCronetBidirectionalStream {
  cronet_bidirectional_stream_create(NULL, 0, 0);
}

@end
