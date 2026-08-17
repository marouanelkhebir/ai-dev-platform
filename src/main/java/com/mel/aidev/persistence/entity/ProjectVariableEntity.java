package com.mel.aidev.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One non-sensitive variable injected into the sandbox environment of a project.
 *
 * <p>These values reach the container in clear text and are readable by the agents, which is why
 * the service refuses anything that looks like a credential. Secrets live in {@code platform_setting},
 * encrypted, and never leave the process.
 */
@Entity
@Table(name = "project_variable")
public class ProjectVariableEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "value", nullable = false, length = 2048)
    private String value;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ProjectVariableEntity() {
        // for JPA
    }

    public ProjectVariableEntity(UUID projectId, String name, String value) {
        this.id = UUID.randomUUID();
        this.projectId = projectId;
        this.name = name;
        this.value = value;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public String getName() {
        return name;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
