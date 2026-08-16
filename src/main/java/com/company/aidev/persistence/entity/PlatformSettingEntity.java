package com.company.aidev.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A single configuration value overridden from the settings screen.
 *
 * <p>Only overrides live here. Whatever is not in this table keeps the value coming from {@code
 * application.yml} or the environment, so wiping the table restores the deployment defaults.
 *
 * <p>The value of a secret setting is stored encrypted; {@link #isEncrypted()} says which form the
 * {@code value} column holds.
 */
@Entity
@Table(name = "platform_setting")
public class PlatformSettingEntity {

    @Id
    @Column(name = "setting_key", nullable = false, length = 128)
    private String key;

    @Column(name = "value", columnDefinition = "text")
    private String value;

    @Column(name = "encrypted", nullable = false)
    private boolean encrypted;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by", length = 128)
    private String updatedBy;

    protected PlatformSettingEntity() {
        // for JPA
    }

    public PlatformSettingEntity(String key, String value, boolean encrypted, String updatedBy) {
        this.key = key;
        this.value = value;
        this.encrypted = encrypted;
        this.updatedBy = updatedBy;
        this.updatedAt = Instant.now();
    }

    public String getKey() {
        return key;
    }

    public String getValue() {
        return value;
    }

    public boolean isEncrypted() {
        return encrypted;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public void update(String value, boolean encrypted, String updatedBy) {
        this.value = value;
        this.encrypted = encrypted;
        this.updatedBy = updatedBy;
        this.updatedAt = Instant.now();
    }
}
