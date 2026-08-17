package com.mel.aidev.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

/**
 * Idempotency ledger for incoming webhooks.
 *
 * <p>Jira and GitLab both retry deliveries. The unique constraint on {@code (source, external_id)}
 * is what makes a duplicate delivery a no-op instead of a second workflow.
 */
@Entity
@Table(
        name = "webhook_event",
        uniqueConstraints = @UniqueConstraint(name = "ux_webhook_event", columnNames = {"source", "external_id"}))
public class WebhookEventEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "source", nullable = false, length = 32)
    private String source;

    @Column(name = "external_id", nullable = false, length = 256)
    private String externalId;

    @Column(name = "event_type", length = 128)
    private String eventType;

    @Column(name = "payload", columnDefinition = "text")
    private String payload;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    protected WebhookEventEntity() {
        // for JPA
    }

    public WebhookEventEntity(String source, String externalId, String eventType, String payload) {
        this.id = UUID.randomUUID();
        this.source = source;
        this.externalId = externalId;
        this.eventType = eventType;
        this.payload = payload;
        this.receivedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getSource() {
        return source;
    }

    public String getExternalId() {
        return externalId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }
}
