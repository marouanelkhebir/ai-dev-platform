package com.mel.aidev.workflow;

import com.mel.aidev.config.WorkflowProperties;
import com.mel.aidev.observability.StepLogBuffer;
import com.mel.aidev.observability.StepLogs;
import com.mel.aidev.persistence.entity.WorkflowStepEntity;
import com.mel.aidev.persistence.entity.WorkflowStepLogEntity;
import com.mel.aidev.persistence.repository.WorkflowStepLogRepository;
import com.mel.aidev.security.SecretRedactor;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records the complete output of each step and hands it back for debugging.
 *
 * <p>The point of this trail is the failed run: when a workflow stops on a build error, a rejected
 * push or an agent that could not converge, this is the only place that holds what actually happened
 * inside the container. The audit tables keep a truncated, per-tool view; this keeps the stream.
 *
 * <p>Nothing here is allowed to fail a step. A run that produced a merge request must not be marked
 * failed because its log could not be written.
 */
@Service
public class WorkflowStepLogService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowStepLogService.class);
    private static final String STEP_SEPARATOR = "\n";

    private final WorkflowStepLogRepository repository;
    private final SecretRedactor redactor;
    private final WorkflowProperties properties;

    public WorkflowStepLogService(
            WorkflowStepLogRepository repository, SecretRedactor redactor, WorkflowProperties properties) {
        this.repository = repository;
        this.redactor = redactor;
        this.properties = properties;
    }

    /**
     * Starts recording a step.
     *
     * <p>The returned buffer is registered against the workflow, which is how the sandbox and the
     * logging framework find it. The caller must pass it to {@link #persist(StepLogBuffer)} in a
     * {@code finally}, or the workflow keeps a buffer that grows for the rest of the run.
     */
    public StepLogBuffer begin(WorkflowStepEntity step, String jiraTicket) {
        StepLogBuffer buffer = StepLogs.open(
                step.getWorkflowId(),
                step.getId(),
                step.getSequenceNumber(),
                properties.logs().effectiveMaxChars());
        if (buffer.enabled()) {
            buffer.line("=== step %d %s ticket=%s workflow=%s started=%s ==="
                    .formatted(
                            step.getSequenceNumber(),
                            step.getStatusFrom(),
                            jiraTicket,
                            step.getWorkflowId(),
                            step.getStartedAt()));
        }
        return buffer;
    }

    /** Stores what the step produced, and releases the buffer. Never throws. */
    @Transactional
    public void persist(StepLogBuffer buffer) {
        try {
            if (buffer == null || !buffer.enabled()) {
                return;
            }
            buffer.line("=== step %d ended %s, %d character(s) produced ==="
                    .formatted(buffer.sequenceNumber(), Instant.now(), buffer.totalChars()));
            String text = buffer.snapshot();
            buffer.release();
            if (text.isBlank()) {
                return;
            }
            // Redaction runs once, on the whole step, and it is the reason a token that reached a
            // command line or an error message does not end up readable in the database.
            String redacted = redactor.redact(text);
            WorkflowStepLogEntity entity = repository.save(new WorkflowStepLogEntity(
                    buffer.workflowId(), buffer.stepId(), buffer.sequenceNumber(), redacted, buffer.truncated()));
            log.debug(
                    "Step log stored workflow={} step={} chars={} compressed={}B",
                    buffer.workflowId(),
                    buffer.sequenceNumber(),
                    entity.getUncompressedChars(),
                    entity.getCompressedBytes());
        } catch (RuntimeException e) {
            log.warn("Unable to store the log of step {}: {}", buffer == null ? null : buffer.stepId(), e.toString());
        } finally {
            StepLogs.close(buffer);
        }
    }

    /** The log of one step, inflated. */
    @Transactional(readOnly = true)
    public Optional<String> stepLog(UUID workflowId, int sequenceNumber) {
        return repository.findByWorkflowIdAndSequenceNumber(workflowId, sequenceNumber)
                .map(WorkflowStepLogEntity::text);
    }

    /** Sizes and sequence numbers of the stored logs, cheapest way to know what is available. */
    @Transactional(readOnly = true)
    public List<StepLogSummary> summaries(UUID workflowId) {
        return repository.findByWorkflowIdOrderBySequenceNumberAsc(workflowId).stream()
                .map(entity -> new StepLogSummary(
                        entity.getSequenceNumber(),
                        entity.getUncompressedChars(),
                        entity.getCompressedBytes(),
                        entity.isTruncated()))
                .toList();
    }

    /**
     * Writes every step log of a workflow, in order.
     *
     * <p>Rows are loaded and inflated one at a time: a long run holds tens of megabytes of logs, and
     * materialising them all to answer one HTTP call is how a debugging endpoint takes the platform
     * down with it.
     */
    @Transactional(readOnly = true)
    public void writeTo(UUID workflowId, Writer writer) {
        for (UUID id : repository.findIdsByWorkflowIdOrderBySequenceNumber(workflowId)) {
            repository.findById(id).ifPresent(entity -> {
                try {
                    writer.write(entity.text());
                    writer.write(STEP_SEPARATOR);
                } catch (IOException e) {
                    throw new UncheckedIOException("Unable to write the log of workflow " + workflowId, e);
                }
            });
        }
    }

    /** Same content as {@link #writeTo}, gzipped on the fly for download. */
    @Transactional(readOnly = true)
    public void writeGzipTo(UUID workflowId, OutputStream output) {
        try (GZIPOutputStream gzip = new GZIPOutputStream(output);
                Writer writer = new OutputStreamWriter(gzip, StandardCharsets.UTF_8)) {
            writeTo(workflowId, writer);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to compress the log of workflow " + workflowId, e);
        }
    }

    /** Deletes the logs of workflows that ended long enough ago. */
    @Transactional
    public int purgeOlderThanRetention() {
        int days = properties.logs().retentionDays();
        if (days <= 0) {
            return 0;
        }
        Instant before = Instant.now().minus(Duration.ofDays(days));
        int deleted = repository.deleteByWorkflowFinishedBefore(before);
        if (deleted > 0) {
            log.info("Retention deleted {} step log(s) of workflows finished before {}", deleted, before);
        }
        return deleted;
    }

    /** What the console needs to offer a download without inflating anything. */
    public record StepLogSummary(int sequence, long uncompressedChars, long compressedBytes, boolean truncated) {}
}
