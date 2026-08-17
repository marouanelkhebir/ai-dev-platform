package com.mel.aidev.project;

import com.mel.aidev.persistence.entity.ProjectEntity;
import com.mel.aidev.persistence.entity.WorkflowEntity;
import com.mel.aidev.persistence.repository.ProjectRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Freezes the project configuration onto a workflow, and reads it back.
 *
 * <p>The freeze is what makes a project editable without rewriting history: a workflow launched
 * yesterday keeps yesterday's image, prefix, commands and models, including when a human retries it
 * today. Re-resolving on retry would mean a workflow that failed on one configuration is retried on
 * another, and the audit trail would describe a run that never happened.
 */
@Component
public class WorkflowLaunchConfiguration {

    private static final Logger log = LoggerFactory.getLogger(WorkflowLaunchConfiguration.class);

    private final ProjectRepository projectRepository;
    private final ProjectConfigurationResolver resolver;
    private final ObjectMapper objectMapper;

    public WorkflowLaunchConfiguration(
            ProjectRepository projectRepository, ProjectConfigurationResolver resolver, ObjectMapper objectMapper) {
        this.projectRepository = projectRepository;
        this.resolver = resolver;
        this.objectMapper = objectMapper;
    }

    /** Resolves the configuration of a project and writes it onto a workflow about to start. */
    @Transactional(readOnly = true)
    public ProjectConfiguration freeze(WorkflowEntity workflow, ProjectEntity project) {
        ProjectConfiguration configuration = resolver.resolve(project);
        workflow.setLaunchConfig(write(configuration));
        return configuration;
    }

    /**
     * Configuration a workflow runs with.
     *
     * <p>Falls back to resolving from the project when the snapshot is missing — the case of the
     * workflows attached to a project by the migration, which ran before the freeze existed.
     */
    @Transactional(readOnly = true)
    public ProjectConfiguration of(WorkflowEntity workflow) {
        ProjectConfiguration frozen = read(workflow.getLaunchConfig());
        if (frozen != null) {
            return frozen;
        }
        ProjectEntity project = projectRepository
                .findById(workflow.getProjectId())
                .orElseThrow(() -> new ProjectNotFoundException(workflow.getProjectId()));
        ProjectConfiguration resolved = resolver.resolve(project);
        workflow.setLaunchConfig(write(resolved));
        log.info("Workflow {} had no frozen configuration; resolved it from project {}", workflow.getId(), project.getName());
        return resolved;
    }

    private String write(ProjectConfiguration configuration) {
        try {
            return objectMapper.writeValueAsString(configuration);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to freeze the project configuration of a workflow", e);
        }
    }

    private ProjectConfiguration read(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, ProjectConfiguration.class);
        } catch (JsonProcessingException e) {
            // Treated as absent, like the other stored artefacts: stranding a workflow forever
            // because a snapshot no longer deserialises would be worse than re-resolving it.
            log.warn("Frozen project configuration could not be read: {}", e.getOriginalMessage());
            return null;
        }
    }
}
