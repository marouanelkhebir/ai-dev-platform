package com.mel.aidev.observability;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/** Capture of what a step produced: the budget, the routing, and the isolation between workflows. */
class StepLogCaptureTest {

    private static final UUID WORKFLOW = UUID.randomUUID();

    private LoggerContext loggerContext;
    private StepLogAppender appender;

    @BeforeEach
    void attachAppender() {
        loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        appender = new StepLogAppender();
        appender.setContext(loggerContext);
        appender.start();
        loggerContext.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).addAppender(appender);
        loggerContext.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).setLevel(Level.INFO);
    }

    @AfterEach
    void detachAppender() {
        loggerContext.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).detachAppender(appender);
        appender.stop();
        MDC.clear();
        StepLogs.clear();
    }

    @Test
    @DisplayName("the platform log of a step is captured, routed by the workflow in the MDC")
    void shouldCaptureLogEventsOfTheRunningStep() {
        StepLogBuffer buffer = StepLogs.open(WORKFLOW, UUID.randomUUID(), 1, 1_000_000);
        Logger log = LoggerFactory.getLogger(StepLogCaptureTest.class);

        log.info("outside of any workflow");
        try (var ignored = LogContext.of().workflow(WORKFLOW).apply()) {
            log.error("GitLab refused the push", new IllegalStateException("403"));
        }
        log.info("outside again");

        String captured = buffer.snapshot();
        assertThat(captured).contains("GitLab refused the push");
        // The stack trace is the point: it is what explains a failure that never reached a build.
        assertThat(captured).contains("IllegalStateException: 403");
        assertThat(captured).doesNotContain("outside");
    }

    @Test
    @DisplayName("a step records nothing once it has been closed")
    void shouldStopCapturingAfterClose() {
        StepLogBuffer buffer = StepLogs.open(WORKFLOW, UUID.randomUUID(), 1, 1_000_000);
        StepLogs.close(buffer);

        try (var ignored = LogContext.of().workflow(WORKFLOW).apply()) {
            LoggerFactory.getLogger(StepLogCaptureTest.class).info("after the step ended");
        }
        StepLogs.current(WORKFLOW).line("also after");

        assertThat(buffer.snapshot()).doesNotContain("after");
    }

    @Test
    @DisplayName("a step over budget keeps its beginning and its end, and says so")
    void shouldKeepHeadAndTailWhenOverBudget() {
        StepLogBuffer buffer = new StepLogBuffer(WORKFLOW, UUID.randomUUID(), 1, 200);

        buffer.line("first line of the build");
        for (int i = 0; i < 100; i++) {
            buffer.line("downloading dependency " + i);
        }
        buffer.line("BUILD FAILURE: the last line is the one that matters");

        String captured = buffer.snapshot();
        assertThat(buffer.truncated()).isTrue();
        assertThat(captured).contains("first line of the build");
        assertThat(captured).contains("BUILD FAILURE: the last line is the one that matters");
        assertThat(captured).contains("character(s) dropped");
        assertThat(buffer.totalChars()).isGreaterThan(200);
    }

    @Test
    @DisplayName("two workflows never see each other's output")
    void shouldIsolateWorkflows() {
        UUID other = UUID.randomUUID();
        StepLogBuffer first = StepLogs.open(WORKFLOW, UUID.randomUUID(), 1, 1_000_000);
        StepLogBuffer second = StepLogs.open(other, UUID.randomUUID(), 1, 1_000_000);

        StepLogs.current(WORKFLOW).line("build of the first ticket");
        StepLogs.current(other).line("build of the second ticket");

        assertThat(first.snapshot()).contains("first ticket").doesNotContain("second ticket");
        assertThat(second.snapshot()).contains("second ticket").doesNotContain("first ticket");
    }

    @Test
    @DisplayName("capture disabled by configuration swallows everything")
    void shouldSwallowEverythingWhenDisabled() {
        StepLogBuffer buffer = StepLogs.open(WORKFLOW, UUID.randomUUID(), 1, 0);

        buffer.line("never stored");

        assertThat(buffer.enabled()).isFalse();
        assertThat(buffer.snapshot()).isEmpty();
    }
}
