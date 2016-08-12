# Copyright 2016 The Chromium Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

{
  'variables': {
    # When including this gypi, the following variables must be set:
    #   in_files:
    #     An array of json files from which to generate the feature provider.
    #   out_dir:
    #     The directory to put the generated code in under gen/.
    #   out_base_filename:
    #     The base filename to use for the generated feature provider; .h and
    #     .cc will be appended.
    #   feature_class:
    #     The name of the class to use for features, e.g. APIFeature.
    #   provider_class:
    #     The name of the class to use for the feature provider, e.g.
    #     APIFeatureProvider
    'compiler_dir': '<(DEPTH)/tools/json_schema_compiler',
    'compiler_script': '<(compiler_dir)/feature_compiler.py',
  },
  'actions': [
    {
      # GN version: json_features.gni
      'action_name': 'genfeatures',
      'msvs_external_rule': 1,
      'extension': 'json',
      'inputs': [
        '<(compiler_dir)/code.py',
        '<(compiler_script)',
      ],
      'outputs': [
        '<(SHARED_INTERMEDIATE_DIR)/<(out_dir)/<(out_base_filename).cc',
        '<(SHARED_INTERMEDIATE_DIR)/<(out_dir)/<(out_base_filename).h',
      ],
      'action': [
        'python',
        '<(compiler_script)',
        '<(DEPTH)',
        '<(feature_class)',
        '<(provider_class)',
        '<(SHARED_INTERMEDIATE_DIR)/<(out_dir)',
        '<(out_base_filename)',
        '<@(in_files)',
      ],
      'message': 'Generating C++ code for json feature files',
      'process_outputs_as_sources': 1,
    },
  ],
  'include_dirs': [
    '<(SHARED_INTERMEDIATE_DIR)',
    '<(DEPTH)',
  ],
  'direct_dependent_settings': {
    'include_dirs': [
      '<(SHARED_INTERMEDIATE_DIR)',
    ]
  },
  # This target exports a hard dependency because it generates header
  # files.
  'hard_dependency': 1,
}
