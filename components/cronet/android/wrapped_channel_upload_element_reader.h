// Copyright 2014 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef COMPONENTS_CRONET_ANDROID_WRAPPED_CHANNEL_UPLOAD_ELEMENT_READER_H_
#define COMPONENTS_CRONET_ANDROID_WRAPPED_CHANNEL_UPLOAD_ELEMENT_READER_H_

#include <stdint.h>

#include "base/compiler_specific.h"
#include "base/macros.h"
#include "components/cronet/android/url_request_adapter.h"
#include "net/base/completion_callback.h"
#include "net/base/upload_element_reader.h"

namespace net {
class IOBuffer;
}  // namespace net

namespace cronet {

// An UploadElementReader implementation for
// java.nio.channels.ReadableByteChannel.
// |channel_| should outlive this class because this class does not take the
// ownership of the data.
class WrappedChannelElementReader : public net::UploadElementReader {
 public:
  WrappedChannelElementReader(
      scoped_refptr<URLRequestAdapter::URLRequestAdapterDelegate> delegate,
      uint64_t length);
  ~WrappedChannelElementReader() override;

  // UploadElementReader overrides:
  int Init(const net::CompletionCallback& callback) override;
  uint64_t GetContentLength() const override;
  uint64_t BytesRemaining() const override;
  bool IsInMemory() const override;
  int Read(net::IOBuffer* buf,
           int buf_length,
           const net::CompletionCallback& callback) override;

 private:
  const uint64_t length_;
  uint64_t offset_;
  scoped_refptr<URLRequestAdapter::URLRequestAdapterDelegate> delegate_;

  DISALLOW_COPY_AND_ASSIGN(WrappedChannelElementReader);
};

}  // namespace cronet

#endif  // COMPONENTS_CRONET_ANDROID_WRAPPED_CHANNEL_UPLOAD_ELEMENT_READER_H_
