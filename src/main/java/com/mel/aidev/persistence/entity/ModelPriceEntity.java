package com.mel.aidev.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Tariff of one model, in micro-units of the currency per 1000 tokens.
 *
 * <p>A model absent from this table is not an error: its calls are recorded without a cost and the
 * dashboard reports them as unpriced, which is honest — unlike a total that silently ignores them.
 */
@Entity
@Table(name = "model_price")
public class ModelPriceEntity {

    @Id
    @Column(name = "model", nullable = false, length = 128)
    private String model;

    @Column(name = "prompt_micros_per_1k", nullable = false)
    private long promptMicrosPer1k;

    @Column(name = "completion_micros_per_1k", nullable = false)
    private long completionMicrosPer1k;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ModelPriceEntity() {
        // for JPA
    }

    public ModelPriceEntity(String model, long promptMicrosPer1k, long completionMicrosPer1k, String currency) {
        this.model = model;
        this.promptMicrosPer1k = promptMicrosPer1k;
        this.completionMicrosPer1k = completionMicrosPer1k;
        this.currency = currency == null || currency.isBlank() ? "USD" : currency;
        this.updatedAt = Instant.now();
    }

    /** Cost of one call, rounded to the nearest micro-unit. */
    public long costMicros(Integer promptTokens, Integer completionTokens) {
        long prompt = promptTokens == null ? 0L : promptTokens;
        long completion = completionTokens == null ? 0L : completionTokens;
        return Math.round((prompt * promptMicrosPer1k + completion * completionMicrosPer1k) / 1000.0);
    }

    public String getModel() {
        return model;
    }

    public long getPromptMicrosPer1k() {
        return promptMicrosPer1k;
    }

    public void setPromptMicrosPer1k(long promptMicrosPer1k) {
        this.promptMicrosPer1k = promptMicrosPer1k;
        this.updatedAt = Instant.now();
    }

    public long getCompletionMicrosPer1k() {
        return completionMicrosPer1k;
    }

    public void setCompletionMicrosPer1k(long completionMicrosPer1k) {
        this.completionMicrosPer1k = completionMicrosPer1k;
        this.updatedAt = Instant.now();
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
        this.updatedAt = Instant.now();
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
