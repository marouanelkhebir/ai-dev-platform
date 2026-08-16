package com.company.aidev.api;

import com.company.aidev.api.dto.WorkflowDetailResponse;
import com.company.aidev.api.dto.WorkflowResponse;
import com.company.aidev.workflow.WorkflowChangedEvent;
import com.company.aidev.workflow.WorkflowStepEvent;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Pushes workflow changes to the open consoles.
 *
 * <p>A workflow step takes minutes, and the operator watching it has nothing else to look at: the
 * console must show the state machine moving, not a page that happens to be refreshed. Every write
 * to a workflow row or a step row is announced here and fanned out to the browsers subscribed to it.
 *
 * <p>Events are delivered <em>after</em> the transaction commits. Sending them earlier would make a
 * console re-read the workflow and see the state it just replaced.
 */
@Component
public class WorkflowEventStream {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEventStream.class);

    /** Long enough for an idle console, short enough to reclaim a browser that vanished. */
    private static final long STREAM_TIMEOUT_MS = Duration.ofMinutes(30).toMillis();

    /** Named so the browser can react to each kind without re-reading everything. */
    static final String EVENT_READY = "ready";
    static final String EVENT_WORKFLOW = "workflow";
    static final String EVENT_STEP = "step";

    /**
     * Reads are far more frequent than subscriptions, and a broadcast must never be blocked by a
     * browser connecting or leaving.
     */
    private final List<Subscription> subscriptions = new CopyOnWriteArrayList<>();

    /**
     * Opens a stream.
     *
     * @param workflowId the workflow to follow, or {@code null} to follow every workflow
     */
    public SseEmitter subscribe(UUID workflowId) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        Subscription subscription = new Subscription(workflowId, emitter);
        subscriptions.add(subscription);

        emitter.onCompletion(() -> subscriptions.remove(subscription));
        emitter.onError(error -> subscriptions.remove(subscription));
        emitter.onTimeout(() -> {
            subscriptions.remove(subscription);
            emitter.complete();
        });

        // The console only calls itself live once the server confirms the stream is open.
        send(subscription, SseEmitter.event().name(EVENT_READY).data("open", MediaType.TEXT_PLAIN));
        return emitter;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onWorkflowChanged(WorkflowChangedEvent event) {
        broadcast(event.workflow().getId(), EVENT_WORKFLOW, WorkflowResponse.from(event.workflow()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onStepRecorded(WorkflowStepEvent event) {
        broadcast(
                event.step().getWorkflowId(),
                EVENT_STEP,
                WorkflowDetailResponse.StepView.from(event.step()));
    }

    /**
     * Keeps the connection alive.
     *
     * <p>An idle SSE stream is indistinguishable from a hung one for a proxy, and a workflow can sit
     * several minutes inside a single step. The comment costs nothing and also surfaces browsers that
     * disappeared without closing, so their subscription can be dropped.
     */
    @Scheduled(fixedDelayString = "${platform.console.heartbeat-interval:PT20S}")
    public void heartbeat() {
        subscriptions.forEach(subscription -> send(subscription, SseEmitter.event().comment("alive")));
    }

    /** Visible for tests: how many consoles are currently listening. */
    int subscriberCount() {
        return subscriptions.size();
    }

    private void broadcast(UUID workflowId, String name, Object payload) {
        subscriptions.stream()
                .filter(subscription -> subscription.follows(workflowId))
                .forEach(subscription ->
                        send(subscription, SseEmitter.event().name(name).data(payload, MediaType.APPLICATION_JSON)));
    }

    /**
     * A failed write means the browser is gone: drop the subscription rather than let the exception
     * escape into the workflow thread that published the change.
     */
    private void send(Subscription subscription, SseEmitter.SseEventBuilder event) {
        try {
            subscription.emitter().send(event);
        } catch (Exception e) {
            subscriptions.remove(subscription);
            log.debug("Dropped a console stream for workflow {}: {}", subscription.workflowId(), e.toString());
            try {
                subscription.emitter().complete();
            } catch (Exception ignored) {
                // The connection is already broken; there is nothing left to close cleanly.
            }
        }
    }

    /** One open browser stream. A {@code null} workflow follows every workflow. */
    private record Subscription(UUID workflowId, SseEmitter emitter) {

        boolean follows(UUID candidate) {
            return workflowId == null || workflowId.equals(candidate);
        }
    }
}
