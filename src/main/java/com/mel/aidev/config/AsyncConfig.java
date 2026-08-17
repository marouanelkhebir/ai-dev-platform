package com.mel.aidev.config;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Executor running the workflow steps.
 *
 * <p>The pool is deliberately small: each running workflow holds a container, a GPU-backed LLM
 * budget and a Maven build. Queueing is the right behaviour when they are all busy, and the queue is
 * bounded so a burst of Jira webhooks cannot fill the heap.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    public static final String WORKFLOW_EXECUTOR = "workflowExecutor";

    @Bean(name = WORKFLOW_EXECUTOR)
    public Executor workflowExecutor(WorkflowProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.executorPoolSize());
        executor.setMaxPoolSize(properties.executorPoolSize());
        executor.setQueueCapacity(properties.executorPoolSize() * 10);
        executor.setThreadNamePrefix("workflow-");
        executor.setRejectedExecutionHandler(new ShedToScheduler());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(120);
        // A task submitted while the context is closing would run against half-destroyed beans.
        executor.setAcceptTasksAfterContextClose(false);
        executor.initialize();
        log.info(
                "Workflow executor started with {} thread(s) and a queue of {}",
                properties.executorPoolSize(),
                properties.executorPoolSize() * 10);
        return executor;
    }

    /**
     * Drops the submission when the queue is full, leaving the workflow for the scheduler.
     *
     * <p>The obvious alternative, {@code CallerRunsPolicy}, is wrong here. The caller is a Jira or
     * GitLab webhook thread, or the scheduler thread; running {@code advance()} on it means a Tomcat
     * worker executes a full workflow — LLM calls, a Maven build, a container — while the webhook
     * delivery times out. A burst of webhooks would take the HTTP connector down with it.
     *
     * <p>Dropping is safe because nothing is lost: the workflow row is already committed with no
     * claim on it, and {@code WorkflowScheduler.resumeRunnableWorkflows} picks up exactly that shape
     * of row on its next tick. Back pressure is therefore expressed as a short delay rather than as a
     * blocked connector.
     */
    private static final class ShedToScheduler implements RejectedExecutionHandler {

        @Override
        public void rejectedExecution(Runnable task, ThreadPoolExecutor executor) {
            if (executor.isShutdown()) {
                log.info("Workflow submission dropped during shutdown; the scheduler will resume it after restart");
                return;
            }
            log.warn(
                    "Workflow executor saturated ({} active, {} queued); submission deferred to the scheduler",
                    executor.getActiveCount(),
                    executor.getQueue().size());
        }
    }
}
