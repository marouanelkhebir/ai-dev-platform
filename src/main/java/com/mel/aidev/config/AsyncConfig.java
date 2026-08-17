package com.mel.aidev.config;

import java.util.concurrent.Executor;
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
 * budget and a Maven build. Queueing is the right behaviour when they are all busy — and the queue is
 * bounded, with {@code CallerRunsPolicy}, so a burst of Jira webhooks applies back pressure instead
 * of filling the heap.
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
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(120);
        executor.initialize();
        log.info("Workflow executor started with {} thread(s)", properties.executorPoolSize());
        return executor;
    }
}
