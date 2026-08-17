package com.mel.aidev.gitlab;

import com.mel.aidev.gitlab.model.CreateMergeRequestCommand;
import com.mel.aidev.gitlab.model.GitLabProject;
import com.mel.aidev.gitlab.model.MergeRequest;
import com.mel.aidev.gitlab.model.Pipeline;
import com.mel.aidev.gitlab.model.PipelineJob;
import com.mel.aidev.gitlab.model.ScannerReport;
import java.util.List;
import java.util.Optional;

/**
 * Typed GitLab access.
 *
 * <p>Note what is absent from this interface: there is no {@code mergeMergeRequest} and no
 * {@code deleteProject}. Capabilities the platform must never have are simply not implemented, which
 * is a stronger guarantee than a runtime flag.
 */
public interface GitLabClient {

    GitLabProject getProject(String projectId);

    /** Recursive file listing of a ref, capped to {@code maxEntries}. */
    List<String> listRepositoryFiles(String projectId, String ref, int maxEntries);

    /** Reads a text file from the repository, empty when it does not exist. */
    Optional<String> readFile(String projectId, String ref, String path);

    /** Full-text search in the repository blobs, returning {@code path:line: content} entries. */
    List<String> searchCode(String projectId, String ref, String query, int maxResults);

    boolean branchExists(String projectId, String branch);

    MergeRequest createMergeRequest(CreateMergeRequestCommand command);

    Optional<MergeRequest> getMergeRequest(String projectId, long mergeRequestIid);

    Optional<MergeRequest> findOpenMergeRequestBySourceBranch(String projectId, String sourceBranch);

    /** Unified diff of the merge request, truncated to {@code maxChars}. */
    String getMergeRequestDiff(String projectId, long mergeRequestIid, int maxChars);

    void commentMergeRequest(String projectId, long mergeRequestIid, String comment);

    Optional<Pipeline> getPipeline(String projectId, long pipelineId);

    Optional<Pipeline> getLatestPipelineForRef(String projectId, String ref);

    List<PipelineJob> getPipelineJobs(String projectId, long pipelineId);

    /** Trace of a job, truncated to {@code maxChars} keeping the end where failures are reported. */
    String getJobLog(String projectId, long jobId, int maxChars);

    /** Security scanner artifacts produced by the pipeline, when the templates are enabled. */
    List<ScannerReport> getSecurityReports(String projectId, long pipelineId, int maxCharsPerReport);
}
