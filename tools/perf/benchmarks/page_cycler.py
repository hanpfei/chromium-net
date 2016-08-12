# Copyright 2013 The Chromium Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

from core import perf_benchmark

from measurements import page_cycler
import page_sets


class _PageCycler(perf_benchmark.PerfBenchmark):
  options = {'pageset_repeat': 6}
  cold_load_percent = 50  # % of page visits for which a cold load is forced

  @classmethod
  def Name(cls):
    return 'page_cycler'

  @classmethod
  def AddBenchmarkCommandLineArgs(cls, parser):
    parser.add_option('--report-speed-index',
                      action='store_true',
                      help='Enable the speed index metric.')

  @classmethod
  def ValueCanBeAddedPredicate(cls, _, is_first_result):
    return cls.cold_load_percent > 0 or not is_first_result

  def CreatePageTest(self, options):
    return page_cycler.PageCycler(
        page_repeat=options.page_repeat,
        pageset_repeat=options.pageset_repeat,
        cold_load_percent=self.cold_load_percent,
        report_speed_index=options.report_speed_index)

  @classmethod
  def ShouldDisable(cls, possible_browser):
    # Superseded by page_cycler_v2.py on all other platforms (crbug.com/611329).
    return possible_browser.platform.GetOSName() != 'chromeos'


class PageCyclerIntlArFaHe(_PageCycler):
  """Page load time for a variety of pages in Arabic, Farsi and Hebrew.

  Runs against pages recorded in April, 2013.
  """
  page_set = page_sets.IntlArFaHePageSet

  @classmethod
  def Name(cls):
    return 'page_cycler.intl_ar_fa_he'


class PageCyclerIntlEsFrPtBr(_PageCycler):
  """Page load time for a pages in Spanish, French and Brazilian Portuguese.

  Runs against pages recorded in April, 2013.
  """
  page_set = page_sets.IntlEsFrPtBrPageSet

  @classmethod
  def Name(cls):
    return 'page_cycler.intl_es_fr_pt-BR'


class PageCyclerIntlHiRu(_PageCycler):
  """Page load time benchmark for a variety of pages in Hindi and Russian.

  Runs against pages recorded in April, 2013.
  """
  page_set = page_sets.IntlHiRuPageSet

  @classmethod
  def Name(cls):
    return 'page_cycler.intl_hi_ru'


class PageCyclerIntlJaZh(_PageCycler):
  """Page load time benchmark for a variety of pages in Japanese and Chinese.

  Runs against pages recorded in April, 2013.
  """
  page_set = page_sets.IntlJaZhPageSet

  @classmethod
  def Name(cls):
    return 'page_cycler.intl_ja_zh'

  @classmethod
  def ValueCanBeAddedPredicate(cls, value, is_first_result):
    # Filter out vm_private_dirty_final_renderer
    # crbug.com/551522
    print '**** %s ***' % value.name
    filtered_name = (
        'vm_private_dirty_final_renderer.vm_private_dirty_final_renderer')
    return (super(PageCyclerIntlJaZh, cls).ValueCanBeAddedPredicate(
        value, is_first_result) and value.name != filtered_name)


class PageCyclerIntlKoThVi(_PageCycler):
  """Page load time for a variety of pages in Korean, Thai and Vietnamese.

  Runs against pages recorded in April, 2013.
  """
  page_set = page_sets.IntlKoThViPageSet

  @classmethod
  def Name(cls):
    return 'page_cycler.intl_ko_th_vi'


class PageCyclerToughLayoutCases(_PageCycler):
  """Page loading for the slowest layouts observed in the Alexa top 1 million.

  Recorded in July 2013.
  """
  page_set = page_sets.ToughLayoutCasesPageSet

  @classmethod
  def Name(cls):
    return 'page_cycler.tough_layout_cases'


class PageCyclerTypical25(_PageCycler):
  """Page load time benchmark for a 25 typical web pages.

  Designed to represent typical, not highly optimized or highly popular web
  sites. Runs against pages recorded in June, 2014.
  """
  options = {'pageset_repeat': 3}

  @classmethod
  def Name(cls):
    return 'page_cycler.typical_25'

  def CreateStorySet(self, options):
    return page_sets.Typical25PageSet(run_no_page_interactions=True)


class PageCyclerBasicOopifIsolated(_PageCycler):
  """ A benchmark measuring performance of out-of-process iframes. """
  page_set = page_sets.OopifBasicPageSet

  @classmethod
  def Name(cls):
    return 'page_cycler_site_isolation.basic_oopif'

  def SetExtraBrowserOptions(self, options):
    options.AppendExtraBrowserArgs(['--site-per-process'])


class PageCyclerBasicOopif(_PageCycler):
  """ A benchmark measuring performance of the out-of-process iframes page
  set, without running in out-of-process iframes mode.. """
  page_set = page_sets.OopifBasicPageSet

  @classmethod
  def Name(cls):
    return 'page_cycler.basic_oopif'
