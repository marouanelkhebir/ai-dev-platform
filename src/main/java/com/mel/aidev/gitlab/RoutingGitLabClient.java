package com.mel.aidev.gitlab;

import com.mel.aidev.bitbucket.BitbucketClient;
import com.mel.aidev.gitlab.model.CreateMergeRequestCommand;
import com.mel.aidev.gitlab.model.GitLabProject;
import com.mel.aidev.gitlab.model.MergeRequest;
import com.mel.aidev.gitlab.model.Pipeline;
import com.mel.aidev.gitlab.model.PipelineJob;
import com.mel.aidev.gitlab.model.ScannerReport;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/** Selects the SCM client from the provider prefix persisted with a project repository. */
@Component
@Primary
public class RoutingGitLabClient implements GitLabClient {
    private final RestGitLabClient gitlab;
    private final BitbucketClient bitbucket;

    public RoutingGitLabClient(RestGitLabClient gitlab, BitbucketClient bitbucket) { this.gitlab = gitlab; this.bitbucket = bitbucket; }
    private GitLabClient client(String project) { return ScmProjectId.isBitbucket(project) ? bitbucket : gitlab; }
    @Override public GitLabProject getProject(String id) { return client(id).getProject(id); }
    @Override public List<String> listRepositoryFiles(String id, String ref, int max) { return client(id).listRepositoryFiles(id, ref, max); }
    @Override public Optional<String> readFile(String id, String ref, String path) { return client(id).readFile(id, ref, path); }
    @Override public List<String> searchCode(String id, String ref, String q, int max) { return client(id).searchCode(id, ref, q, max); }
    @Override public boolean branchExists(String id, String branch) { return client(id).branchExists(id, branch); }
    @Override public MergeRequest createMergeRequest(CreateMergeRequestCommand command) { return client(command.projectId()).createMergeRequest(command); }
    @Override public Optional<MergeRequest> getMergeRequest(String id, long iid) { return client(id).getMergeRequest(id, iid); }
    @Override public Optional<MergeRequest> findOpenMergeRequestBySourceBranch(String id, String branch) { return client(id).findOpenMergeRequestBySourceBranch(id, branch); }
    @Override public String getMergeRequestDiff(String id, long iid, int max) { return client(id).getMergeRequestDiff(id, iid, max); }
    @Override public void commentMergeRequest(String id, long iid, String comment) { client(id).commentMergeRequest(id, iid, comment); }
    @Override public Optional<Pipeline> getPipeline(String id, long pid) { return client(id).getPipeline(id, pid); }
    @Override public Optional<Pipeline> getLatestPipelineForRef(String id, String ref) { return client(id).getLatestPipelineForRef(id, ref); }
    @Override public List<PipelineJob> getPipelineJobs(String id, long pid) { return client(id).getPipelineJobs(id, pid); }
    @Override public String getJobLog(String id, long jid, int max) { return client(id).getJobLog(id, jid, max); }
    @Override public List<ScannerReport> getSecurityReports(String id, long pid, int max) { return client(id).getSecurityReports(id, pid, max); }
}
