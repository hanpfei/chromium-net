#!/usr/bin/env python
#
# Copyright 2015 The Chromium Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

import optparse
import os
import sys

REPOSITORY_ROOT = os.path.abspath(os.path.join(
    os.path.dirname(__file__), '..', '..', '..'))

sys.path.append(os.path.join(REPOSITORY_ROOT, 'build/android/gyp/util'))
sys.path.append(os.path.join(REPOSITORY_ROOT, 'net/tools/net_docs'))
import build_utils
import net_docs
from markdown.postprocessors import Postprocessor
from markdown.extensions import Extension


class CronetPostprocessor(Postprocessor):
  def run(self, text):
    return text.replace('@Override', '&commat;Override')


class CronetExtension(Extension):
  def extendMarkdown(self, md, md_globals):
    md.postprocessors.add('CronetPostprocessor',
                          CronetPostprocessor(md), '_end')


def GenerateJavadoc(options):
  output_dir = os.path.abspath(os.path.join(options.output_dir, 'javadoc'))
  working_dir = os.path.join(options.input_dir, 'android/api')
  overview_file = os.path.abspath(options.overview_file)
  lib_java_dir = os.path.abspath(options.lib_java_dir)

  build_utils.DeleteDirectory(output_dir)
  build_utils.MakeDirectory(output_dir)
  javadoc_cmd = ['ant', '-Dsource.dir=src', '-Ddoc.dir=' + output_dir,
             '-Dlib.java.dir=' + lib_java_dir, '-Doverview=' + overview_file,
             'doc']
  stdout = build_utils.CheckOutput(javadoc_cmd, cwd=working_dir)
  if " error: " in stdout or "warning" in stdout or "javadoc: error " in stdout:
    build_utils.DeleteDirectory(output_dir)
    raise build_utils.CalledProcessError(working_dir, javadoc_cmd, stdout)


def main():
  parser = optparse.OptionParser()
  build_utils.AddDepfileOption(parser)
  parser.add_option('--output-dir', help='Directory to put javadoc')
  parser.add_option('--input-dir', help='Root of cronet source')
  parser.add_option('--overview-file', help='Path of the overview page')
  parser.add_option('--readme-file', help='Path of the README.md')
  parser.add_option('--lib-java-dir', help='Directory containing java libs')

  options, _ = parser.parse_args()

  net_docs.ProcessDocs([options.readme_file], options.input_dir,
                       options.output_dir, extensions=[CronetExtension()])

  GenerateJavadoc(options)

  if options.depfile:
    input_paths = []
    for root, _, filenames in os.walk(options.input_dir):
      input_paths.extend(os.path.join(root, f) for f in filenames)
    build_utils.WriteDepfile(options.depfile,
                             input_paths + build_utils.GetPythonDependencies())

if __name__ == '__main__':
  sys.exit(main())
