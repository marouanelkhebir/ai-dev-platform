package com.mel.aidev.persistence.entity;

import com.mel.aidev.llm.ModelRole;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * The model a project pins for one logical role.
 *
 * <p>The platform allowlist ({@code ai.models.allowed}) bounds what may be pinned here. An agent
 * never selects its own model, exactly as it never selects its own container image.
 */
@Entity
@Table(name = "project_model")
public class ProjectModelEntity {

    @EmbeddedId
    private Key key;

    @Column(name = "model_name", nullable = false, length = 128)
    private String modelName;

    protected ProjectModelEntity() {
        // for JPA
    }

    public ProjectModelEntity(UUID projectId, ModelRole role, String modelName) {
        this.key = new Key(projectId, role);
        this.modelName = modelName;
    }

    public UUID getProjectId() {
        return key.projectId();
    }

    public ModelRole getRole() {
        return key.modelRole();
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    /** Composite primary key: one model per role and per project. */
    @Embeddable
    public static class Key implements Serializable {

        @Column(name = "project_id", nullable = false)
        private UUID projectId;

        @Enumerated(EnumType.STRING)
        @Column(name = "model_role", nullable = false, length = 32)
        private ModelRole modelRole;

        protected Key() {
            // for JPA
        }

        public Key(UUID projectId, ModelRole modelRole) {
            this.projectId = projectId;
            this.modelRole = modelRole;
        }

        public UUID projectId() {
            return projectId;
        }

        public ModelRole modelRole() {
            return modelRole;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            return other instanceof Key key
                    && Objects.equals(projectId, key.projectId)
                    && modelRole == key.modelRole;
        }

        @Override
        public int hashCode() {
            return Objects.hash(projectId, modelRole);
        }
    }
}
