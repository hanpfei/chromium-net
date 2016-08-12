# Copyright 2015 The Chromium Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.
import argparse
import logging
import json
import StringIO
import unittest

import mock

from telemetry import benchmark
from telemetry.testing import system_stub

from core import trybot_command


class FakeProcess(object):

  def __init__(self, expected_responses):
    self._communicate = expected_responses[1:]
    self._poll = expected_responses[0]

  def communicate(self):
    return self._communicate

  def poll(self):
    return self._poll


class TrybotCommandTest(unittest.TestCase):

  # pylint: disable=protected-access

  def setUp(self):
    self.log_output = StringIO.StringIO()
    self.stream_handler = logging.StreamHandler(self.log_output)
    logging.getLogger().addHandler(self.stream_handler)
    self._subprocess_patcher = mock.patch('core.trybot_command.subprocess')
    self._mock_subprocess = self._subprocess_patcher.start()
    self._urllib2_patcher = mock.patch('core.trybot_command.urllib2')
    self._urllib2_mock = self._urllib2_patcher.start()
    self._stubs = system_stub.Override(trybot_command,
                                       ['sys', 'open', 'os'])
    # Always set git command to 'git' to simplify testing across platforms.
    self._original_git_cmd = trybot_command._GIT_CMD
    trybot_command._GIT_CMD = 'git'

  def tearDown(self):
    logging.getLogger().removeHandler(self.stream_handler)
    self.log_output.close()
    self._stubs.Restore()
    self._subprocess_patcher.stop()
    self._urllib2_patcher.stop()
    # Reset the cached builders in trybot_command
    trybot_command.Trybot._builders = None
    trybot_command._GIT_CMD = self._original_git_cmd

  def _ExpectProcesses(self, expected_args_list):
    counter = [-1]

    def side_effect(args, **kwargs):
      if not expected_args_list:
        self.fail(
            'Not expect any Popen() call but got a Popen call with %s\n' % args)
      del kwargs  # unused
      counter[0] += 1
      expected_args, expected_responses = expected_args_list[counter[0]]
      self.assertEquals(
          expected_args, args,
          'Popen() is called with unexpected args.\n Actual: %s.\n'
          'Expecting (index %i): %s' % (args, counter[0], expected_args))
      return FakeProcess(expected_responses)
    self._mock_subprocess.Popen.side_effect = side_effect

  def _MockBuilderList(self):
    ExcludedBots = trybot_command.EXCLUDED_BOTS
    builders = [bot for bot in self._builder_list if bot not in ExcludedBots]
    return builders

  def _MockTryserverJson(self, bots_dict):
    data = mock.Mock()
    data.read.return_value = json.dumps(bots_dict)
    self._urllib2_mock.urlopen.return_value = data

  def testFindAllBrowserTypesList(self):
    self._MockTryserverJson({
        'android_nexus4_perf_bisect': 'stuff',
        'mac_10_9_perf_bisect': 'otherstuff',
        'win_perf_bisect_builder': 'not a trybot',
    })
    expected_trybots_list = [
        'all',
        'all-android',
        'all-linux',
        'all-mac',
        'all-win',
        'android-nexus4',
        'mac-10-9'
    ]
    parser = trybot_command.Trybot.CreateParser()
    trybot_command.Trybot.AddCommandLineArgs(parser, None)
    trybot_action = [a for a in parser._actions if a.dest == 'trybot'][0]
    self.assertEquals(
        expected_trybots_list,
        sorted(trybot_action.choices))

  def testFindAllBrowserTypesTrybot(self):
    self._MockTryserverJson({
        'android_nexus4_perf_bisect': 'stuff',
        'mac_10_9_perf_bisect': 'otherstuff',
        'win_perf_bisect_builder': 'not a trybot',
    })
    expected_trybots_list = [
        'all',
        'all-android',
        'all-linux',
        'all-mac',
        'all-win',
        'android-nexus4',
        'mac-10-9'
    ]

    parser = trybot_command.Trybot.CreateParser()
    trybot_command.Trybot.AddCommandLineArgs(parser, None)
    trybot_action = [a for a in parser._actions if a.dest == 'trybot'][0]
    self.assertEquals(expected_trybots_list, sorted(trybot_action.choices))

  def testFindAllBrowserTypesNonTrybotBrowser(self):
    self._MockTryserverJson({})
    parser = trybot_command.Trybot.CreateParser()
    trybot_command.Trybot.AddCommandLineArgs(parser, None)
    trybot_action = [a for a in parser._actions if a.dest == 'trybot'][0]
    self.assertEquals(
        ['all', 'all-android', 'all-linux', 'all-mac', 'all-win'],
        sorted(trybot_action.choices))

  def testConstructor(self):
    self._MockTryserverJson({
        'android_nexus4_perf_bisect': 'stuff',
        'mac_10_9_perf_bisect': 'otherstuff',
        'win_perf_bisect_builder': 'not a trybot',
    })
    command = trybot_command.Trybot()
    command._InitializeBuilderNames('android-nexus4')
    self.assertTrue('android' in command._builder_names)
    self.assertEquals(['android_nexus4_perf_bisect'],
                      command._builder_names.get('android'))

  def testConstructorTrybotAll(self):
    self._MockTryserverJson({
        'android_nexus4_perf_bisect': 'stuff',
        'android_nexus5_perf_bisect': 'stuff2',
        'mac_10_9_perf_bisect': 'otherstuff',
        'mac_perf_bisect': 'otherstuff1',
        'win_perf_bisect': 'otherstuff2',
        'linux_perf_bisect': 'otherstuff3',
        'win_x64_perf_bisect': 'otherstuff4',
        'win_perf_bisect_builder': 'not a trybot',
    })
    command = trybot_command.Trybot()
    command._InitializeBuilderNames('all')
    self.assertEquals(
        ['android', 'linux', 'mac', 'win', 'win-x64'],
        sorted(command._builder_names))
    self.assertEquals(
        ['android_nexus4_perf_bisect', 'android_nexus5_perf_bisect'],
        sorted(command._builder_names.get('android')))
    self.assertEquals(
        ['mac_10_9_perf_bisect', 'mac_perf_bisect'],
        sorted(command._builder_names.get('mac')))
    self.assertEquals(
        ['linux_perf_bisect'], sorted(command._builder_names.get('linux')))
    self.assertEquals(
        ['win_perf_bisect'], sorted(command._builder_names.get('win')))
    self.assertEquals(
        ['win_x64_perf_bisect'], sorted(command._builder_names.get('win-x64')))

  def testConstructorTrybotAllWin(self):
    self._MockTryserverJson({
        'android_nexus4_perf_bisect': 'stuff',
        'android_nexus5_perf_bisect': 'stuff2',
        'win_8_perf_bisect': 'otherstuff',
        'win_perf_bisect': 'otherstuff2',
        'linux_perf_bisect': 'otherstuff3',
        'win_x64_perf_bisect': 'otherstuff4',
        'win_perf_bisect_builder': 'not a trybot',
        'win_x64_10_perf_bisect': 'otherstuff4',
        'winx64ati_perf_bisect': 'not a trybot',
        'winx64nvidia_perf_bisect': 'not a trybot',
    })
    command = trybot_command.Trybot()
    command._InitializeBuilderNames('all-win')
    self.assertEquals(
        ['win', 'win-x64'],
        sorted(command._builder_names))
    self.assertEquals(
        ['win_8_perf_bisect', 'win_perf_bisect'],
        sorted(command._builder_names.get('win')))
    self.assertNotIn(
        'win_x64_perf_bisect',
        sorted(command._builder_names.get('win')))
    self.assertEquals(
        sorted(['win_x64_perf_bisect', 'win_x64_10_perf_bisect',
                'winx64ati_perf_bisect', 'winx64nvidia_perf_bisect']),
        sorted(command._builder_names.get('win-x64')))

  def testConstructorTrybotAllAndroid(self):
    self._MockTryserverJson({
        'android_nexus4_perf_bisect': 'stuff',
        'android_nexus5_perf_bisect': 'stuff2',
        'win_8_perf_bisect': 'otherstuff',
        'win_perf_bisect': 'otherstuff2',
        'linux_perf_bisect': 'otherstuff3',
        'win_x64_perf_bisect': 'otherstuff4',
        'win_perf_bisect_builder': 'not a trybot',
    })
    command = trybot_command.Trybot()
    command._InitializeBuilderNames('all-android')
    self.assertEquals(
        ['android_nexus4_perf_bisect', 'android_nexus5_perf_bisect'],
        sorted(command._builder_names.get('android')))

  def testConstructorTrybotAllMac(self):
    self._MockTryserverJson({
        'android_nexus4_perf_bisect': 'stuff',
        'win_8_perf_bisect': 'otherstuff',
        'mac_perf_bisect': 'otherstuff2',
        'win_perf_bisect_builder': 'not a trybot',
    })
    command = trybot_command.Trybot()
    command._InitializeBuilderNames('all-mac')
    self.assertEquals(
        ['mac'],
        sorted(command._builder_names))
    self.assertEquals(
        ['mac_perf_bisect'],
        sorted(command._builder_names.get('mac')))

  def testConstructorTrybotAllLinux(self):
    self._MockTryserverJson({
        'android_nexus4_perf_bisect': 'stuff',
        'linux_perf_bisect': 'stuff1',
        'win_8_perf_bisect': 'otherstuff',
        'mac_perf_bisect': 'otherstuff2',
        'win_perf_bisect_builder': 'not a trybot',
    })
    command = trybot_command.Trybot()
    command._InitializeBuilderNames('all-linux')
    self.assertEquals(
        ['linux'],
        sorted(command._builder_names))
    self.assertEquals(
        ['linux_perf_bisect'],
        sorted(command._builder_names.get('linux')))

  def testNoGit(self):
    self._MockTryserverJson({'android_nexus4_perf_bisect': 'stuff'})
    command = trybot_command.Trybot()
    command._InitializeBuilderNames('android-nexus4')
    self._ExpectProcesses((
        (['git', 'rev-parse', '--abbrev-ref', 'HEAD'], (128, None, None)),
    ))
    options = argparse.Namespace(trybot='android', benchmark_name='dromaeo')
    command.Run(options)
    self.assertEquals(
        'Must be in a git repository to send changes to trybots.\n',
        self.log_output.getvalue())

  def testDirtyTree(self):
    self._MockTryserverJson({'android_nexus4_perf_bisect': 'stuff'})
    self._ExpectProcesses((
        (['git', 'rev-parse', '--abbrev-ref', 'HEAD'], (0, 'br', None)),
        (['git', 'update-index', '--refresh', '-q'], (0, None, None,)),
        (['git', 'diff-index', 'HEAD'], (0, 'dirty tree', None)),
    ))
    options = argparse.Namespace(trybot='android-nexus4', benchmark_name='foo')
    command = trybot_command.Trybot()
    command.Run(options, [])
    self.assertEquals(
        'Cannot send a try job with a dirty tree. Commit locally first.\n',
        self.log_output.getvalue())

  def testNoLocalCommits(self):
    self._MockTryserverJson({'android_nexus4_perf_bisect': 'stuff'})
    command = trybot_command.Trybot()
    command._InitializeBuilderNames('android-nexus4')
    self._ExpectProcesses((
        (['git', 'rev-parse', '--abbrev-ref', 'HEAD'], (0, 'br', None)),
        (['git', 'update-index', '--refresh', '-q'], (0, None, None,)),
        (['git', 'diff-index', 'HEAD'], (0, '', None)),
        (['git', 'log', 'origin/master..HEAD'], (0, '', None)),
        (['git', 'rev-parse', '--abbrev-ref', 'HEAD'], (0, 'br', None)),
        (['git', 'update-index', '--refresh', '-q'], (0, None, None,)),
        (['git', 'diff-index', 'HEAD'], (0, '', None)),
        (['git', 'log', 'origin/master..HEAD'], (0, '', None)),
    ))

    options = argparse.Namespace(trybot='android-nexus4', benchmark_name='foo')
    command.Run(options)
    self.assertEquals(
        ('No local changes found in chromium or blink trees. '
         'browser=android-nexus4 argument sends local changes to the '
         'perf trybot(s): '
         '[[\'android_nexus4_perf_bisect\']].\n'),
        self.log_output.getvalue())

  def testErrorOnBrowserArgSpecified(self):
    parser = trybot_command.Trybot.CreateParser()
    options, extra_args = parser.parse_known_args(
        ['sunspider', '--trybot=android-all', '--browser=mac'])
    with self.assertRaises(SystemExit):
      trybot_command.Trybot.ProcessCommandLineArgs(
          parser, options, extra_args, None)

  def testBranchCheckoutFails(self):
    self._MockTryserverJson({'android_nexus4_perf_bisect': 'stuff'})
    self._ExpectProcesses((
        (['git', 'rev-parse', '--abbrev-ref', 'HEAD'], (0, 'br', None)),
        (['git', 'update-index', '--refresh', '-q'], (0, None, None,)),
        (['git', 'diff-index', 'HEAD'], (0, '', None)),
        (['git', 'log', 'origin/master..HEAD'], (0, 'logs here', None)),
        (['git', 'checkout', '-b', 'telemetry-tryjob'],
         (1, None, 'fatal: A branch named \'telemetry-try\' already exists.')),
    ))

    command = trybot_command.Trybot()
    options = argparse.Namespace(trybot='android-nexus4', benchmark_name='foo')
    command.Run(options, [])
    self.assertEquals(
        ('Error creating branch telemetry-tryjob. '
         'Please delete it if it exists.\n'
         'fatal: A branch named \'telemetry-try\' already exists.\n'),
        self.log_output.getvalue())

  def _GetConfigForTrybot(self, name, platform, branch, cfg_filename,
                          is_blink=False, extra_benchmark_args=None):
    bot = '%s_perf_bisect' % name.replace('', '').replace('-', '_')
    self._MockTryserverJson({bot: 'stuff'})
    first_processes = ()
    if is_blink:
      first_processes = (
          (['git', 'rev-parse', '--abbrev-ref', 'HEAD'], (0, 'br', None)),
          (['git', 'update-index', '--refresh', '-q'], (0, None, None,)),
          (['git', 'diff-index', 'HEAD'], (0, '', None)),
          (['git', 'log', 'origin/master..HEAD'], (0, '', None))
      )
    self._ExpectProcesses(first_processes + (
        (['git', 'rev-parse', '--abbrev-ref', 'HEAD'], (0, branch, None)),
        (['git', 'update-index', '--refresh', '-q'], (0, None, None,)),
        (['git', 'diff-index', 'HEAD'], (0, '', None)),
        (['git', 'log', 'origin/master..HEAD'], (0, 'logs here', None)),
        (['git', 'checkout', '-b', 'telemetry-tryjob'], (0, None, None)),
        (['git', 'branch', '--set-upstream-to', 'origin/master'],
         (0, None, None)),
        (['git', 'commit', '-a', '-m', 'bisect config: %s' % platform],
         (0, None, None)),
        (['git', 'cl', 'upload', '-f', '--bypass-hooks', '-m',
          'CL for perf tryjob on %s' % platform],
         (0, 'stuff https://codereview.chromium.org/12345 stuff', None)),
        (['git', 'cl', 'try', '-m', 'tryserver.chromium.perf', '-b', bot],
         (0, None, None)),
        (['git', 'checkout', branch], (0, None, None)),
        (['git', 'branch', '-D', 'telemetry-tryjob'], (0, None, None))
    ))
    cfg = StringIO.StringIO()
    self._stubs.open.files = {cfg_filename: cfg}

    options = argparse.Namespace(trybot=name, benchmark_name='sunspider')
    command = trybot_command.Trybot()
    extra_benchmark_args = extra_benchmark_args or []
    command.Run(options, extra_benchmark_args)
    return cfg.getvalue()

  def testConfigAndroid(self):
    config = self._GetConfigForTrybot(
        'android-nexus4', 'android', 'somebranch',
        'tools/run-perf-test.cfg')
    self.assertEquals(
        ('config = {\n'
         '  "command": "./tools/perf/run_benchmark '
         '--browser=android-chromium sunspider --verbose",\n'
         '  "max_time_minutes": "120",\n'
         '  "repeat_count": "1",\n'
         '  "target_arch": "ia32",\n'
         '  "truncate_percent": "0"\n'
         '}'), config)

  def testConfigMac(self):
    config = self._GetConfigForTrybot(
        'mac-10-9', 'mac', 'currentwork', 'tools/run-perf-test.cfg')
    self.assertEquals(
        ('config = {\n'
         '  "command": "./tools/perf/run_benchmark '
         '--browser=release sunspider --verbose",\n'
         '  "max_time_minutes": "120",\n'
         '  "repeat_count": "1",\n'
         '  "target_arch": "ia32",\n'
         '  "truncate_percent": "0"\n'
         '}'), config)

  def testConfigWinX64(self):
    config = self._GetConfigForTrybot(
        'win-x64', 'win-x64', 'currentwork', 'tools/run-perf-test.cfg')
    self.assertEquals(
        ('config = {\n'
         '  "command": "python tools\\\\perf\\\\run_benchmark '
         '--browser=release_x64 sunspider --verbose",\n'
         '  "max_time_minutes": "120",\n'
         '  "repeat_count": "1",\n'
         '  "target_arch": "x64",\n'
         '  "truncate_percent": "0"\n'
         '}'), config)

  def testVerboseOptionIsNotAddedTwice(self):
    config = self._GetConfigForTrybot(
        'win-x64', 'win-x64', 'currentwork', 'tools/run-perf-test.cfg',
        extra_benchmark_args=['-v'])
    self.assertEquals(
        ('config = {\n'
         '  "command": "python tools\\\\perf\\\\run_benchmark '
         '--browser=release_x64 sunspider -v",\n'
         '  "max_time_minutes": "120",\n'
         '  "repeat_count": "1",\n'
         '  "target_arch": "x64",\n'
         '  "truncate_percent": "0"\n'
         '}'), config)

  def testConfigWinX64WithNoHyphen(self):
    config = self._GetConfigForTrybot(
        'winx64nvidia', 'win-x64', 'currentwork', 'tools/run-perf-test.cfg')
    self.assertEquals(
        ('config = {\n'
         '  "command": "python tools\\\\perf\\\\run_benchmark '
         '--browser=release_x64 sunspider --verbose",\n'
         '  "max_time_minutes": "120",\n'
         '  "repeat_count": "1",\n'
         '  "target_arch": "x64",\n'
         '  "truncate_percent": "0"\n'
         '}'), config)

  def testUnsupportedTrybot(self):
    self.assertRaises(
        trybot_command.TrybotError,
        trybot_command._GetBuilderNames,
        'arms-nvidia',
        {'win_perf_bisect': 'stuff'}
    )

  def testConfigBlink(self):
    config = self._GetConfigForTrybot(
        'mac-10-9', 'mac', 'blinkbranch',
        'Tools/run-perf-test.cfg', True)
    self.assertEquals(
        ('config = {\n'
         '  "command": "./tools/perf/run_benchmark '
         '--browser=release sunspider --verbose",\n'
         '  "max_time_minutes": "120",\n'
         '  "repeat_count": "1",\n'
         '  "target_arch": "ia32",\n'
         '  "truncate_percent": "0"\n'
         '}'), config)

  def testUpdateConfigGitCommitTrybotError(self):
    self._MockTryserverJson({'android_nexus4_perf_bisect': 'stuff'})
    command = trybot_command.Trybot()
    command._InitializeBuilderNames('android-nexus4')
    self._ExpectProcesses((
        (['git', 'commit', '-a', '-m', 'bisect config: android'],
         (128, 'None', 'commit failed')),
        (['git', 'cl', 'upload', '-f', '--bypass-hooks', '-m',
          'CL for perf tryjob on android'],
         (0, 'stuff https://codereview.chromium.org/12345 stuff', None)),
        (['git', 'cl', 'try', '-m', 'tryserver.chromium.perf', '-b',
          'android_nexus4_perf_bisect'], (0, None, None))))
    cfg_filename = 'tools/run-perf-test.cfg'
    cfg = StringIO.StringIO()
    self._stubs.open.files = {cfg_filename: cfg}
    self.assertRaises(
        trybot_command.TrybotError, command._UpdateConfigAndRunTryjob,
        'android', cfg_filename, [])

  def testUpdateConfigGitUploadTrybotError(self):
    self._MockTryserverJson({'android_nexus4_perf_bisect': 'stuff'})
    command = trybot_command.Trybot()
    command._InitializeBuilderNames('android-nexus4')
    self._ExpectProcesses((
        (['git', 'commit', '-a', '-m', 'bisect config: android'],
         (0, 'None', None)),
        (['git', 'cl', 'upload', '-f', '--bypass-hooks', '-m',
          'CL for perf tryjob on android'],
         (128, None, 'error')),
        (['git', 'cl', 'try', '-m', 'tryserver.chromium.perf', '-b',
          'android_nexus4_perf_bisect'], (0, None, None))))
    cfg_filename = 'tools/run-perf-test.cfg'
    cfg = StringIO.StringIO()
    self._stubs.open.files = {cfg_filename: cfg}
    self.assertRaises(
        trybot_command.TrybotError, command._UpdateConfigAndRunTryjob,
        'android', cfg_filename, [])

  def testUpdateConfigGitTryTrybotError(self):
    self._MockTryserverJson({'android_nexus4_perf_bisect': 'stuff'})
    command = trybot_command.Trybot()
    command._InitializeBuilderNames('android-nexus4')
    self._ExpectProcesses((
        (['git', 'commit', '-a', '-m', 'bisect config: android'],
         (0, 'None', None)),
        (['git', 'cl', 'upload', '-f', '--bypass-hooks', '-m',
          'CL for perf tryjob on android'],
         (0, 'stuff https://codereview.chromium.org/12345 stuff', None)),
        (['git', 'cl', 'try', '-m', 'tryserver.chromium.perf', '-b',
          'android_nexus4_perf_bisect'], (128, None, None))))
    cfg_filename = 'tools/run-perf-test.cfg'
    cfg = StringIO.StringIO()
    self._stubs.open.files = {cfg_filename: cfg}
    self.assertRaises(
        trybot_command.TrybotError, command._UpdateConfigAndRunTryjob,
        'android', cfg_filename, [])

  def testUpdateConfigSkipTryjob(self):
    self._MockTryserverJson({'win_perf_bisect': 'stuff'})
    command = trybot_command.Trybot()
    command._InitializeBuilderNames('win-x64')
    self._ExpectProcesses(())
    cfg_filename = 'tools/run-perf-test.cfg'
    cfg_data = ('''config = {
  "command": "python tools\\\\perf\\\\run_benchmark --browser=release_x64'''
''' --verbose",
  "max_time_minutes": "120",
  "repeat_count": "1",
  "target_arch": "x64",
  "truncate_percent": "0"
}''')
    self._stubs.open.files = {cfg_filename: cfg_data}
    self.assertEquals((trybot_command.NO_CHANGES, ''),
                      command._UpdateConfigAndRunTryjob(
                          'win-x64', cfg_filename, []))

  def testUpdateConfigGitTry(self):
    self._MockTryserverJson({'android_nexus4_perf_bisect': 'stuff'})
    command = trybot_command.Trybot()
    command._InitializeBuilderNames('android-nexus4')
    self._ExpectProcesses((
        (['git', 'commit', '-a', '-m', 'bisect config: android'],
         (0, 'None', None)),
        (['git', 'cl', 'upload', '-f', '--bypass-hooks', '-m',
          'CL for perf tryjob on android'],
         (0, 'stuff https://codereview.chromium.org/12345 stuff', None)),
        (['git', 'cl', 'try', '-m', 'tryserver.chromium.perf', '-b',
          'android_nexus4_perf_bisect'], (0, None, None))))
    cfg_filename = 'tools/run-perf-test.cfg'
    cfg = StringIO.StringIO()
    self._stubs.open.files = {cfg_filename: cfg}
    self.assertEquals((0, 'https://codereview.chromium.org/12345'),
                      command._UpdateConfigAndRunTryjob(
                          'android', cfg_filename, []))
    cfg.seek(0)
    config = '''config = {
  "command": "./tools/perf/run_benchmark --browser=android-chromium --verbose",
  "max_time_minutes": "120",
  "repeat_count": "1",
  "target_arch": "ia32",
  "truncate_percent": "0"
}'''
    self.assertEquals(cfg.read(), config)

  def testUpdateConfigGitTryAll(self):
    self._MockTryserverJson({
        'android_nexus4_perf_bisect': 'stuff',
        'win_8_perf_bisect': 'stuff2'
    })
    command = trybot_command.Trybot()
    command._InitializeBuilderNames('all')
    self._ExpectProcesses((
        (['git', 'rev-parse', '--abbrev-ref', 'HEAD'],
         (0, 'CURRENT-BRANCH', None)),
        (['git', 'update-index', '--refresh', '-q'],
         (0, '', None)),
        (['git', 'diff-index', 'HEAD'],
         (0, '', None)),
        (['git', 'log', 'origin/master..HEAD'],
         (0, 'abcdef', None)),
        (['git', 'checkout', '-b', 'telemetry-tryjob'],
         (0, '', None)),
        (['git', 'branch', '--set-upstream-to', 'origin/master'],
         (0, '', None)),
        (['git', 'commit', '-a', '-m', 'bisect config: win'],
         (0, 'None', None)),
        (['git', 'cl', 'upload', '-f', '--bypass-hooks', '-m',
          'CL for perf tryjob on win'],
         (0, 'stuff2 https://codereview.chromium.org/12345 stuff2', None)),
        (['git', 'cl', 'try', '-m', 'tryserver.chromium.perf', '-b',
          'win_8_perf_bisect'],
         (0, None, None)),
        (['git', 'commit', '-a', '-m', 'bisect config: android'],
         (0, 'None', None)),
        (['git', 'cl', 'upload', '-f', '--bypass-hooks', '-m',
          'CL for perf tryjob on android'],
         (0, 'stuff https://codereview.chromium.org/12345 stuff', None)),
        (['git', 'cl', 'try', '-m', 'tryserver.chromium.perf', '-b',
          'android_nexus4_perf_bisect'], (0, None, None)),
        (['git', 'checkout', 'CURRENT-BRANCH'],
         (0, '', None)),
        (['git', 'branch', '-D', 'telemetry-tryjob'],
         (0, '', None))))
    cfg_filename = 'tools/run-perf-test.cfg'
    cfg = StringIO.StringIO()
    self._stubs.open.files = {cfg_filename: cfg}
    self.assertEquals(0, command._AttemptTryjob(cfg_filename, []))
    cfg.seek(0)

    # The config contains both config for browser release & android-chromium,
    # but that's because the stub testing does not reset the StringIO. In
    # reality, the cfg_filename should be overwritten with the new data.
    config = ('''config = {
  "command": "python tools\\\\perf\\\\run_benchmark --browser=release '''
  '''--verbose",
  "max_time_minutes": "120",
  "repeat_count": "1",
  "target_arch": "ia32",
  "truncate_percent": "0"
}''''''config = {
  "command": "./tools/perf/run_benchmark --browser=android-chromium --verbose",
  "max_time_minutes": "120",
  "repeat_count": "1",
  "target_arch": "ia32",
  "truncate_percent": "0"
}''')
    self.assertEquals(cfg.read(), config)



class IsBenchmarkDisabledOnTrybotPlatformTest(unittest.TestCase):

  def IsBenchmarkDisabled(self, benchmark_class, trybot_name):
    return trybot_command.Trybot.IsBenchmarkDisabledOnTrybotPlatform(
        benchmark_class, trybot_name)[0]

  def testBenchmarkIsDisabledAll(self):
    @benchmark.Disabled('all')
    class FooBenchmark(benchmark.Benchmark):
      pass
    self.assertTrue(self.IsBenchmarkDisabled(FooBenchmark, 'all'))
    self.assertTrue(self.IsBenchmarkDisabled(FooBenchmark, 'all-mac'))
    self.assertTrue(self.IsBenchmarkDisabled(FooBenchmark, 'android-s5'))
    self.assertTrue(self.IsBenchmarkDisabled(FooBenchmark, 'linux'))
    self.assertTrue(self.IsBenchmarkDisabled(FooBenchmark, 'winx64ati'))

  def testBenchmarkIsEnabledAll(self):
    @benchmark.Enabled('all')
    class FooBenchmark(benchmark.Benchmark):
      pass
    self.assertFalse(self.IsBenchmarkDisabled(FooBenchmark, 'all'))
    self.assertFalse(self.IsBenchmarkDisabled(FooBenchmark, 'all-mac'))
    self.assertFalse(self.IsBenchmarkDisabled(FooBenchmark, 'android-s5'))
    self.assertFalse(self.IsBenchmarkDisabled(FooBenchmark, 'linux'))
    self.assertFalse(self.IsBenchmarkDisabled(FooBenchmark, 'winx64ati'))

  def testBenchmarkIsDisabledOnMultiplePlatforms(self):
    @benchmark.Disabled('win', 'mac')
    class FooBenchmark(benchmark.Benchmark):
      pass
    self.assertFalse(self.IsBenchmarkDisabled(FooBenchmark, 'all'))
    self.assertFalse(self.IsBenchmarkDisabled(FooBenchmark, 'android-s5'))
    self.assertFalse(self.IsBenchmarkDisabled(FooBenchmark, 'linux'))

    self.assertTrue(self.IsBenchmarkDisabled(FooBenchmark, 'all-mac'))
    self.assertTrue(self.IsBenchmarkDisabled(FooBenchmark, 'winx64ati'))

  def testBenchmarkIsEnabledOnMultiplePlatforms(self):
    @benchmark.Enabled('win', 'mac')
    class FooBenchmark(benchmark.Benchmark):
      pass
    self.assertFalse(self.IsBenchmarkDisabled(FooBenchmark, 'all'))
    self.assertFalse(self.IsBenchmarkDisabled(FooBenchmark, 'all-mac'))
    self.assertFalse(self.IsBenchmarkDisabled(FooBenchmark, 'winx64ati'))

    self.assertTrue(self.IsBenchmarkDisabled(FooBenchmark, 'android-s5'))
    self.assertTrue(self.IsBenchmarkDisabled(FooBenchmark, 'linux'))
    self.assertTrue(self.IsBenchmarkDisabled(FooBenchmark, 'all-linux'))
