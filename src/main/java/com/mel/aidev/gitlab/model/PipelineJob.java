package com.mel.aidev.gitlab.model;

/** Typed view of a GitLab CI job. */
public record PipelineJob(
        long id, String name, String stage, PipelineStatus status, String webUrl, boolean allowFailure) {

    /** Only jobs that genuinely break the pipeline are worth sending to an agent. */
    public boolean isBlockingFailure() {
        return status == PipelineStatus.FAILED && !allowFailure;
    }
}
