# Copyright 2015 The Chromium Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

"""Top-level presubmit script for src/components/cronet.

See http://dev.chromium.org/developers/how-tos/depottools/presubmit-scripts
for more details about the presubmit API built into depot_tools.
"""

def _PyLintChecks(input_api, output_api):
  pylint_checks = input_api.canned_checks.GetPylint(input_api, output_api,
          extra_paths_list=_GetPathsToPrepend(input_api), pylintrc='pylintrc')
  return input_api.RunTests(pylint_checks)

def _GetPathsToPrepend(input_api):
  current_dir = input_api.PresubmitLocalPath()
  chromium_src_dir = input_api.os_path.join(current_dir, '..', '..')
  return [
    input_api.os_path.join(current_dir, 'tools'),
    input_api.os_path.join(current_dir, 'android', 'test', 'javaperftests'),
    input_api.os_path.join(chromium_src_dir, 'tools', 'perf'),
    input_api.os_path.join(chromium_src_dir, 'build', 'android'),
    input_api.os_path.join(chromium_src_dir, 'build', 'android', 'gyp', 'util'),
    input_api.os_path.join(chromium_src_dir, 'net', 'tools', 'net_docs'),
    input_api.os_path.join(chromium_src_dir, 'tools'),
    input_api.os_path.join(chromium_src_dir, 'third_party'),
    input_api.os_path.join(chromium_src_dir,
        'third_party', 'catapult', 'telemetry'),
    input_api.os_path.join(chromium_src_dir,
        'third_party', 'catapult', 'devil'),
  ]

def CheckChangeOnUpload(input_api, output_api):
  results = []
  results.extend(_PyLintChecks(input_api, output_api))
  results.extend(
      input_api.canned_checks.CheckPatchFormatted(input_api, output_api))
  return results
