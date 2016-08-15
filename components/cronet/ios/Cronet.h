// Copyright 2016 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#import <Foundation/Foundation.h>

#include "cronet_c_for_grpc.h"

// Interface for installing Cronet.
CRONET_EXPORT
@interface Cronet : NSObject

// Sets whether HTTP/2 should be supported by CronetEngine. This method only has
// any effect before |start| is called.
+ (void)setHttp2Enabled:(BOOL)http2Enabled;

// Sets whether QUIC should be supported by CronetEngine. This method only has
// any effect before |start| is called.
+ (void)setQuicEnabled:(BOOL)quicEnabled;

// Adds hint that host supports QUIC on altPort. This method only has any effect
// before |start| is called.
+ (void)addQuicHint:(NSString*)host port:(int)port altPort:(int)altPort;

// |userAgent| is expected to be of the form Product/Version.
// Example: Foo/3.0.0.0
//
// This method only has any effect before |start| is called.
+ (void)setPartialUserAgent:(NSString*)userAgent;

// Sets SSLKEYLogFileName to export SSL key for Wireshark decryption of packet
// captures. This method only has any effect before |start| is called.
+ (void)setSslKeyLogFileName:(NSString*)sslKeyLogFileName;

// Starts CronetEngine. It is recommended to call this method on the application
// main thread. If the method is called on any thread other than the main one,
// the method will internally try to execute synchronously using the main GCD
// queue. Please make sure that the main thread is not blocked by a job
// that calls this method; otherwise, a deadlock can occur.
+ (void)start;

// Starts net-internals logging to a file named |fileName| in the application
// temporary directory. |fileName| must not be empty. Log level is determined
// by |logBytes| - if YES then LOG_ALL otherwise LOG_ALL_BUT_BYTES. If the file
// exists it is truncated before starting. If actively logging the call is
// ignored.
+ (void)startNetLogToFile:(NSString*)fileName logBytes:(BOOL)logBytes;

// Stop net-internals logging and flush file to disk. If a logging session is
// not in progress this call is ignored.
+ (void)stopNetLog;

// Returns the full user-agent that the stack uses.
// This is the exact string servers will see.
+ (NSString*)getUserAgent;

// Get a pointer to global instance of cronet_engine for GRPC C API.
+ (cronet_engine*)getGlobalEngine;

@end
