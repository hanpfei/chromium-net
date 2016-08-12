// Copyright 2016 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "base/task_scheduler/task_tracker.h"

#include <stdint.h>

#include <memory>
#include <vector>

#include "base/bind.h"
#include "base/logging.h"
#include "base/macros.h"
#include "base/memory/ptr_util.h"
#include "base/memory/ref_counted.h"
#include "base/sequence_token.h"
#include "base/sequenced_task_runner.h"
#include "base/single_thread_task_runner.h"
#include "base/synchronization/waitable_event.h"
#include "base/task_scheduler/scheduler_lock.h"
#include "base/task_scheduler/sequence.h"
#include "base/task_scheduler/task.h"
#include "base/task_scheduler/task_traits.h"
#include "base/test/gtest_util.h"
#include "base/test/test_simple_task_runner.h"
#include "base/threading/platform_thread.h"
#include "base/threading/sequenced_task_runner_handle.h"
#include "base/threading/simple_thread.h"
#include "base/threading/thread_restrictions.h"
#include "base/threading/thread_task_runner_handle.h"
#include "testing/gtest/include/gtest/gtest.h"

namespace base {
namespace internal {

namespace {

constexpr size_t kLoadTestNumIterations = 100;

scoped_refptr<Sequence> CreateSequenceWithTask(std::unique_ptr<Task> task) {
  scoped_refptr<Sequence> sequence(new Sequence);
  sequence->PushTask(std::move(task));
  return sequence;
}

// Calls TaskTracker::Shutdown() asynchronously.
class ThreadCallingShutdown : public SimpleThread {
 public:
  explicit ThreadCallingShutdown(TaskTracker* tracker)
      : SimpleThread("ThreadCallingShutdown"),
        tracker_(tracker),
        has_returned_(WaitableEvent::ResetPolicy::MANUAL,
                      WaitableEvent::InitialState::NOT_SIGNALED) {}

  // Returns true once the async call to Shutdown() has returned.
  bool has_returned() { return has_returned_.IsSignaled(); }

 private:
  void Run() override {
    tracker_->Shutdown();
    has_returned_.Signal();
  }

  TaskTracker* const tracker_;
  WaitableEvent has_returned_;

  DISALLOW_COPY_AND_ASSIGN(ThreadCallingShutdown);
};

class ThreadPostingAndRunningTask : public SimpleThread {
 public:
  enum class Action {
    WILL_POST,
    RUN,
    WILL_POST_AND_RUN,
  };

  ThreadPostingAndRunningTask(TaskTracker* tracker,
                              scoped_refptr<Sequence> sequence,
                              Action action,
                              bool expect_post_succeeds)
      : SimpleThread("ThreadPostingAndRunningTask"),
        tracker_(tracker),
        sequence_(std::move(sequence)),
        action_(action),
        expect_post_succeeds_(expect_post_succeeds) {}

 private:
  void Run() override {
    bool post_succeeded = true;
    if (action_ == Action::WILL_POST || action_ == Action::WILL_POST_AND_RUN) {
      post_succeeded = tracker_->WillPostTask(sequence_->PeekTask());
      EXPECT_EQ(expect_post_succeeds_, post_succeeded);
    }
    if (post_succeeded &&
        (action_ == Action::RUN || action_ == Action::WILL_POST_AND_RUN)) {
      tracker_->RunNextTaskInSequence(sequence_.get());
    }
  }

  TaskTracker* const tracker_;
  const scoped_refptr<Sequence> sequence_;
  const Action action_;
  const bool expect_post_succeeds_;

  DISALLOW_COPY_AND_ASSIGN(ThreadPostingAndRunningTask);
};

class ScopedSetSingletonAllowed {
 public:
  ScopedSetSingletonAllowed(bool singleton_allowed)
      : previous_value_(
            ThreadRestrictions::SetSingletonAllowed(singleton_allowed)) {}
  ~ScopedSetSingletonAllowed() {
    ThreadRestrictions::SetSingletonAllowed(previous_value_);
  }

 private:
  const bool previous_value_;
};

class TaskSchedulerTaskTrackerTest
    : public testing::TestWithParam<TaskShutdownBehavior> {
 protected:
  TaskSchedulerTaskTrackerTest() = default;

  // Creates a task with |shutdown_behavior|.
  std::unique_ptr<Task> CreateTask(TaskShutdownBehavior shutdown_behavior) {
    return WrapUnique(new Task(
        FROM_HERE,
        Bind(&TaskSchedulerTaskTrackerTest::RunTaskCallback, Unretained(this)),
        TaskTraits().WithShutdownBehavior(shutdown_behavior), TimeDelta()));
  }

  // Calls tracker_->Shutdown() on a new thread. When this returns, Shutdown()
  // method has been entered on the new thread, but it hasn't necessarily
  // returned.
  void CallShutdownAsync() {
    ASSERT_FALSE(thread_calling_shutdown_);
    thread_calling_shutdown_.reset(new ThreadCallingShutdown(&tracker_));
    thread_calling_shutdown_->Start();
    while (!tracker_.HasShutdownStarted())
      PlatformThread::YieldCurrentThread();
  }

  void WaitForAsyncIsShutdownComplete() {
    ASSERT_TRUE(thread_calling_shutdown_);
    thread_calling_shutdown_->Join();
    EXPECT_TRUE(thread_calling_shutdown_->has_returned());
    EXPECT_TRUE(tracker_.IsShutdownComplete());
  }

  void VerifyAsyncShutdownInProgress() {
    ASSERT_TRUE(thread_calling_shutdown_);
    EXPECT_FALSE(thread_calling_shutdown_->has_returned());
    EXPECT_TRUE(tracker_.HasShutdownStarted());
    EXPECT_FALSE(tracker_.IsShutdownComplete());
  }

  size_t NumTasksExecuted() {
    AutoSchedulerLock auto_lock(lock_);
    return num_tasks_executed_;
  }

  TaskTracker tracker_;

 private:
  void RunTaskCallback() {
    AutoSchedulerLock auto_lock(lock_);
    ++num_tasks_executed_;
  }

  std::unique_ptr<ThreadCallingShutdown> thread_calling_shutdown_;

  // Synchronizes accesses to |num_tasks_executed_|.
  SchedulerLock lock_;

  size_t num_tasks_executed_ = 0;

  DISALLOW_COPY_AND_ASSIGN(TaskSchedulerTaskTrackerTest);
};

#define WAIT_FOR_ASYNC_SHUTDOWN_COMPLETED() \
  do {                                      \
    SCOPED_TRACE("");                       \
    WaitForAsyncIsShutdownComplete();       \
  } while (false)

#define VERIFY_ASYNC_SHUTDOWN_IN_PROGRESS() \
  do {                                      \
    SCOPED_TRACE("");                       \
    VerifyAsyncShutdownInProgress();        \
  } while (false)

}  // namespace

TEST_P(TaskSchedulerTaskTrackerTest, WillPostAndRunBeforeShutdown) {
  std::unique_ptr<Task> task(CreateTask(GetParam()));

  // Inform |task_tracker_| that |task| will be posted.
  EXPECT_TRUE(tracker_.WillPostTask(task.get()));

  // Run the task.
  EXPECT_EQ(0U, NumTasksExecuted());
  tracker_.RunNextTaskInSequence(CreateSequenceWithTask(std::move(task)).get());
  EXPECT_EQ(1U, NumTasksExecuted());

  // Shutdown() shouldn't block.
  tracker_.Shutdown();
}

TEST_P(TaskSchedulerTaskTrackerTest, WillPostAndRunLongTaskBeforeShutdown) {
  // Create a task that will block until |event| is signaled.
  WaitableEvent event(WaitableEvent::ResetPolicy::AUTOMATIC,
                      WaitableEvent::InitialState::NOT_SIGNALED);
  std::unique_ptr<Task> blocked_task(
      new Task(FROM_HERE, Bind(&WaitableEvent::Wait, Unretained(&event)),
               TaskTraits().WithShutdownBehavior(GetParam()), TimeDelta()));

  // Inform |task_tracker_| that |blocked_task| will be posted.
  EXPECT_TRUE(tracker_.WillPostTask(blocked_task.get()));

  // Run the task asynchronouly.
  ThreadPostingAndRunningTask thread_running_task(
      &tracker_, CreateSequenceWithTask(std::move(blocked_task)),
      ThreadPostingAndRunningTask::Action::RUN, false);
  thread_running_task.Start();

  // Initiate shutdown while the task is running.
  CallShutdownAsync();

  if (GetParam() == TaskShutdownBehavior::CONTINUE_ON_SHUTDOWN) {
    // Shutdown should complete even with a CONTINUE_ON_SHUTDOWN in progress.
    WAIT_FOR_ASYNC_SHUTDOWN_COMPLETED();
  } else {
    // Shutdown should block with any non CONTINUE_ON_SHUTDOWN task in progress.
    VERIFY_ASYNC_SHUTDOWN_IN_PROGRESS();
  }

  // Unblock the task.
  event.Signal();
  thread_running_task.Join();

  // Shutdown should now complete for a non CONTINUE_ON_SHUTDOWN task.
  if (GetParam() != TaskShutdownBehavior::CONTINUE_ON_SHUTDOWN)
    WAIT_FOR_ASYNC_SHUTDOWN_COMPLETED();
}

TEST_P(TaskSchedulerTaskTrackerTest, WillPostBeforeShutdownRunDuringShutdown) {
  // Inform |task_tracker_| that a task will be posted.
  std::unique_ptr<Task> task(CreateTask(GetParam()));
  EXPECT_TRUE(tracker_.WillPostTask(task.get()));

  // Inform |task_tracker_| that a BLOCK_SHUTDOWN task will be posted just to
  // block shutdown.
  std::unique_ptr<Task> block_shutdown_task(
      CreateTask(TaskShutdownBehavior::BLOCK_SHUTDOWN));
  EXPECT_TRUE(tracker_.WillPostTask(block_shutdown_task.get()));

  // Call Shutdown() asynchronously.
  CallShutdownAsync();
  VERIFY_ASYNC_SHUTDOWN_IN_PROGRESS();

  // Try to run |task|. It should only run it it's BLOCK_SHUTDOWN. Otherwise it
  // should be discarded.
  EXPECT_EQ(0U, NumTasksExecuted());
  tracker_.RunNextTaskInSequence(CreateSequenceWithTask(std::move(task)).get());
  EXPECT_EQ(GetParam() == TaskShutdownBehavior::BLOCK_SHUTDOWN ? 1U : 0U,
            NumTasksExecuted());
  VERIFY_ASYNC_SHUTDOWN_IN_PROGRESS();

  // Unblock shutdown by running the remaining BLOCK_SHUTDOWN task.
  tracker_.RunNextTaskInSequence(
      CreateSequenceWithTask(std::move(block_shutdown_task)).get());
  EXPECT_EQ(GetParam() == TaskShutdownBehavior::BLOCK_SHUTDOWN ? 2U : 1U,
            NumTasksExecuted());
  WAIT_FOR_ASYNC_SHUTDOWN_COMPLETED();
}

TEST_P(TaskSchedulerTaskTrackerTest, WillPostBeforeShutdownRunAfterShutdown) {
  // Inform |task_tracker_| that a task will be posted.
  std::unique_ptr<Task> task(CreateTask(GetParam()));
  EXPECT_TRUE(tracker_.WillPostTask(task.get()));

  // Call Shutdown() asynchronously.
  CallShutdownAsync();
  EXPECT_EQ(0U, NumTasksExecuted());

  if (GetParam() == TaskShutdownBehavior::BLOCK_SHUTDOWN) {
    VERIFY_ASYNC_SHUTDOWN_IN_PROGRESS();

    // Run the task to unblock shutdown.
    tracker_.RunNextTaskInSequence(
        CreateSequenceWithTask(std::move(task)).get());
    EXPECT_EQ(1U, NumTasksExecuted());
    WAIT_FOR_ASYNC_SHUTDOWN_COMPLETED();

    // It is not possible to test running a BLOCK_SHUTDOWN task posted before
    // shutdown after shutdown because Shutdown() won't return if there are
    // pending BLOCK_SHUTDOWN tasks.
  } else {
    WAIT_FOR_ASYNC_SHUTDOWN_COMPLETED();

    // The task shouldn't be allowed to run after shutdown.
    tracker_.RunNextTaskInSequence(
        CreateSequenceWithTask(std::move(task)).get());
    EXPECT_EQ(0U, NumTasksExecuted());
  }
}

TEST_P(TaskSchedulerTaskTrackerTest, WillPostAndRunDuringShutdown) {
  // Inform |task_tracker_| that a BLOCK_SHUTDOWN task will be posted just to
  // block shutdown.
  std::unique_ptr<Task> block_shutdown_task(
      CreateTask(TaskShutdownBehavior::BLOCK_SHUTDOWN));
  EXPECT_TRUE(tracker_.WillPostTask(block_shutdown_task.get()));

  // Call Shutdown() asynchronously.
  CallShutdownAsync();
  VERIFY_ASYNC_SHUTDOWN_IN_PROGRESS();

  if (GetParam() == TaskShutdownBehavior::BLOCK_SHUTDOWN) {
    // Inform |task_tracker_| that a BLOCK_SHUTDOWN task will be posted.
    std::unique_ptr<Task> task(CreateTask(GetParam()));
    EXPECT_TRUE(tracker_.WillPostTask(task.get()));

    // Run the BLOCK_SHUTDOWN task.
    EXPECT_EQ(0U, NumTasksExecuted());
    tracker_.RunNextTaskInSequence(
        CreateSequenceWithTask(std::move(task)).get());
    EXPECT_EQ(1U, NumTasksExecuted());
  } else {
    // It shouldn't be allowed to post a non BLOCK_SHUTDOWN task.
    std::unique_ptr<Task> task(CreateTask(GetParam()));
    EXPECT_FALSE(tracker_.WillPostTask(task.get()));

    // Don't try to run the task, because it wasn't allowed to be posted.
  }

  // Unblock shutdown by running |block_shutdown_task|.
  VERIFY_ASYNC_SHUTDOWN_IN_PROGRESS();
  tracker_.RunNextTaskInSequence(
      CreateSequenceWithTask(std::move(block_shutdown_task)).get());
  EXPECT_EQ(GetParam() == TaskShutdownBehavior::BLOCK_SHUTDOWN ? 2U : 1U,
            NumTasksExecuted());
  WAIT_FOR_ASYNC_SHUTDOWN_COMPLETED();
}

TEST_P(TaskSchedulerTaskTrackerTest, WillPostAfterShutdown) {
  tracker_.Shutdown();

  std::unique_ptr<Task> task(CreateTask(GetParam()));

  // |task_tracker_| shouldn't allow a task to be posted after shutdown.
  if (GetParam() == TaskShutdownBehavior::BLOCK_SHUTDOWN) {
    EXPECT_DCHECK_DEATH({ tracker_.WillPostTask(task.get()); });
  } else {
    EXPECT_FALSE(tracker_.WillPostTask(task.get()));
  }
}

// Verify that BLOCK_SHUTDOWN and SKIP_ON_SHUTDOWN tasks can
// AssertSingletonAllowed() but CONTINUE_ON_SHUTDOWN tasks can't.
TEST_P(TaskSchedulerTaskTrackerTest, SingletonAllowed) {
  const bool can_use_singletons =
      (GetParam() != TaskShutdownBehavior::CONTINUE_ON_SHUTDOWN);

  TaskTracker tracker;
  std::unique_ptr<Task> task(
      new Task(FROM_HERE, Bind(&ThreadRestrictions::AssertSingletonAllowed),
               TaskTraits().WithShutdownBehavior(GetParam()), TimeDelta()));
  EXPECT_TRUE(tracker.WillPostTask(task.get()));

  // Set the singleton allowed bit to the opposite of what it is expected to be
  // when |tracker| runs |task| to verify that |tracker| actually sets the
  // correct value.
  ScopedSetSingletonAllowed scoped_singleton_allowed(!can_use_singletons);

  // Running the task should fail iff the task isn't allowed to use singletons.
  if (can_use_singletons) {
    tracker.RunNextTaskInSequence(
        CreateSequenceWithTask(std::move(task)).get());
  } else {
    EXPECT_DCHECK_DEATH(
        {
          tracker.RunNextTaskInSequence(
              CreateSequenceWithTask(std::move(task)).get());
        });
  }
}

static void RunTaskRunnerHandleVerificationTask(
    TaskTracker* tracker,
    std::unique_ptr<Task> verify_task) {
  // Pretend |verify_task| is posted to respect TaskTracker's contract.
  EXPECT_TRUE(tracker->WillPostTask(verify_task.get()));

  // Confirm that the test conditions are right (no TaskRunnerHandles set
  // already).
  EXPECT_FALSE(ThreadTaskRunnerHandle::IsSet());
  EXPECT_FALSE(SequencedTaskRunnerHandle::IsSet());

  tracker->RunNextTaskInSequence(
      CreateSequenceWithTask(std::move(verify_task)).get());

  // TaskRunnerHandle state is reset outside of task's scope.
  EXPECT_FALSE(ThreadTaskRunnerHandle::IsSet());
  EXPECT_FALSE(SequencedTaskRunnerHandle::IsSet());
}

static void VerifyNoTaskRunnerHandle() {
  EXPECT_FALSE(ThreadTaskRunnerHandle::IsSet());
  EXPECT_FALSE(SequencedTaskRunnerHandle::IsSet());
}

TEST_P(TaskSchedulerTaskTrackerTest, TaskRunnerHandleIsNotSetOnParallel) {
  // Create a task that will verify that TaskRunnerHandles are not set in its
  // scope per no TaskRunner ref being set to it.
  std::unique_ptr<Task> verify_task(
      new Task(FROM_HERE, Bind(&VerifyNoTaskRunnerHandle),
               TaskTraits().WithShutdownBehavior(GetParam()), TimeDelta()));

  RunTaskRunnerHandleVerificationTask(&tracker_, std::move(verify_task));
}

static void VerifySequencedTaskRunnerHandle(
    const SequencedTaskRunner* expected_task_runner) {
  EXPECT_FALSE(ThreadTaskRunnerHandle::IsSet());
  EXPECT_TRUE(SequencedTaskRunnerHandle::IsSet());
  EXPECT_EQ(expected_task_runner, SequencedTaskRunnerHandle::Get());
}

TEST_P(TaskSchedulerTaskTrackerTest,
       SequencedTaskRunnerHandleIsSetOnSequenced) {
  scoped_refptr<SequencedTaskRunner> test_task_runner(new TestSimpleTaskRunner);

  // Create a task that will verify that SequencedTaskRunnerHandle is properly
  // set to |test_task_runner| in its scope per |sequenced_task_runner_ref|
  // being set to it.
  std::unique_ptr<Task> verify_task(
      new Task(FROM_HERE, Bind(&VerifySequencedTaskRunnerHandle,
                               base::Unretained(test_task_runner.get())),
               TaskTraits().WithShutdownBehavior(GetParam()), TimeDelta()));
  verify_task->sequenced_task_runner_ref = test_task_runner;

  RunTaskRunnerHandleVerificationTask(&tracker_, std::move(verify_task));
}

static void VerifyThreadTaskRunnerHandle(
    const SingleThreadTaskRunner* expected_task_runner) {
  EXPECT_TRUE(ThreadTaskRunnerHandle::IsSet());
  // SequencedTaskRunnerHandle inherits ThreadTaskRunnerHandle for thread.
  EXPECT_TRUE(SequencedTaskRunnerHandle::IsSet());
  EXPECT_EQ(expected_task_runner, ThreadTaskRunnerHandle::Get());
}

TEST_P(TaskSchedulerTaskTrackerTest,
       ThreadTaskRunnerHandleIsSetOnSingleThreaded) {
  scoped_refptr<SingleThreadTaskRunner> test_task_runner(
      new TestSimpleTaskRunner);

  // Create a task that will verify that ThreadTaskRunnerHandle is properly set
  // to |test_task_runner| in its scope per |single_thread_task_runner_ref|
  // being set on it.
  std::unique_ptr<Task> verify_task(
      new Task(FROM_HERE, Bind(&VerifyThreadTaskRunnerHandle,
                               base::Unretained(test_task_runner.get())),
               TaskTraits().WithShutdownBehavior(GetParam()), TimeDelta()));
  verify_task->single_thread_task_runner_ref = test_task_runner;

  RunTaskRunnerHandleVerificationTask(&tracker_, std::move(verify_task));
}

INSTANTIATE_TEST_CASE_P(
    ContinueOnShutdown,
    TaskSchedulerTaskTrackerTest,
    ::testing::Values(TaskShutdownBehavior::CONTINUE_ON_SHUTDOWN));
INSTANTIATE_TEST_CASE_P(
    SkipOnShutdown,
    TaskSchedulerTaskTrackerTest,
    ::testing::Values(TaskShutdownBehavior::SKIP_ON_SHUTDOWN));
INSTANTIATE_TEST_CASE_P(
    BlockShutdown,
    TaskSchedulerTaskTrackerTest,
    ::testing::Values(TaskShutdownBehavior::BLOCK_SHUTDOWN));

namespace {

void ExpectSequenceToken(SequenceToken sequence_token) {
  EXPECT_EQ(sequence_token, SequenceToken::GetForCurrentThread());
}

}  // namespace

// Verify that SequenceToken::GetForCurrentThread() returns the Sequence's token
// when a Task runs.
TEST_F(TaskSchedulerTaskTrackerTest, CurrentSequenceToken) {
  scoped_refptr<Sequence> sequence(new Sequence);
  sequence->PushTask(WrapUnique(
      new Task(FROM_HERE, Bind(&ExpectSequenceToken, sequence->token()),
               TaskTraits(), TimeDelta())));
  tracker_.WillPostTask(sequence->PeekTask());

  EXPECT_FALSE(SequenceToken::GetForCurrentThread().IsValid());
  tracker_.RunNextTaskInSequence(sequence.get());
  EXPECT_FALSE(SequenceToken::GetForCurrentThread().IsValid());
}

TEST_F(TaskSchedulerTaskTrackerTest, LoadWillPostAndRunBeforeShutdown) {
  // Post and run tasks asynchronously.
  std::vector<std::unique_ptr<ThreadPostingAndRunningTask>> threads;

  for (size_t i = 0; i < kLoadTestNumIterations; ++i) {
    threads.push_back(WrapUnique(new ThreadPostingAndRunningTask(
        &tracker_, CreateSequenceWithTask(
                       CreateTask(TaskShutdownBehavior::CONTINUE_ON_SHUTDOWN)),
        ThreadPostingAndRunningTask::Action::WILL_POST_AND_RUN, true)));
    threads.back()->Start();

    threads.push_back(WrapUnique(new ThreadPostingAndRunningTask(
        &tracker_, CreateSequenceWithTask(
                       CreateTask(TaskShutdownBehavior::SKIP_ON_SHUTDOWN)),
        ThreadPostingAndRunningTask::Action::WILL_POST_AND_RUN, true)));
    threads.back()->Start();

    threads.push_back(WrapUnique(new ThreadPostingAndRunningTask(
        &tracker_, CreateSequenceWithTask(
                       CreateTask(TaskShutdownBehavior::BLOCK_SHUTDOWN)),
        ThreadPostingAndRunningTask::Action::WILL_POST_AND_RUN, true)));
    threads.back()->Start();
  }

  for (const auto& thread : threads)
    thread->Join();

  // Expect all tasks to be executed.
  EXPECT_EQ(kLoadTestNumIterations * 3, NumTasksExecuted());

  // Should return immediately because no tasks are blocking shutdown.
  tracker_.Shutdown();
}

TEST_F(TaskSchedulerTaskTrackerTest,
       LoadWillPostBeforeShutdownAndRunDuringShutdown) {
  // Post tasks asynchronously.
  std::vector<scoped_refptr<Sequence>> sequences;
  std::vector<std::unique_ptr<ThreadPostingAndRunningTask>> post_threads;

  for (size_t i = 0; i < kLoadTestNumIterations; ++i) {
    sequences.push_back(CreateSequenceWithTask(
        CreateTask(TaskShutdownBehavior::CONTINUE_ON_SHUTDOWN)));
    post_threads.push_back(WrapUnique(new ThreadPostingAndRunningTask(
        &tracker_, sequences.back(),
        ThreadPostingAndRunningTask::Action::WILL_POST, true)));
    post_threads.back()->Start();

    sequences.push_back(CreateSequenceWithTask(
        CreateTask(TaskShutdownBehavior::SKIP_ON_SHUTDOWN)));
    post_threads.push_back(WrapUnique(new ThreadPostingAndRunningTask(
        &tracker_, sequences.back(),
        ThreadPostingAndRunningTask::Action::WILL_POST, true)));
    post_threads.back()->Start();

    sequences.push_back(CreateSequenceWithTask(
        CreateTask(TaskShutdownBehavior::BLOCK_SHUTDOWN)));
    post_threads.push_back(WrapUnique(new ThreadPostingAndRunningTask(
        &tracker_, sequences.back(),
        ThreadPostingAndRunningTask::Action::WILL_POST, true)));
    post_threads.back()->Start();
  }

  for (const auto& thread : post_threads)
    thread->Join();

  // Call Shutdown() asynchronously.
  CallShutdownAsync();

  // Run tasks asynchronously.
  std::vector<std::unique_ptr<ThreadPostingAndRunningTask>> run_threads;

  for (const auto& sequence : sequences) {
    run_threads.push_back(WrapUnique(new ThreadPostingAndRunningTask(
        &tracker_, sequence, ThreadPostingAndRunningTask::Action::RUN, false)));
    run_threads.back()->Start();
  }

  for (const auto& thread : run_threads)
    thread->Join();

  WAIT_FOR_ASYNC_SHUTDOWN_COMPLETED();

  // Expect BLOCK_SHUTDOWN tasks to have been executed.
  EXPECT_EQ(kLoadTestNumIterations, NumTasksExecuted());
}

TEST_F(TaskSchedulerTaskTrackerTest, LoadWillPostAndRunDuringShutdown) {
  // Inform |task_tracker_| that a BLOCK_SHUTDOWN task will be posted just to
  // block shutdown.
  std::unique_ptr<Task> block_shutdown_task(
      CreateTask(TaskShutdownBehavior::BLOCK_SHUTDOWN));
  EXPECT_TRUE(tracker_.WillPostTask(block_shutdown_task.get()));

  // Call Shutdown() asynchronously.
  CallShutdownAsync();

  // Post and run tasks asynchronously.
  std::vector<std::unique_ptr<ThreadPostingAndRunningTask>> threads;

  for (size_t i = 0; i < kLoadTestNumIterations; ++i) {
    threads.push_back(WrapUnique(new ThreadPostingAndRunningTask(
        &tracker_, CreateSequenceWithTask(
                       CreateTask(TaskShutdownBehavior::CONTINUE_ON_SHUTDOWN)),
        ThreadPostingAndRunningTask::Action::WILL_POST_AND_RUN, false)));
    threads.back()->Start();

    threads.push_back(WrapUnique(new ThreadPostingAndRunningTask(
        &tracker_, CreateSequenceWithTask(
                       CreateTask(TaskShutdownBehavior::SKIP_ON_SHUTDOWN)),
        ThreadPostingAndRunningTask::Action::WILL_POST_AND_RUN, false)));
    threads.back()->Start();

    threads.push_back(WrapUnique(new ThreadPostingAndRunningTask(
        &tracker_, CreateSequenceWithTask(
                       CreateTask(TaskShutdownBehavior::BLOCK_SHUTDOWN)),
        ThreadPostingAndRunningTask::Action::WILL_POST_AND_RUN, true)));
    threads.back()->Start();
  }

  for (const auto& thread : threads)
    thread->Join();

  // Expect BLOCK_SHUTDOWN tasks to have been executed.
  EXPECT_EQ(kLoadTestNumIterations, NumTasksExecuted());

  // Shutdown() shouldn't return before |block_shutdown_task| is executed.
  VERIFY_ASYNC_SHUTDOWN_IN_PROGRESS();

  // Unblock shutdown by running |block_shutdown_task|.
  tracker_.RunNextTaskInSequence(
      CreateSequenceWithTask(std::move(block_shutdown_task)).get());
  EXPECT_EQ(kLoadTestNumIterations + 1, NumTasksExecuted());
  WAIT_FOR_ASYNC_SHUTDOWN_COMPLETED();
}

}  // namespace internal
}  // namespace base
