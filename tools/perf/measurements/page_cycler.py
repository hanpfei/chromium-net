# Copyright 2012 The Chromium Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

"""The page cycler measurement.

This measurement registers a window load handler in which is forces a layout and
then records the value of performance.now(). This call to now() measures the
time from navigationStart (immediately after the previous page's beforeunload
event) until after the layout in the page's load event. In addition, two garbage
collections are performed in between the page loads (in the beforeunload event).
This extra garbage collection time is not included in the measurement times.
Finally, various memory and IO statistics are gathered at the very end of
cycling all pages.
"""

import collections
import os

from telemetry.core import util
from telemetry.page import legacy_page_test
from telemetry.value import scalar

from metrics import cpu
from metrics import keychain_metric
from metrics import memory
from metrics import power
from metrics import speedindex


class PageCycler(legacy_page_test.LegacyPageTest):

  def __init__(self, page_repeat, pageset_repeat, cold_load_percent=50,
               report_speed_index=False, clear_cache_before_each_run=False):
    super(PageCycler, self).__init__(
        clear_cache_before_each_run=clear_cache_before_each_run)

    with open(os.path.join(os.path.dirname(__file__),
                           'page_cycler.js'), 'r') as f:
      self._page_cycler_js = f.read()

    self._report_speed_index = report_speed_index
    self._speedindex_metric = speedindex.SpeedIndexMetric()
    self._memory_metric = None
    self._power_metric = None
    self._cpu_metric = None
    self._has_loaded_page = collections.defaultdict(int)
    self._initial_renderer_url = None  # to avoid cross-renderer navigation

    cold_runs_percent_set = (cold_load_percent != None)
    # Handle requests for cold cache runs
    if (cold_runs_percent_set and
        (cold_load_percent < 0 or cold_load_percent > 100)):
      raise Exception('cold-load-percent must be in the range [0-100]')

    # Make sure _cold_run_start_index is an integer multiple of page_repeat.
    # Without this, --pageset_shuffle + --page_repeat could lead to
    # assertion failures on _started_warm in WillNavigateToPage.
    if cold_runs_percent_set:
      number_warm_pageset_runs = int(
          (int(pageset_repeat) - 1) * (100 - cold_load_percent) / 100)
      number_warm_runs = number_warm_pageset_runs * page_repeat
      self._cold_run_start_index = number_warm_runs + page_repeat
    else:
      self._cold_run_start_index = pageset_repeat * page_repeat

  def WillStartBrowser(self, platform):
    """Initialize metrics once right before the browser has been launched."""
    self._power_metric = power.PowerMetric(platform)

  def DidStartBrowser(self, browser):
    """Initialize metrics once right after the browser has been launched."""
    self._memory_metric = memory.MemoryMetric(browser)
    self._cpu_metric = cpu.CpuMetric(browser)

  def WillNavigateToPage(self, page, tab):
    if page.is_file:
      # For legacy page cyclers which use the filesystem, do an initial
      # navigate to avoid paying for a cross-renderer navigation.
      initial_url = tab.browser.platform.http_server.UrlOf('nonexistent.html')
      if self._initial_renderer_url != initial_url:
        self._initial_renderer_url = initial_url
        tab.Navigate(self._initial_renderer_url)

    page.script_to_evaluate_on_commit = self._page_cycler_js
    if self.ShouldRunCold(page.url):
      tab.ClearCache(force=True)
    if self._report_speed_index:
      self._speedindex_metric.Start(page, tab)
    self._cpu_metric.Start(page, tab)
    self._power_metric.Start(page, tab)

  def DidNavigateToPage(self, page, tab):
    self._memory_metric.Start(page, tab)

  def CustomizeBrowserOptions(self, options):
    memory.MemoryMetric.CustomizeBrowserOptions(options)
    power.PowerMetric.CustomizeBrowserOptions(options)
    options.AppendExtraBrowserArgs('--js-flags=--expose_gc')

    if self._report_speed_index:
      self._speedindex_metric.CustomizeBrowserOptions(options)

    keychain_metric.KeychainMetric.CustomizeBrowserOptions(options)

  def ValidateAndMeasurePage(self, page, tab, results):
    tab.WaitForJavaScriptExpression('__pc_load_time', 60)

    chart_name_prefix = ('cold_' if self.IsRunCold(page.url) else
                         'warm_')

    results.AddValue(scalar.ScalarValue(
        results.current_page, '%stimes-page_load_time' % chart_name_prefix,
        'ms', tab.EvaluateJavaScript('__pc_load_time'),
        description='Average page load time. Measured from '
                    'performance.timing.navigationStart until the completion '
                    'time of a layout after the window.load event. Cold times '
                    'are the times when the page is loaded cold, i.e. without '
                    'loading it before, and warm times are times when the '
                    'page is loaded after being loaded previously.'))
    results.AddValue(scalar.ScalarValue(
        results.current_page, '%stimes-time_to_onload' % chart_name_prefix,
        'ms', tab.EvaluateJavaScript('performance.timing.loadEventStart'
                                     '- performance.timing.navigationStart'),
        description='Time to onload. This is temporary metric to check that '
                    'PCv1 and PCv2 emit similar results'))

    # TODO(kouhei): Remove below. crbug.com/616342
    results.AddValue(scalar.ScalarValue(
        results.current_page, '%stimes.page_load_time' % chart_name_prefix,
        'ms', tab.EvaluateJavaScript('__pc_load_time'),
        description='Average page load time. Measured from '
                    'performance.timing.navigationStart until the completion '
                    'time of a layout after the window.load event. Cold times '
                    'are the times when the page is loaded cold, i.e. without '
                    'loading it before, and warm times are times when the '
                    'page is loaded after being loaded previously.'))
    results.AddValue(scalar.ScalarValue(
        results.current_page, '%stimes.time_to_onload' % chart_name_prefix,
        'ms', tab.EvaluateJavaScript('performance.timing.loadEventStart'
                                     '- performance.timing.navigationStart'),
        description='Time to onload. This is temporary metric to check that '
                    'PCv1 and PCv2 emit similar results'))

    self._has_loaded_page[page.url] += 1

    self._power_metric.Stop(page, tab)
    self._memory_metric.Stop(page, tab)
    self._memory_metric.AddResults(tab, results)
    self._power_metric.AddResults(tab, results)

    self._cpu_metric.Stop(page, tab)
    self._cpu_metric.AddResults(tab, results)

    if self._report_speed_index:
      def SpeedIndexIsFinished():
        return self._speedindex_metric.IsFinished(tab)
      util.WaitFor(SpeedIndexIsFinished, 60)
      self._speedindex_metric.Stop(page, tab)
      self._speedindex_metric.AddResults(
          tab, results, chart_name=chart_name_prefix + 'speed_index')
    keychain_metric.KeychainMetric().AddResults(tab, results)

  def IsRunCold(self, url):
    return self.ShouldRunCold(url) or self._has_loaded_page[url] == 0

  def ShouldRunCold(self, url):
    # We do the warm runs first for two reasons.  The first is so we can
    # preserve any initial profile cache for as long as possible.
    # The second is that, if we did cold runs first, we'd have a transition
    # page set during which we wanted the run for each URL to both
    # contribute to the cold data and warm the catch for the following
    # warm run, and clearing the cache before the load of the following
    # URL would eliminate the intended warmup for the previous URL.
    return self._has_loaded_page[url] >= self._cold_run_start_index

  def DidRunPage(self, platform):
    del platform  # unused
    self._power_metric.Close()
