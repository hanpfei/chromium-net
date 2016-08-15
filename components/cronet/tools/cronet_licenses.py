#!/usr/bin/python
# Copyright 2014 The Chromium Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

"""Generates the contents of an Cronet LICENSE file for the third-party code.

It makes use of src/tools/licenses.py and the README.chromium files on which
it depends. Based on android_webview/tools/webview_licenses.py.
"""

import optparse
import os
import shutil
import subprocess
import sys
import tempfile
import textwrap

REPOSITORY_ROOT = os.path.abspath(os.path.join(
    os.path.dirname(__file__), '..', '..', '..'))

sys.path.append(os.path.join(REPOSITORY_ROOT, 'tools'))
import licenses

third_party_dirs = [
  'base/third_party/libevent',
  'third_party/ashmem',
  'third_party/boringssl',
  'third_party/modp_b64',
  'third_party/zlib',
]


def _ReadFile(path):
  """Reads a file from disk.
  Args:
    path: The path of the file to read, relative to the root of the repository.
  Returns:
    The contents of the file as a string.
  """
  return open(os.path.join(REPOSITORY_ROOT, path), 'rb').read()


def GenerateLicense():
  """Generates the contents of an Cronet LICENSE file for the third-party code.
  Returns:
    The contents of the LICENSE file.
  """
  # Start with Chromium's LICENSE file
  content = [_ReadFile('LICENSE')]

  # Add necessary third_party.
  for directory in sorted(third_party_dirs):
    metadata = licenses.ParseDir(directory, REPOSITORY_ROOT,
                                 require_license_file=True)
    content.append('-' * 20)
    content.append(directory.split("/")[-1])
    content.append('-' * 20)
    license_file = metadata['License File']
    if license_file and license_file != licenses.NOT_SHIPPED:
      content.append(_ReadFile(license_file))

  return '\n'.join(content)


def FindThirdPartyDeps(gn_out_dir):
  # Generate gn project in temp directory and use it to find dependencies.
  # Current gn directory cannot ba used because gn doesn't allow recursive
  # invocations due to potential side effects.
  try:
    tmp_dir = tempfile.mkdtemp(dir = gn_out_dir)
    shutil.copy(gn_out_dir + "/args.gn", tmp_dir)
    subprocess.check_output(["gn", "gen", tmp_dir])
    gn_deps = subprocess.check_output(["gn", "desc", tmp_dir, \
                                    "//net", "deps", "--as=buildfile", "--all"])
  finally:
    if os.path.exists(tmp_dir):
      shutil.rmtree(tmp_dir)

  third_party_deps = []
  for build_dep in gn_deps.split():
    if ("third_party" in build_dep and build_dep.endswith("/BUILD.gn")):
      third_party_deps.append(build_dep.replace("/BUILD.gn", ""))
  third_party_deps.sort()
  return third_party_deps


def main():
  class FormatterWithNewLines(optparse.IndentedHelpFormatter):
    def format_description(self, description):
      paras = description.split('\n')
      formatted_paras = [textwrap.fill(para, self.width) for para in paras]
      return '\n'.join(formatted_paras) + '\n'

  parser = optparse.OptionParser(formatter=FormatterWithNewLines(),
                                 usage='%prog command [options]')
  parser.add_option('--gn', help='Use gn deps to find third party dependencies',
                    action='store_true')
  parser.description = (__doc__ +
                       '\nCommands:\n' \
                       '  license [filename]\n' \
                       '    Generate Cronet LICENSE to filename or stdout.\n')
  (_, args) = parser.parse_args()

  if _.gn:
    global third_party_dirs
    third_party_dirs = FindThirdPartyDeps(os.getcwd())

  if not args:
    parser.print_help()
    return 1

  if args[0] == 'license':
    if len(args) > 1:
      f = open(args[1], "w")
      try:
        f.write(GenerateLicense())
      finally:
        f.close()
    else:
      print GenerateLicense()
    return 0

  parser.print_help()
  return 1


if __name__ == '__main__':
  sys.exit(main())
