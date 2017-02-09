// Copyright 2014 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "network_change_notifier_util.h"

#include "base/android/jni_android.h"
#include "base/macros.h"
#include "base/message_loop/message_loop.h"
#include "base/run_loop.h"
#include "jni/NetworkChangeNotifierUtil_jni.h"
#include "net/base/network_change_notifier.h"

using base::android::JavaParamRef;

namespace cronet {

namespace {

// An IPAddressObserver to test whether Cronet can receive notification
// when network changes.
class TestIPAddressObserver
    : public net::NetworkChangeNotifier::IPAddressObserver {
 public:
  TestIPAddressObserver() : ip_address_changed_(false) {
  }

  ~TestIPAddressObserver() override {}

  // net::NetworkChangeNotifier::IPAddressObserver implementation.
  void OnIPAddressChanged() override {
    ip_address_changed_ = true;
  }

  bool ip_address_changed() const {
    return ip_address_changed_;
  }

 private:
  bool ip_address_changed_;

  DISALLOW_COPY_AND_ASSIGN(TestIPAddressObserver);
};

}  // namespace

// Adds a TestIPAddressObserver to the list of IPAddressObservers, and returns
// a boolean indicating whether the TestIPAddressObserver has received
// notification when network changes.
static jboolean IsTestIPAddressObserverCalled(
    JNIEnv* jenv,
    const JavaParamRef<jclass>& jcaller) {
  // This method is called on a Java thread with no MessageLoop, but we need
  // one for the NetworkChangeNotifier to call the observer on.
  base::MessageLoop loop;
  TestIPAddressObserver test_observer;
  net::NetworkChangeNotifier::AddIPAddressObserver(&test_observer);
  net::NetworkChangeNotifier::NotifyObserversOfIPAddressChangeForTests();

  base::RunLoop().RunUntilIdle();
  net::NetworkChangeNotifier::RemoveIPAddressObserver(&test_observer);

  return test_observer.ip_address_changed();
}

bool RegisterNetworkChangeNotifierUtil(JNIEnv* jenv) {
  return RegisterNativesImpl(jenv);
}

}  // namespace cronet
