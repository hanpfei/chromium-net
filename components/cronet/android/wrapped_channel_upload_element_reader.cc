// Copyright 2014 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "components/cronet/android/wrapped_channel_upload_element_reader.h"

#include "base/android/jni_android.h"
#include "base/logging.h"
#include "net/base/io_buffer.h"
#include "net/base/net_errors.h"

namespace cronet {

WrappedChannelElementReader::WrappedChannelElementReader(
    scoped_refptr<URLRequestAdapter::URLRequestAdapterDelegate> delegate,
    uint64_t length)
    : length_(length), offset_(0), delegate_(delegate) {}

WrappedChannelElementReader::~WrappedChannelElementReader() {
}

int WrappedChannelElementReader::Init(const net::CompletionCallback& callback) {
  if (offset_ != 0)
    return net::ERR_UPLOAD_STREAM_REWIND_NOT_SUPPORTED;
  return net::OK;
}

uint64_t WrappedChannelElementReader::GetContentLength() const {
  return length_;
}

uint64_t WrappedChannelElementReader::BytesRemaining() const {
  return length_ - offset_;
}

bool WrappedChannelElementReader::IsInMemory() const {
  return false;
}

int WrappedChannelElementReader::Read(net::IOBuffer* buf,
                                      int buf_length,
                                      const net::CompletionCallback& callback) {
  DCHECK(!callback.is_null());
  DCHECK(delegate_.get());
  // TODO(mef): Post the read to file thread.
  int bytes_read = delegate_->ReadFromUploadChannel(buf, buf_length);
  if (bytes_read < 0)
    return net::ERR_FAILED;
  offset_ += bytes_read;
  return bytes_read;
}

}  // namespace cronet

