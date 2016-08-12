#!/usr/bin/python
# Copyright 2016 The Chromium Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

"""Inspection of the prefetch predictor database.

On Android, the database can be extracted using:
adb pull \
  '/data/user/0/$package_name/app_chrome/Default/Network Action Predictor'
  predictor_db
"""

import argparse
import operator
import sqlite3


class ResourceType(object):
  """Partially mirrors content::ResourceType."""
  STYLESHEET = 2
  SCRIPT = 3
  FONT_RESOURCE = 5


class Entry(object):
  """Represents an entry in the predictor database."""
  HEADER = (
      'score,main_page_url,resource_type,number_of_hits,number_of_misses,'
      'consecutive_misses,average_position,confidence,resource_url')

  def __init__(
      self, main_page_url, resource_url, resource_type, number_of_hits,
      number_of_misses, consecutive_misses, average_position, priority):
    self.main_page_url = main_page_url
    self.resource_url = resource_url
    self.resource_type = resource_type
    self.number_of_hits = int(number_of_hits)
    self.number_of_misses = int(number_of_misses)
    self.consecutive_misses = int(consecutive_misses)
    self.average_position = int(average_position)
    self.priority = int(priority)
    self.confidence = float(number_of_hits) / (
        number_of_hits + number_of_misses)
    self.score = self._Score()

  def _Score(self):
    """Mirrors ResourcePrefetchPredictorTables::ResourceRow::UpdateScore."""
    multiplier = 1
    if self.resource_type in (ResourceType.STYLESHEET, ResourceType.SCRIPT,
                              ResourceType.FONT_RESOURCE):
      multiplier = 2
    return multiplier * 100 - self.average_position

  @classmethod
  def FromRow(cls, row):
    """Builds an entry from a database row."""
    return Entry(*row)

  def __str__(self):
    return '%f,%s,%d,%d,%d,%d,%d,%f,%d\t%s' % (
        self.score, self.main_page_url, self.resource_type,
        self.number_of_hits, self.number_of_misses, self.consecutive_misses,
        self.average_position, self.confidence, self.priority,
        self.resource_url)


def FilterAndSort(entries, domain):
  """Filters and sorts the entries to be prefetched for a given domain.

  Uses the default thresholds defined in resource_prefetch_common.cc.
  """
  result = filter(
      lambda x: ((domain is None or x.main_page_url == domain)
                 and x.confidence > .7
                 and x.number_of_hits >= 2), entries)
  return sorted(result, key=operator.attrgetter('score'), reverse=True)


def DatabaseStats(filename, domain):
  connection = sqlite3.connect(filename)
  c = connection.cursor()
  query = ('SELECT main_page_url, resource_url, resource_type, number_of_hits, '
           'number_of_misses, consecutive_misses, average_position, priority '
           'FROM resource_prefetch_predictor_host')
  entries = [Entry.FromRow(row) for row in c.execute(query)]
  prefetched = FilterAndSort(entries, domain)
  print Entry.HEADER
  for x in prefetched:
    print x


def main():
  parser = argparse.ArgumentParser()
  parser.add_argument('-f', dest='database_filename', required=True,
                      help='Path to the database')
  parser.add_argument('-d', dest='domain', default=None, help='Domain')
  args = parser.parse_args()
  DatabaseStats(args.database_filename, args.domain)


if __name__ == '__main__':
  main()
