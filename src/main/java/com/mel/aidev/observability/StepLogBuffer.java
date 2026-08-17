package com.mel.aidev.observability;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.UUID;

/**
 * Everything one workflow step produced, accumulated in memory until the step ends.
 *
 * <p>A step is minutes long and writes megabytes: a Maven build alone prints tens of thousands of
 * lines. The buffer therefore has a budget, and when the budget is exhausted it keeps the
 * <em>head</em> and the <em>tail</em> of the step rather than the head alone. That is deliberate: a
 * build tells you what it was doing at the start and why it failed at the end, and the middle is
 * repetitive download and compilation noise.
 *
 * <p>Appends come from several threads — the engine thread, the Docker callback thread that streams
 * command output, and the logging framework — so every mutation is synchronized. Contention is
 * irrelevant here: writes are coarse (a whole output frame, a whole log line) and the alternative,
 * losing interleaving, would make the trail unreadable.
 */
public final class StepLogBuffer {

    private final UUID workflowId;
    private final UUID stepId;
    private final int sequenceNumber;
    private final int maxChars;
    private final Instant openedAt = Instant.now();

    private final StringBuilder head = new StringBuilder();
    private final ArrayDeque<String> tail = new ArrayDeque<>();
    private int headChars;
    private int tailChars;
    private long droppedChars;
    private long totalChars;

    StepLogBuffer(UUID workflowId, UUID stepId, int sequenceNumber, int maxChars) {
        this.workflowId = workflowId;
        this.stepId = stepId;
        this.sequenceNumber = sequenceNumber;
        this.maxChars = Math.max(maxChars, 0);
    }

    /** A buffer that swallows everything, handed out when no step is being recorded. */
    static StepLogBuffer disabled() {
        return new StepLogBuffer(null, null, 0, 0);
    }

    public boolean enabled() {
        return maxChars > 0;
    }

    /** Appends raw text, exactly as the container or the logger produced it. */
    public synchronized void append(String text) {
        if (!enabled() || text == null || text.isEmpty()) {
            return;
        }
        totalChars += text.length();

        int headBudget = maxChars / 2;
        if (headChars < headBudget) {
            head.append(text);
            headChars += text.length();
            return;
        }
        tail.addLast(text);
        tailChars += text.length();
        // Evict whole segments from the front: they are output frames and log lines, small enough
        // that dropping one at a time keeps the tail close to its budget.
        while (tailChars > maxChars - headBudget && tail.size() > 1) {
            String evicted = tail.removeFirst();
            tailChars -= evicted.length();
            droppedChars += evicted.length();
        }
    }

    /** Appends a line, adding the trailing newline. */
    public void line(String text) {
        append(text == null ? "\n" : text + "\n");
    }

    /** Assembles what was kept, with an explicit marker where the middle was dropped. */
    public synchronized String snapshot() {
        if (!enabled()) {
            return "";
        }
        StringBuilder result = new StringBuilder(headChars + tailChars + 128);
        result.append(head);
        if (droppedChars > 0) {
            result.append("\n...[")
                    .append(droppedChars)
                    .append(" character(s) dropped: the step exceeded its log budget of ")
                    .append(maxChars)
                    .append(" characters, the middle was discarded]...\n");
        }
        tail.forEach(result::append);
        return result.toString();
    }

    /**
     * Drops what was accumulated, keeping the counters.
     *
     * <p>Called once the snapshot has been taken: redaction and compression each hold another copy
     * of the step, and a budget that is measured in characters costs twice as much in memory.
     * Releasing here is what keeps the peak of a large step to the copies actually needed.
     */
    public synchronized void release() {
        head.setLength(0);
        head.trimToSize();
        tail.clear();
        headChars = 0;
        tailChars = 0;
    }

    public UUID workflowId() {
        return workflowId;
    }

    public UUID stepId() {
        return stepId;
    }

    public int sequenceNumber() {
        return sequenceNumber;
    }

    public Instant openedAt() {
        return openedAt;
    }

    /** Number of characters produced by the step, including those that were dropped. */
    public synchronized long totalChars() {
        return totalChars;
    }

    public synchronized long droppedChars() {
        return droppedChars;
    }

    public synchronized boolean truncated() {
        return droppedChars > 0;
    }
}
