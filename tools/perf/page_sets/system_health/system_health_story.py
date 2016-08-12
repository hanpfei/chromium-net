# Copyright 2016 The Chromium Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

import logging

from page_sets.system_health import platforms

from telemetry.page import page


_DUMP_WAIT_TIME = 3


class _MetaSystemHealthStory(type):
  """Metaclass for SystemHealthStory."""

  @property
  def ABSTRACT_STORY(cls):
    """Class field marking whether the class is abstract.

    If true, the story will NOT be instantiated and added to a System Health
    story set. This field is NOT inherited by subclasses (that's why it's
    defined on the metaclass).
    """
    return cls.__dict__.get('__ABSTRACT_STORY__', False)

  @ABSTRACT_STORY.setter
  def ABSTRACT_STORY(cls, ABSTRACT_STORY):
    cls.__dict__['__ABSTRACT_STORY__'] = ABSTRACT_STORY


class SystemHealthStory(page.Page):
  """Abstract base class for System Health user stories."""
  __metaclass__ = _MetaSystemHealthStory

  # The full name of a single page story has the form CASE:GROUP:PAGE (e.g.
  # 'load:search:google').
  NAME = NotImplemented
  URL = NotImplemented
  ABSTRACT_STORY = True
  SUPPORTED_PLATFORMS = platforms.ALL_PLATFORMS

  def __init__(self, story_set, take_memory_measurement):
    case, group, _ = self.NAME.split(':')
    super(SystemHealthStory, self).__init__(
        page_set=story_set, name=self.NAME, url=self.URL,
        credentials_path='../data/credentials.json',
        grouping_keys={'case': case, 'group': group})
    self._take_memory_measurement = take_memory_measurement

  def _Measure(self, action_runner):
    if not self._take_memory_measurement:
      return
    # TODO(petrcermak): This method is essentially the same as
    # MemoryHealthPage._TakeMemoryMeasurement() in memory_health_story.py.
    # Consider sharing the common code.
    action_runner.Wait(_DUMP_WAIT_TIME)
    action_runner.ForceGarbageCollection()
    action_runner.Wait(_DUMP_WAIT_TIME)
    tracing_controller = action_runner.tab.browser.platform.tracing_controller
    if not tracing_controller.is_tracing_running:
      return  # Tracing is not running, e.g., when recording a WPR archive.
    if not action_runner.tab.browser.DumpMemory():
      logging.error('Unable to get a memory dump for %s.', self.name)

  def _Login(self, action_runner):
    pass

  def _DidLoadDocument(self, action_runner):
    pass

  def RunNavigateSteps(self, action_runner):
    self._Login(action_runner)
    super(SystemHealthStory, self).RunNavigateSteps(action_runner)

  def RunPageInteractions(self, action_runner):
    action_runner.tab.WaitForDocumentReadyStateToBeComplete()
    self._DidLoadDocument(action_runner)
    self._Measure(action_runner)
