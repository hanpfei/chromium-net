// Copyright 2015 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "components/cronet/android/cronet_in_memory_pref_store.h"

#include <utility>

#include "base/logging.h"
#include "base/values.h"

CronetInMemoryPrefStore::CronetInMemoryPrefStore() {}

CronetInMemoryPrefStore::~CronetInMemoryPrefStore() {}

bool CronetInMemoryPrefStore::GetValue(const std::string& key,
                                       const base::Value** value) const {
  return prefs_.GetValue(key, value);
}

bool CronetInMemoryPrefStore::GetMutableValue(const std::string& key,
                                              base::Value** value) {
  return prefs_.GetValue(key, value);
}

void CronetInMemoryPrefStore::AddObserver(PrefStore::Observer* observer) {
  observers_.AddObserver(observer);
}

void CronetInMemoryPrefStore::RemoveObserver(PrefStore::Observer* observer) {
  observers_.RemoveObserver(observer);
}

bool CronetInMemoryPrefStore::HasObservers() const {
  return observers_.might_have_observers();
}

bool CronetInMemoryPrefStore::IsInitializationComplete() const {
  return true;
}

void CronetInMemoryPrefStore::SetValue(const std::string& key,
                                       std::unique_ptr<base::Value> value,
                                       uint32_t flags) {
  DCHECK(value);
  if (prefs_.SetValue(key, std::move(value)))
    ReportValueChanged(key, flags);
}

void CronetInMemoryPrefStore::SetValueSilently(
    const std::string& key,
    std::unique_ptr<base::Value> value,
    uint32_t flags) {
  prefs_.SetValue(key, std::move(value));
}

void CronetInMemoryPrefStore::RemoveValue(const std::string& key,
                                          uint32_t flags) {
  if (prefs_.RemoveValue(key))
    ReportValueChanged(key, flags);
}

bool CronetInMemoryPrefStore::ReadOnly() const {
  return false;
}

PersistentPrefStore::PrefReadError
CronetInMemoryPrefStore::GetReadError() const {
  return PersistentPrefStore::PREF_READ_ERROR_NONE;
}

PersistentPrefStore::PrefReadError CronetInMemoryPrefStore::ReadPrefs() {
  return PersistentPrefStore::PREF_READ_ERROR_NONE;
}

void CronetInMemoryPrefStore::ReadPrefsAsync(
    ReadErrorDelegate* error_delegate_raw) {
}

void CronetInMemoryPrefStore::ReportValueChanged(const std::string& key,
                                                 uint32_t flags) {
  FOR_EACH_OBSERVER(Observer, observers_, OnPrefValueChanged(key));
}

