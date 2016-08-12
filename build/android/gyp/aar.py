#!/usr/bin/env python
#
# Copyright 2016 The Chromium Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

"""Processes an Android AAR file."""

import argparse
import os
import shutil
import sys
import zipfile

from util import build_utils

sys.path.append(os.path.abspath(os.path.join(os.path.dirname(__file__),
                                             os.pardir, os.pardir)))
import gn_helpers


def main():
  parser = argparse.ArgumentParser(description=__doc__)
  parser.add_argument('--input-file',
                      help='Path to the AAR file.',
                      required=True,
                      metavar='FILE')
  parser.add_argument('--extract',
                      help='Extract the files to output directory.',
                      action='store_true')
  parser.add_argument('--list',
                      help='List all the resource and jar files.',
                      action='store_true')
  parser.add_argument('--output-dir',
                      help='Output directory for the extracted files. Must '
                      'be set if --extract is set.',
                      metavar='DIR')

  args = parser.parse_args()
  if not args.extract and not args.list:
    parser.error('Either --extract or --list has to be specified.')

  aar_file = args.input_file
  output_dir = args.output_dir

  if args.extract:
    # Clear previously extracted versions of the AAR.
    shutil.rmtree(output_dir, True)
    build_utils.ExtractAll(aar_file, path=output_dir)

  if args.list:
    data = {}
    data['resources'] = []
    data['jars'] = []
    with zipfile.ZipFile(aar_file) as z:
      for name in z.namelist():
        if name.startswith('res/') and not name.endswith('/'):
          data['resources'].append(name)
        if name.endswith('.jar'):
          data['jars'].append(name)
    print gn_helpers.ToGNString(data)


if __name__ == '__main__':
  sys.exit(main())
