// Copyright 2015 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef COMPONENTS_CRONET_ANDROID_CRONET_IN_MEMORY_PREF_STORE_H_
#define COMPONENTS_CRONET_ANDROID_CRONET_IN_MEMORY_PREF_STORE_H_

#include <stdint.h>

#include <memory>
#include <string>

#include "base/compiler_specific.h"
#include "base/macros.h"
#include "base/observer_list.h"
#include "components/prefs/persistent_pref_store.h"
#include "components/prefs/pref_value_map.h"

namespace base {
class Value;
}

// A light-weight prefstore implementation that keeps preferences
// in a memory backed store. This is not a persistent prefstore.
// TODO(bengr): Move to components/prefs or some other shared location.
class CronetInMemoryPrefStore : public PersistentPrefStore {
 public:
  CronetInMemoryPrefStore();

  // PrefStore overrides:
  bool GetValue(const std::string& key,
                const base::Value** result) const override;
  void AddObserver(PrefStore::Observer* observer) override;
  void RemoveObserver(PrefStore::Observer* observer) override;
  bool HasObservers() const override;
  bool IsInitializationComplete() const override;

  // PersistentPrefStore overrides:
  bool GetMutableValue(const std::string& key, base::Value** result) override;
  void ReportValueChanged(const std::string& key, uint32_t flags) override;
  void SetValue(const std::string& key,
                std::unique_ptr<base::Value> value,
                uint32_t flags) override;
  void SetValueSilently(const std::string& key,
                        std::unique_ptr<base::Value> value,
                        uint32_t flags) override;
  void RemoveValue(const std::string& key, uint32_t flags) override;
  bool ReadOnly() const override;
  PrefReadError GetReadError() const override;
  PersistentPrefStore::PrefReadError ReadPrefs() override;
  void ReadPrefsAsync(ReadErrorDelegate* error_delegate) override;
  void CommitPendingWrite() override {}
  void SchedulePendingLossyWrites() override {}
  void ClearMutableValues() override {}

 private:
  ~CronetInMemoryPrefStore() override;

  // Stores the preference values.
  PrefValueMap prefs_;

  base::ObserverList<PrefStore::Observer, true> observers_;

  DISALLOW_COPY_AND_ASSIGN(CronetInMemoryPrefStore);
};

#endif  // COMPONENTS_CRONET_ANDROID_CRONET_IN_MEMORY_PREF_STORE_H_
