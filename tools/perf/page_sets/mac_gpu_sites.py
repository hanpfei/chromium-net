# Copyright 2016 The Chromium Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.
from telemetry.page import page as page_module
from telemetry.page import shared_page_state
from telemetry import story


class _NoGpuSharedPageState(shared_page_state.SharedPageState):
  def __init__(self, test, finder_options, story_set):
    super(_NoGpuSharedPageState, self).__init__(
      test, finder_options, story_set)
    finder_options.browser_options.AppendExtraBrowserArgs(
      ['--disable-gpu'])


class _NoOverlaysSharedPageState(shared_page_state.SharedPageState):
  def __init__(self, test, finder_options, story_set):
    super(_NoOverlaysSharedPageState, self).__init__(
      test, finder_options, story_set)
    finder_options.browser_options.AppendExtraBrowserArgs(
      ['--disable-mac-overlays'])


class _NoWebGLImageChromiumSharedPageState(shared_page_state.SharedPageState):
  def __init__(self, test, finder_options, story_set):
    super(_NoWebGLImageChromiumSharedPageState, self).__init__(
      test, finder_options, story_set)
    finder_options.browser_options.AppendExtraBrowserArgs(
      ['--disable-webgl-image-chromium'])


class TrivialScrollingPage(page_module.Page):

  def __init__(self, page_set, shared_page_state_class):
    super(TrivialScrollingPage, self).__init__(
        url='file://trivial_sites/trivial_scrolling_page.html',
        page_set=page_set,
        name=self.__class__.__name__ + shared_page_state_class.__name__,
        shared_page_state_class=shared_page_state_class)


class TrivialBlinkingCursorPage(page_module.Page):

  def __init__(self, page_set, shared_page_state_class):
    super(TrivialBlinkingCursorPage, self).__init__(
        url='file://trivial_sites/trivial_blinking_cursor.html',
        page_set=page_set,
        name=self.__class__.__name__ + shared_page_state_class.__name__,
        shared_page_state_class=shared_page_state_class)


class TrivialCanvasPage(page_module.Page):

  def __init__(self, page_set, shared_page_state_class):
    super(TrivialCanvasPage, self).__init__(
        url='file://trivial_sites/trivial_canvas.html',
        page_set=page_set,
        name=self.__class__.__name__ + shared_page_state_class.__name__,
        shared_page_state_class=shared_page_state_class)


class TrivialWebGLPage(page_module.Page):

  def __init__(self, page_set, shared_page_state_class):
    super(TrivialWebGLPage, self).__init__(
        url='file://trivial_sites/trivial_webgl.html',
        page_set=page_set,
        name=self.__class__.__name__ + shared_page_state_class.__name__,
        shared_page_state_class=shared_page_state_class)


class TrivialBlurAnimationPage(page_module.Page):

  def __init__(self, page_set, shared_page_state_class):
    super(TrivialBlurAnimationPage, self).__init__(
        url='file://trivial_sites/trivial_blur_animation.html',
        page_set=page_set,
        name=self.__class__.__name__ + shared_page_state_class.__name__,
        shared_page_state_class=shared_page_state_class)


class TrivialFullscreenVideoPage(page_module.Page):

  def __init__(self, page_set, shared_page_state_class):
    super(TrivialFullscreenVideoPage, self).__init__(
        url='file://trivial_sites/trivial_fullscreen_video.html',
        page_set=page_set,
        name=self.__class__.__name__ + shared_page_state_class.__name__,
        shared_page_state_class=shared_page_state_class)

  def RunPageInteractions(self, action_runner):
    action_runner.PressKey("Return")


class MacGpuTrivialPagesStorySet(story.StorySet):

  def __init__(self):
    super(MacGpuTrivialPagesStorySet, self).__init__(
        cloud_storage_bucket=story.PUBLIC_BUCKET)
    self.AddStory(TrivialScrollingPage(self, shared_page_state.SharedPageState))
    self.AddStory(TrivialBlinkingCursorPage(
        self, shared_page_state.SharedPageState))
    self.AddStory(TrivialCanvasPage(self, shared_page_state.SharedPageState))
    self.AddStory(TrivialWebGLPage(self, shared_page_state.SharedPageState))
    self.AddStory(TrivialBlurAnimationPage(
        self, shared_page_state.SharedPageState))
    self.AddStory(TrivialFullscreenVideoPage(
        self, shared_page_state.SharedPageState))

    self.AddStory(TrivialScrollingPage(self, _NoOverlaysSharedPageState))
    self.AddStory(TrivialBlinkingCursorPage(self, _NoOverlaysSharedPageState))
    self.AddStory(TrivialCanvasPage(self, _NoOverlaysSharedPageState))
    self.AddStory(TrivialWebGLPage(self, _NoOverlaysSharedPageState))
    self.AddStory(TrivialBlurAnimationPage(self, _NoOverlaysSharedPageState))
    self.AddStory(TrivialFullscreenVideoPage(self, _NoOverlaysSharedPageState))

    self.AddStory(TrivialScrollingPage(self, _NoGpuSharedPageState))
    self.AddStory(TrivialBlinkingCursorPage(self, _NoGpuSharedPageState))
    self.AddStory(TrivialCanvasPage(self, _NoGpuSharedPageState))
    self.AddStory(TrivialWebGLPage(self, _NoWebGLImageChromiumSharedPageState))
    self.AddStory(TrivialBlurAnimationPage(self, _NoGpuSharedPageState))
    self.AddStory(TrivialFullscreenVideoPage(self, _NoGpuSharedPageState))

  @property
  def allow_mixed_story_states(self):
    # Return True here in order to be able to add the same tests with
    # a different SharedPageState.
    return True
