package com.mel.aidev.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Behaviour of the workflow executor when it is saturated.
 *
 * <p>The property under test is the one that keeps the HTTP connector alive: a submission that
 * cannot be queued is dropped, never executed on the calling thread. The caller is a Jira or GitLab
 * webhook thread, and a workflow step takes minutes.
 */
class AsyncConfigTest {

    private static final int TIMEOUT_SECONDS = 5;

    /** Pool of one with a queue of ten, so the twelfth submission has nowhere to go. */
    private final ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) new AsyncConfig()
            .workflowExecutor(
                    new WorkflowProperties(null, null, null, null, null, null, 1, null, null, null));

    private final CountDownLatch release = new CountDownLatch(1);

    @AfterEach
    void tearDown() {
        release.countDown();
        executor.shutdown();
    }

    /** Fills the single thread and the ten queue slots with tasks that block until released. */
    private void saturate() throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1);
        executor.execute(() -> {
            started.countDown();
            awaitRelease();
        });
        assertThat(started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .as("the pool thread should have picked up the first task")
                .isTrue();
        for (int i = 0; i < 10; i++) {
            executor.execute(this::awaitRelease);
        }
    }

    @Test
    @DisplayName("a submission that does not fit the queue is dropped, not run on the calling thread")
    void shouldNotRunRejectedTaskOnCallerThread() throws InterruptedException {
        saturate();

        Thread caller = Thread.currentThread();
        AtomicBoolean ranOnCaller = new AtomicBoolean(false);
        // CallerRunsPolicy would run this here and block the caller until the latch opens. The
        // shedding handler returns immediately and leaves the workflow to the scheduler.
        executor.execute(() -> {
            if (Thread.currentThread() == caller) {
                ranOnCaller.set(true);
            }
            awaitRelease();
        });

        assertThat(ranOnCaller)
                .as("a rejected workflow must never execute on the webhook thread that submitted it")
                .isFalse();
    }

    @Test
    @DisplayName("rejection does not propagate to the caller, which has no way to handle it")
    void shouldNotThrowOnRejection() throws InterruptedException {
        saturate();

        // A TaskRejectedException here would surface as a 500 on the Jira webhook, and Jira would
        // retry a workflow that is already committed and waiting for the scheduler to pick it up.
        assertThatCode(() -> executor.execute(() -> {})).doesNotThrowAnyException();
    }

    private void awaitRelease() {
        try {
            release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
