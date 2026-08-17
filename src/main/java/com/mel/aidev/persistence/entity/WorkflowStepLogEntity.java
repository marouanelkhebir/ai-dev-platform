package com.mel.aidev.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The full output of one workflow step: container commands and platform log, gzipped.
 *
 * <p>Stored compressed because that is what makes storing it at all reasonable — build output is
 * highly repetitive text and gzip takes it down to a few percent of its size. The uncompressed size
 * is kept alongside so the console can show what a run actually produced without inflating anything.
 *
 * <p>The payload is written once, when the step ends, and never updated.
 */
@Entity
@Table(name = "workflow_step_log")
public class WorkflowStepLogEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "workflow_id", nullable = false)
    private UUID workflowId;

    @Column(name = "step_id", nullable = false)
    private UUID stepId;

    @Column(name = "sequence_number", nullable = false)
    private int sequenceNumber;

    // bytea, spelled out, and not @Lob: on PostgreSQL a @Lob byte[] becomes an oid — a large object
    // stored outside the table that a plain delete never reclaims. The column definition is the one
    // the migration declares, and H2 accepts "bytea" as an alias of varbinary, which is what lets
    // the same mapping run against the in-memory database of the tests.
    @JdbcTypeCode(SqlTypes.VARBINARY)
    @Column(name = "content", nullable = false, columnDefinition = "bytea")
    private byte[] content;

    @Column(name = "uncompressed_chars", nullable = false)
    private long uncompressedChars;

    @Column(name = "compressed_bytes", nullable = false)
    private long compressedBytes;

    @Column(name = "truncated", nullable = false)
    private boolean truncated;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected WorkflowStepLogEntity() {
        // for JPA
    }

    public WorkflowStepLogEntity(UUID workflowId, UUID stepId, int sequenceNumber, String plainText, boolean truncated) {
        this.id = UUID.randomUUID();
        this.workflowId = workflowId;
        this.stepId = stepId;
        this.sequenceNumber = sequenceNumber;
        this.content = compress(plainText == null ? "" : plainText);
        this.uncompressedChars = plainText == null ? 0 : plainText.length();
        this.compressedBytes = this.content.length;
        this.truncated = truncated;
        this.createdAt = Instant.now();
    }

    /** Inflates the stored payload. */
    public String text() {
        return decompress(content);
    }

    static byte[] compress(String plainText) {
        byte[] raw = plainText.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(Math.max(64, raw.length / 20));
        try (GZIPOutputStream gzip = new GZIPOutputStream(buffer)) {
            gzip.write(raw);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to compress a step log", e);
        }
        return buffer.toByteArray();
    }

    static String decompress(byte[] compressed) {
        if (compressed == null || compressed.length == 0) {
            return "";
        }
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            return new String(gzip.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to read a step log", e);
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getWorkflowId() {
        return workflowId;
    }

    public UUID getStepId() {
        return stepId;
    }

    public int getSequenceNumber() {
        return sequenceNumber;
    }

    public long getUncompressedChars() {
        return uncompressedChars;
    }

    public long getCompressedBytes() {
        return compressedBytes;
    }

    public boolean isTruncated() {
        return truncated;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
