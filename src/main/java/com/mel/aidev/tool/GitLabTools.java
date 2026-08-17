package com.mel.aidev.tool;

import com.mel.aidev.gitlab.GitLabClient;
import com.mel.aidev.gitlab.model.Pipeline;
import com.mel.aidev.gitlab.model.PipelineJob;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import java.util.List;
import java.util.UUID;

/**
 * Read-only GitLab tools, used by the agents that diagnose a failing pipeline.
 *
 * <p>Creating the merge request and posting the final report are administrative actions performed by
 * the engine, so they are not exposed as tools.
 */
public class GitLabTools {

    private static final int MAX_LOG_CHARS = 30_000;

    private final GitLabClient gitLabClient;
    private final ToolExecutionRecorder recorder;
    private final UUID workflowId;
    private final UUID agentExecutionId;
    private final String projectId;

    public GitLabTools(
            GitLabClient gitLabClient,
            ToolExecutionRecorder recorder,
            UUID workflowId,
            UUID agentExecutionId,
            String projectId) {
        this.gitLabClient = gitLabClient;
        this.recorder = recorder;
        this.workflowId = workflowId;
        this.agentExecutionId = agentExecutionId;
        this.projectId = projectId;
    }

    @Tool("List the jobs of a GitLab pipeline with their status and stage.")
    public String getPipelineJobs(@P("Pipeline identifier") long pipelineId) {
        return recorder.record(
                workflowId, agentExecutionId, "getPipelineJobs", String.valueOf(pipelineId), () -> {
                    List<PipelineJob> jobs = gitLabClient.getPipelineJobs(projectId, pipelineId);
                    if (jobs.isEmpty()) {
                        return "No job found for pipeline " + pipelineId;
                    }
                    StringBuilder sb = new StringBuilder();
                    jobs.forEach(job -> sb.append(job.id())
                            .append(" | ")
                            .append(job.stage())
                            .append(" | ")
                            .append(job.name())
                            .append(" | ")
                            .append(job.status())
                            .append(job.allowFailure() ? " (allow_failure)" : "")
                            .append('\n'));
                    return sb.toString();
                });
    }

    @Tool("Read the log of a GitLab CI job. Returns the end of the log, where failures are reported.")
    public String getJobLog(@P("Job identifier") long jobId) {
        return recorder.record(workflowId, agentExecutionId, "getJobLog", String.valueOf(jobId), () -> {
            String log = gitLabClient.getJobLog(projectId, jobId, MAX_LOG_CHARS);
            return log.isBlank() ? "Empty log for job " + jobId : log;
        });
    }

    @Tool("Get the status of a GitLab pipeline.")
    public String getPipelineStatus(@P("Pipeline identifier") long pipelineId) {
        return recorder.record(
                workflowId, agentExecutionId, "getPipelineStatus", String.valueOf(pipelineId), () -> gitLabClient
                        .getPipeline(projectId, pipelineId)
                        .map(Pipeline::status)
                        .map(Enum::name)
                        .orElse("UNKNOWN"));
    }
}
