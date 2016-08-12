#!/usr/bin/python
# Copyright (c) 2016 The Chromium Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

"""Certificate chain with 2 intermediates and one end entity certificate. The
root certificate has a pathlen:1 restriction so this is an invalid chain."""

import common

# Self-signed root certificate (part of trust store).
root = common.create_self_signed_root_certificate('Root')
root.get_extensions().set_property('basicConstraints',
                                   'critical,CA:true,pathlen:1')

# Intermediate 1 (no pathlen restriction).
intermediate1 = common.create_intermediate_certificate('Intermediate1', root)

# Intermediate 2 (no pathlen restriction).
intermediate2 = common.create_intermediate_certificate('Intermediate2',
                                                       intermediate1)

# Target certificate.
target = common.create_end_entity_certificate('Target', intermediate2)

chain = [target, intermediate2, intermediate1]
trusted = [root]
time = common.DEFAULT_TIME
verify_result = False

common.write_test_file(__doc__, chain, trusted, time, verify_result)
