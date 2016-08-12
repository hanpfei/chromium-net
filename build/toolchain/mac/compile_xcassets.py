# Copyright 2016 The Chromium Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

import argparse
import os
import subprocess
import sys


def CompileXCAssets(output, platform, min_deployment_target, inputs):
  command = [
      'xcrun', 'actool', '--output-format=human-readable-text',
      '--compress-pngs', '--notices', '--warnings', '--errors',
      '--platform', platform, '--minimum-deployment-target',
      min_deployment_target,
  ]

  if platform == 'macosx':
    command.extend(['--target-device', 'mac'])
  else:
    command.extend(['--target-device', 'iphone', '--target-device', 'ipad'])

  # actool crashes if paths are relative, so convert input and output paths
  # to absolute paths.
  command.extend(['--compile', os.path.dirname(os.path.abspath(output))])
  command.extend(map(os.path.abspath, inputs))

  # Run actool and redirect stdout and stderr to the same pipe (as actool
  # is confused about what should go to stderr/stdout).
  process = subprocess.Popen(
      command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
  stdout, _ = process.communicate()

  if process.returncode:
    sys.stderr.write(stdout)
    sys.exit(process.returncode)

  # In case of success, the output looks like the following:
  #   /* com.apple.actool.compilation-results */
  #   /Full/Path/To/Bundle.app/Assets.car
  #
  # Ignore any lines in the output matching those (last line is an empty line)
  # and consider that the build failed if the output contains any other lines.
  for line in stdout.splitlines():
    if not line:
      continue
    if line == '/* com.apple.actool.compilation-results */':
      continue
    if line == os.path.abspath(output):
      continue
    sys.stderr.write(stdout)
    sys.exit(1)


def Main():
  parser = argparse.ArgumentParser(
      description='compile assets catalog for a bundle')
  parser.add_argument(
      '--platform', '-p', required=True,
      choices=('macosx', 'iphoneos', 'iphonesimulator'),
      help='target platform for the compiled assets catalog')
  parser.add_argument(
      '--minimum-deployment-target', '-t', required=True,
      help='minimum deployment target for the compiled assets catalog')
  parser.add_argument(
      '--output', '-o', required=True,
      help='path to the compiled assets catalog')
  parser.add_argument(
      'inputs', nargs='+',
      help='path to input assets catalog sources')
  args = parser.parse_args()

  if os.path.basename(args.output) != 'Assets.car':
    sys.stderr.write(
        'output should be path to compiled asset catalog, not '
        'to the containing bundle: %s\n' % (args.output,))
    sys.exit(1)

  CompileXCAssets(
      args.output,
      args.platform,
      args.minimum_deployment_target,
      args.inputs)


if __name__ == '__main__':
  sys.exit(Main())
