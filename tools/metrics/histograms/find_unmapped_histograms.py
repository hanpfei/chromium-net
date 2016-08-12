# Copyright 2013 The Chromium Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

"""Scans the Chromium source for histograms that are absent from histograms.xml.

This is a heuristic scan, so a clean run of this script does not guarantee that
all histograms in the Chromium source are properly mapped.  Notably, field
trials are entirely ignored by this script.

"""

import hashlib
import logging
import optparse
import os
import re
import subprocess
import sys

sys.path.append(os.path.join(os.path.dirname(__file__), '..', 'common'))
import path_util

import extract_histograms


ADJACENT_C_STRING_REGEX = re.compile(r"""
    ("      # Opening quotation mark
    [^"]*)  # Literal string contents
    "       # Closing quotation mark
    \s*     # Any number of spaces
    "       # Another opening quotation mark
    """, re.VERBOSE)
CONSTANT_REGEX = re.compile(r"""
    (\w*::)?  # Optional namespace
    k[A-Z]    # Match a constant identifier: 'k' followed by an uppercase letter
    \w*       # Match the rest of the constant identifier
    $         # Make sure there's only the identifier, nothing else
    """, re.VERBOSE)
HISTOGRAM_REGEX = re.compile(r"""
    UMA_HISTOGRAM  # Match the shared prefix for standard UMA histogram macros
    \w*            # Match the rest of the macro name, e.g. '_ENUMERATION'
    \(             # Match the opening parenthesis for the macro
    \s*            # Match any whitespace -- especially, any newlines
    ([^,)]*)       # Capture the first parameter to the macro
    [,)]           # Match the comma/paren that delineates the first parameter
    """, re.VERBOSE)


def RunGit(command):
  """Run a git subcommand, returning its output."""
  # On Windows, use shell=True to get PATH interpretation.
  command = ['git'] + command
  logging.info(' '.join(command))
  shell = (os.name == 'nt')
  proc = subprocess.Popen(command, shell=shell, stdout=subprocess.PIPE)
  out = proc.communicate()[0].strip()
  return out


class DirectoryNotFoundException(Exception):
  """Base class to distinguish locally defined exceptions from standard ones."""
  def __init__(self, msg):
    self.msg = msg

  def __str__(self):
    return self.msg


def collapseAdjacentCStrings(string):
  """Collapses any adjacent C strings into a single string.

  Useful to re-combine strings that were split across multiple lines to satisfy
  the 80-col restriction.

  Args:
    string: The string to recombine, e.g. '"Foo"\n    "bar"'

  Returns:
    The collapsed string, e.g. "Foobar" for an input of '"Foo"\n    "bar"'
  """
  while True:
    collapsed = ADJACENT_C_STRING_REGEX.sub(r'\1', string, count=1)
    if collapsed == string:
      return collapsed

    string = collapsed


def logNonLiteralHistogram(filename, histogram):
  """Logs a statement warning about a non-literal histogram name found in the
  Chromium source.

  Filters out known acceptable exceptions.

  Args:
    filename: The filename for the file containing the histogram, e.g.
              'chrome/browser/memory_details.cc'
    histogram: The expression that evaluates to the name of the histogram, e.g.
               '"FakeHistogram" + variant'

  Returns:
    None
  """
  # Ignore histogram macros, which typically contain backslashes so that they
  # can be formatted across lines.
  if '\\' in histogram:
    return

  # Ignore histogram names that have been pulled out into C++ constants.
  if CONSTANT_REGEX.match(histogram):
    return

  # TODO(isherman): This is still a little noisy... needs further filtering to
  # reduce the noise.
  logging.warning('%s contains non-literal histogram name <%s>', filename,
                  histogram)


def readChromiumHistograms():
  """Searches the Chromium source for all histogram names.

  Also prints warnings for any invocations of the UMA_HISTOGRAM_* macros with
  names that might vary during a single run of the app.

  Returns:
    A tuple of
      a set containing any found literal histogram names, and
      a set mapping histogram name to first filename:line where it was found
  """
  logging.info('Scanning Chromium source for histograms...')

  # Use git grep to find all invocations of the UMA_HISTOGRAM_* macros.
  # Examples:
  #   'path/to/foo.cc:420:  UMA_HISTOGRAM_COUNTS_100("FooGroup.FooName",'
  #   'path/to/bar.cc:632:  UMA_HISTOGRAM_ENUMERATION('
  locations = RunGit(['gs', 'UMA_HISTOGRAM']).split('\n')
  filenames = set([location.split(':')[0] for location in locations])

  histograms = set()
  location_map = dict()
  for filename in filenames:
    contents = ''
    with open(filename, 'r') as f:
      contents = f.read()

    for match in HISTOGRAM_REGEX.finditer(contents):
      histogram = collapseAdjacentCStrings(match.group(1))

      # Must begin and end with a quotation mark.
      if not histogram or histogram[0] != '"' or histogram[-1] != '"':
        logNonLiteralHistogram(filename, histogram)
        continue

      # Must not include any quotation marks other than at the beginning or end.
      histogram_stripped = histogram.strip('"')
      if '"' in histogram_stripped:
        logNonLiteralHistogram(filename, histogram)
        continue

      if histogram_stripped not in histograms:
        histograms.add(histogram_stripped)
        line_number = contents[:match.start()].count('\n') + 1
        location_map[histogram_stripped] = '%s:%d' % (filename, line_number)

  return histograms, location_map


def readXmlHistograms(histograms_file_location):
  """Parses all histogram names from histograms.xml.

  Returns:
    A set cotaining the parsed histogram names.
  """
  logging.info('Reading histograms from %s...' % histograms_file_location)
  histograms = extract_histograms.ExtractHistograms(histograms_file_location)
  return set(extract_histograms.ExtractNames(histograms))


def hashHistogramName(name):
  """Computes the hash of a histogram name.

  Args:
    name: The string to hash (a histogram name).

  Returns:
    Histogram hash as a string representing a hex number (with leading 0x).
  """
  return '0x' + hashlib.md5(name).hexdigest()[:16]


def main():
  # Find default paths.
  default_root = path_util.GetInputFile('/')
  default_histograms_path = path_util.GetInputFile(
      'tools/metrics/histograms/histograms.xml')
  default_extra_histograms_path = path_util.GetInputFile(
      'tools/histograms/histograms.xml')

  # Parse command line options
  parser = optparse.OptionParser()
  parser.add_option(
    '--root-directory', dest='root_directory', default=default_root,
    help='scan within DIRECTORY for histograms [optional, defaults to "%s"]' %
        default_root,
    metavar='DIRECTORY')
  parser.add_option(
    '--histograms-file', dest='histograms_file_location',
    default=default_histograms_path,
    help='read histogram definitions from FILE (relative to --root-directory) '
         '[optional, defaults to "%s"]' % default_histograms_path,
    metavar='FILE')
  parser.add_option(
    '--exrta_histograms-file', dest='extra_histograms_file_location',
    default=default_extra_histograms_path,
    help='read additional histogram definitions from FILE (relative to '
         '--root-directory) [optional, defaults to "%s"]' %
         default_extra_histograms_path,
    metavar='FILE')
  parser.add_option(
      '--verbose', action='store_true', dest='verbose', default=False,
      help=(
          'print file position information with histograms ' +
          '[optional, defaults to %default]'))

  (options, args) = parser.parse_args()
  if args:
    parser.print_help()
    sys.exit(1)

  logging.basicConfig(format='%(levelname)s: %(message)s', level=logging.INFO)

  try:
    os.chdir(options.root_directory)
  except EnvironmentError as e:
    logging.error("Could not change to root directory: %s", e)
    sys.exit(1)
  chromium_histograms, location_map = readChromiumHistograms()
  xml_histograms = readXmlHistograms(options.histograms_file_location)
  unmapped_histograms = chromium_histograms - xml_histograms

  if os.path.isfile(options.extra_histograms_file_location):
    xml_histograms2 = readXmlHistograms(options.extra_histograms_file_location)
    unmapped_histograms -= xml_histograms2
  else:
    logging.warning('No such file: %s', options.extra_histograms_file_location)

  if len(unmapped_histograms):
    logging.info('')
    logging.info('')
    logging.info('Histograms in Chromium but not in XML files:')
    logging.info('-------------------------------------------------')
    for histogram in sorted(unmapped_histograms):
      if options.verbose:
        logging.info('%s: %s - %s', location_map[histogram], histogram,
                     hashHistogramName(histogram))
      else:
        logging.info('  %s - %s', histogram, hashHistogramName(histogram))
  else:
    logging.info('Success!  No unmapped histograms found.')


if __name__ == '__main__':
  main()
