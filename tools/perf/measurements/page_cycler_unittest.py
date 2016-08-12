# Copyright 2013 The Chromium Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

import sys
import unittest

from telemetry.internal.browser import browser_options
from telemetry.internal.results import page_test_results
from telemetry.internal import story_runner
from telemetry import page as page_module
from telemetry.testing import simple_mock

from measurements import page_cycler
from metrics import keychain_metric


# Allow testing protected members in the unit test.
# pylint: disable=protected-access

class MockMemoryMetric(object):
  """Used instead of simple_mock.MockObject so that the precise order and
  number of calls need not be specified."""

  def __init__(self):
    pass

  def Start(self, page, tab):
    pass

  def Stop(self, page, tab):
    pass

  def AddResults(self, tab, results):
    pass

  def AddSummaryResults(self, tab, results):
    pass


class FakePage(page_module.Page):
  """Used to mock loading a page."""

  def __init__(self, url):
    super(FakePage, self).__init__(url=url)

  @property
  def is_file(self):
    return self._url.startswith('file://')


class FakeTab(object):
  """Used to mock a browser tab."""

  def __init__(self):
    self.clear_cache_calls = 0
    self.navigated_urls = []

  def ClearCache(self, force=False):
    assert force
    self.clear_cache_calls += 1

  def EvaluateJavaScript(self, script):
    # If the page cycler invokes javascript to measure the number of keychain
    # accesses, return a valid JSON dictionary.
    keychain_histogram_name = keychain_metric.KeychainMetric.HISTOGRAM_NAME

    # Fake data for keychain metric.
    if keychain_histogram_name in script:
      return '{{ "{0}" : 0 }}'.format(keychain_histogram_name)

    return 1

  def Navigate(self, url):
    self.navigated_urls.append(url)

  def WaitForJavaScriptExpression(self, _, __):
    pass

  @property
  def browser(self):
    return FakeBrowser()


class FakeBrowser(object):
  _iteration = 0

  @property
  def cpu_stats(self):
    FakeBrowser._iteration += 1
    return {
        'Browser': {'CpuProcessTime': FakeBrowser._iteration,
                    'TotalTime': FakeBrowser._iteration * 2},
        'Renderer': {'CpuProcessTime': FakeBrowser._iteration,
                     'TotalTime': FakeBrowser._iteration * 3},
        'Gpu': {'CpuProcessTime': FakeBrowser._iteration,
                'TotalTime': FakeBrowser._iteration * 4}
    }

  @property
  def platform(self):
    return FakePlatform()

  @property
  def supports_cpu_metrics(self):
    return True

  @property
  def supports_memory_metrics(self):
    return True

  @property
  def supports_power_metrics(self):
    return True


class FakePlatform(object):

  def GetOSName(self):
    return 'fake'

  def CanMonitorPower(self):
    return False

  @property
  def http_server(self):
    class FakeHttpServer(object):

      def UrlOf(self, url_path):
        return 'http://fakeserver:99999/%s' % url_path
    return FakeHttpServer()


class PageCyclerUnitTest(unittest.TestCase):

  def SetUpCycler(self, page_repeat=1, pageset_repeat=10, cold_load_percent=50,
                  report_speed_index=False, setup_memory_module=False):
    cycler = page_cycler.PageCycler(
        page_repeat=page_repeat,
        pageset_repeat=pageset_repeat,
        cold_load_percent=cold_load_percent,
        report_speed_index=report_speed_index)
    options = browser_options.BrowserFinderOptions()
    options.browser_options.platform = FakePlatform()
    parser = options.CreateParser()
    story_runner.AddCommandLineArgs(parser)
    args = ['--page-repeat=%i' % page_repeat,
            '--pageset-repeat=%i' % pageset_repeat]
    parser.parse_args(args)
    story_runner.ProcessCommandLineArgs(parser, options)
    cycler.CustomizeBrowserOptions(options.browser_options)

    if setup_memory_module:
      # Mock out memory metrics; the real ones require a real browser.
      mock_memory_metric = MockMemoryMetric()

      mock_memory_module = simple_mock.MockObject()
      mock_memory_module.ExpectCall(
          'MemoryMetric').WithArgs(simple_mock.DONT_CARE).WillReturn(
              mock_memory_metric)

      real_memory_module = page_cycler.memory
      try:
        page_cycler.memory = mock_memory_module
        browser = FakeBrowser()
        cycler.WillStartBrowser(options.browser_options.platform)
        cycler.DidStartBrowser(browser)
      finally:
        page_cycler.memory = real_memory_module

    return cycler

  def testOptionsColdLoadNoArgs(self):
    cycler = self.SetUpCycler()

    self.assertEquals(cycler._cold_run_start_index, 5)

  def testOptionsColdLoadPagesetRepeat(self):
    cycler = self.SetUpCycler(pageset_repeat=20, page_repeat=2)

    self.assertEquals(cycler._cold_run_start_index, 20)

  def testOptionsColdLoadRequested(self):
    cycler = self.SetUpCycler(pageset_repeat=21, page_repeat=2,
                              cold_load_percent=40)

    self.assertEquals(cycler._cold_run_start_index, 26)

  def testCacheHandled(self):
    cycler = self.SetUpCycler(pageset_repeat=5,
                              cold_load_percent=50,
                              setup_memory_module=True)

    url_name = 'http://fakepage.com'
    page = FakePage(url_name)
    tab = FakeTab()

    for i in range(5):
      results = page_test_results.PageTestResults()
      results.WillRunPage(page)
      cycler.WillNavigateToPage(page, tab)
      self.assertEqual(max(0, i - 2), tab.clear_cache_calls,
                       'Iteration %d tab.clear_cache_calls %d' %
                       (i, tab.clear_cache_calls))
      cycler.ValidateAndMeasurePage(page, tab, results)
      results.DidRunPage(page)

      values = results.all_page_specific_values
      self.assertGreater(len(values), 2)

      self.assertEqual(values[0].page, page)
      chart_name = 'cold_times' if i == 0 or i > 2 else 'warm_times'
      self.assertEqual(values[0].name, '%s-page_load_time' % chart_name)
      self.assertEqual(values[0].units, 'ms')

      cycler.DidNavigateToPage(page, tab)

  def testColdWarm(self):
    cycler = self.SetUpCycler(pageset_repeat=3, setup_memory_module=True)
    pages = [FakePage('http://fakepage1.com'), FakePage('http://fakepage2.com')]
    tab = FakeTab()
    for i in range(3):
      for page in pages:
        results = page_test_results.PageTestResults()
        results.WillRunPage(page)
        cycler.WillNavigateToPage(page, tab)
        cycler.ValidateAndMeasurePage(page, tab, results)
        results.DidRunPage(page)

        values = results.all_page_specific_values
        self.assertGreater(len(values), 2)

        self.assertEqual(values[0].page, page)

        chart_name = 'cold_times' if i == 0 or i > 1 else 'warm_times'
        self.assertEqual(values[0].name, '%s-page_load_time' % chart_name)
        self.assertEqual(values[0].units, 'ms')

        cycler.DidNavigateToPage(page, tab)

  def testResults(self):
    cycler = self.SetUpCycler(setup_memory_module=True)

    pages = [FakePage('http://fakepage1.com'), FakePage('http://fakepage2.com')]
    tab = FakeTab()

    for i in range(2):
      for page in pages:
        results = page_test_results.PageTestResults()
        results.WillRunPage(page)
        cycler.WillNavigateToPage(page, tab)
        cycler.ValidateAndMeasurePage(page, tab, results)
        results.DidRunPage(page)

        values = results.all_page_specific_values

        # On Mac, there is an additional measurement: the number of keychain
        # accesses.
        value_count = 6
        if sys.platform == 'darwin':
          value_count += 1
        self.assertEqual(value_count, len(values))

        self.assertEqual(values[0].page, page)
        chart_name = 'cold_times' if i == 0 else 'warm_times'
        self.assertEqual(values[0].name, '%s-page_load_time' % chart_name)
        self.assertEqual(values[0].units, 'ms')
        self.assertEqual(values[1].name, '%s-time_to_onload' % chart_name)
        self.assertEqual(values[1].units, 'ms')

        expected_values = ['gpu', 'browser']
        for value, expected in zip(values[4:len(expected_values) + 1],
                                   expected_values):
          self.assertEqual(value.page, page)
          self.assertEqual(value.name,
                           'cpu_utilization.cpu_utilization_%s' % expected)
          self.assertEqual(value.units, '%')

        cycler.DidNavigateToPage(page, tab)

  def testLegacyPagesAvoidCrossRenderNavigation(self):
    # For legacy page cyclers with file URLs, verify that WillNavigateToPage
    # does an initial navigate to avoid paying for a cross-renderer navigation.
    cycler = self.SetUpCycler(setup_memory_module=True)
    pages = [FakePage('file://fakepage1.com'), FakePage('file://fakepage2.com')]
    tab = FakeTab()

    self.assertEqual([], tab.navigated_urls)
    for page in pages * 2:
      cycler.WillNavigateToPage(page, tab)
      self.assertEqual(
          ['http://fakeserver:99999/nonexistent.html'], tab.navigated_urls)
