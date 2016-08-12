# Copyright 2016 The Chromium Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

DESKTOP = 'desktop'
MOBILE = 'mobile'

ALL_PLATFORMS = frozenset({DESKTOP, MOBILE})
DESKTOP_ONLY = frozenset({DESKTOP})
MOBILE_ONLY = frozenset({MOBILE})
# This is used for disabling a story on all platforms.
NO_PLATFORMS = frozenset()
