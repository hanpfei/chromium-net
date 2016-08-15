// Copyright 2016 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "components/cronet/android/io_buffer_with_byte_buffer.h"

#include "base/logging.h"

namespace cronet {

IOBufferWithByteBuffer::IOBufferWithByteBuffer(
    JNIEnv* env,
    const base::android::JavaParamRef<jobject>& jbyte_buffer,
    void* byte_buffer_data,
    jint position,
    jint limit)
    : net::WrappedIOBuffer(static_cast<char*>(byte_buffer_data) + position),
      byte_buffer_(env, jbyte_buffer),
      initial_position_(position),
      initial_limit_(limit) {
  DCHECK(byte_buffer_data);
  DCHECK_EQ(env->GetDirectBufferAddress(jbyte_buffer), byte_buffer_data);
}

IOBufferWithByteBuffer::~IOBufferWithByteBuffer() {}

}  // namespace cronet
