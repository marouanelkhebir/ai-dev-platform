package com.company.aidev.gitlab;

import com.company.aidev.config.RestClientConfig;
import com.company.aidev.gitlab.model.CreateMergeRequestCommand;
import com.company.aidev.gitlab.model.GitLabProject;
import com.company.aidev.gitlab.model.MergeRequest;
import com.company.aidev.gitlab.model.Pipeline;
import com.company.aidev.gitlab.model.PipelineJob;
import com.company.aidev.gitlab.model.PipelineStatus;
import com.company.aidev.gitlab.model.ScannerReport;
import com.company.aidev.security.BranchPolicy;
import com.company.aidev.settings.PlatformSettings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriUtils;

/** {@link GitLabClient} over the GitLab REST API v4. */
@Component
public class RestGitLabClient implements GitLabClient {

    private static final Logger log = LoggerFactory.getLogger(RestGitLabClient.class);

    private static final int PAGE_SIZE = 100;

    /** Artifact paths produced by the GitLab security templates. */
    private static final Map<String, String> SECURITY_ARTIFACTS = Map.of(
            ScannerReport.SAST, "gl-sast-report.json",
            ScannerReport.DEPENDENCY_SCANNING, "gl-dependency-scanning-report.json",
            ScannerReport.SECRET_DETECTION, "gl-secret-detection-report.json");

    private final PlatformSettings settings;
    private final ObjectMapper objectMapper;
    private final BranchPolicy branchPolicy;

    public RestGitLabClient(
            PlatformSettings settings,
            ObjectMapper objectMapper,
            BranchPolicy branchPolicy) {
        this.settings = settings;
        this.objectMapper = objectMapper;
        this.branchPolicy = branchPolicy;
    }

    @Override
    @Retry(name = "gitlab")
    @CircuitBreaker(name = "gitlab")
    public GitLabProject getProject(String projectId) {
        JsonNode node = get("/projects/" + encode(projectId))
                .orElseThrow(() -> new GitLabException("GitLab project not found: " + projectId));
        return new GitLabProject(
                node.path("id").asLong(),
                node.path("name").asText(""),
                node.path("path_with_namespace").asText(""),
                node.path("default_branch").asText(settings.gitlab().defaultTargetBranch()),
                node.path("web_url").asText(""),
                node.path("http_url_to_repo").asText(""));
    }

    @Override
    @Retry(name = "gitlab")
    @CircuitBreaker(name = "gitlab")
    public List<String> listRepositoryFiles(String projectId, String ref, int maxEntries) {
        List<String> paths = new ArrayList<>();
        int page = 1;
        while (paths.size() < maxEntries) {
            Optional<JsonNode> body = get("/projects/" + encode(projectId) + "/repository/tree"
                    + "?recursive=true&per_page=" + PAGE_SIZE + "&page=" + page
                    + "&ref=" + encode(ref));
            if (body.isEmpty() || !body.get().isArray() || body.get().isEmpty()) {
                break;
            }
            for (JsonNode node : body.get()) {
                if ("blob".equals(node.path("type").asText())) {
                    paths.add(node.path("path").asText());
                }
                if (paths.size() >= maxEntries) {
                    break;
                }
            }
            if (body.get().size() < PAGE_SIZE) {
                break;
            }
            page++;
        }
        return paths;
    }

    @Override
    @Retry(name = "gitlab")
    @CircuitBreaker(name = "gitlab")
    public Optional<String> readFile(String projectId, String ref, String path) {
        try {
            String content = restClient()
                    .get()
                    .uri("/projects/" + encode(projectId) + "/repository/files/" + encode(path)
                            + "/raw?ref=" + encode(ref))
                    .retrieve()
                    .body(String.class);
            return Optional.ofNullable(content);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return Optional.empty();
            }
            throw new GitLabException("Unable to read " + path + " from " + projectId, e);
        }
    }

    @Override
    @Retry(name = "gitlab")
    @CircuitBreaker(name = "gitlab")
    public List<String> searchCode(String projectId, String ref, String query, int maxResults) {
        Optional<JsonNode> body = get("/projects/" + encode(projectId) + "/search?scope=blobs&ref=" + encode(ref)
                + "&per_page=" + Math.min(maxResults, PAGE_SIZE) + "&search=" + encode(query));
        if (body.isEmpty() || !body.get().isArray()) {
            return List.of();
        }
        List<String> results = new ArrayList<>();
        for (JsonNode node : body.get()) {
            results.add(node.path("path").asText("") + ":" + node.path("startline").asInt(0) + ":\n"
                    + node.path("data").asText(""));
            if (results.size() >= maxResults) {
                break;
            }
        }
        return results;
    }

    @Override
    @Retry(name = "gitlab")
    @CircuitBreaker(name = "gitlab")
    public boolean branchExists(String projectId, String branch) {
        return get("/projects/" + encode(projectId) + "/repository/branches/" + encode(branch))
                .isPresent();
    }

    @Override
    @Retry(name = "gitlab")
    @CircuitBreaker(name = "gitlab")
    public MergeRequest createMergeRequest(CreateMergeRequestCommand command) {
        // Last line of defence: the source branch must be an ai/* branch and the target must not be
        // the source of another protected branch write.
        branchPolicy.assertAgentBranch(command.sourceBranch());
        branchPolicy.assertNotProtected(command.sourceBranch());

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("source_branch", command.sourceBranch());
        payload.put("target_branch", command.targetBranch());
        payload.put("title", command.title());
        payload.put("description", command.description());
        payload.put("remove_source_branch", command.removeSourceBranch());
        payload.put("squash", command.squash());
        if (!command.labels().isEmpty()) {
            payload.put("labels", String.join(",", command.labels()));
        }

        try {
            JsonNode node = restClient()
                    .post()
                    .uri("/projects/" + encode(command.projectId()) + "/merge_requests")
                    .body(payload)
                    .retrieve()
                    .body(JsonNode.class);
            if (node == null) {
                throw new GitLabException("Empty response when creating the merge request");
            }
            MergeRequest mergeRequest = mapMergeRequest(node);
            log.info(
                    "Merge request created project={} iid={} source={} target={}",
                    command.projectId(),
                    mergeRequest.iid(),
                    command.sourceBranch(),
                    command.targetBranch());
            return mergeRequest;
        } catch (RestClientResponseException e) {
            // GitLab answers 409 when a merge request already exists for this source branch.
            if (e.getStatusCode().value() == 409) {
                return findOpenMergeRequestBySourceBranch(command.projectId(), command.sourceBranch())
                        .orElseThrow(() -> new GitLabException("Merge request conflict but none found", e));
            }
            throw new GitLabException(
                    "Unable to create merge request (HTTP " + e.getStatusCode().value() + "): "
                            + e.getResponseBodyAsString(),
                    e);
        }
    }

    @Override
    @Retry(name = "gitlab")
    @CircuitBreaker(name = "gitlab")
    public Optional<MergeRequest> getMergeRequest(String projectId, long mergeRequestIid) {
        return get("/projects/" + encode(projectId) + "/merge_requests/" + mergeRequestIid)
                .map(this::mapMergeRequest);
    }

    @Override
    @Retry(name = "gitlab")
    @CircuitBreaker(name = "gitlab")
    public Optional<MergeRequest> findOpenMergeRequestBySourceBranch(String projectId, String sourceBranch) {
        return get("/projects/" + encode(projectId) + "/merge_requests?state=opened&source_branch="
                        + encode(sourceBranch))
                .filter(JsonNode::isArray)
                .filter(array -> !array.isEmpty())
                .map(array -> mapMergeRequest(array.get(0)));
    }

    @Override
    @Retry(name = "gitlab")
    @CircuitBreaker(name = "gitlab")
    public String getMergeRequestDiff(String projectId, long mergeRequestIid, int maxChars) {
        Optional<JsonNode> body = get("/projects/" + encode(projectId) + "/merge_requests/" + mergeRequestIid
                + "/changes?access_raw_diffs=true");
        if (body.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode change : body.get().path("changes")) {
            String oldPath = change.path("old_path").asText("");
            String newPath = change.path("new_path").asText("");
            sb.append("--- a/").append(oldPath).append('\n');
            sb.append("+++ b/").append(newPath).append('\n');
            if (change.path("new_file").asBoolean(false)) {
                sb.append("[new file]\n");
            }
            if (change.path("deleted_file").asBoolean(false)) {
                sb.append("[deleted file]\n");
            }
            if (change.path("renamed_file").asBoolean(false)) {
                sb.append("[renamed from ").append(oldPath).append("]\n");
            }
            sb.append(change.path("diff").asText("")).append('\n');
            if (sb.length() > maxChars) {
                sb.append("\n...[diff truncated at ").append(maxChars).append(" characters]\n");
                break;
            }
        }
        return sb.length() > maxChars ? sb.substring(0, maxChars) : sb.toString();
    }

    @Override
    @Retry(name = "gitlab")
    @CircuitBreaker(name = "gitlab")
    public void commentMergeRequest(String projectId, long mergeRequestIid, String comment) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("body", comment);
        try {
            restClient()
                    .post()
                    .uri("/projects/" + encode(projectId) + "/merge_requests/" + mergeRequestIid + "/notes")
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            throw new GitLabException(
                    "Unable to comment merge request " + mergeRequestIid + " (HTTP " + e.getStatusCode().value() + ")",
                    e);
        }
    }

    @Override
    @Retry(name = "gitlab")
    @CircuitBreaker(name = "gitlab")
    public Optional<Pipeline> getPipeline(String projectId, long pipelineId) {
        return get("/projects/" + encode(projectId) + "/pipelines/" + pipelineId).map(RestGitLabClient::mapPipeline);
    }

    @Override
    @Retry(name = "gitlab")
    @CircuitBreaker(name = "gitlab")
    public Optional<Pipeline> getLatestPipelineForRef(String projectId, String ref) {
        return get("/projects/" + encode(projectId) + "/pipelines?ref=" + encode(ref) + "&order_by=id&sort=desc&per_page=1")
                .filter(JsonNode::isArray)
                .filter(array -> !array.isEmpty())
                .map(array -> mapPipeline(array.get(0)));
    }

    @Override
    @Retry(name = "gitlab")
    @CircuitBreaker(name = "gitlab")
    public List<PipelineJob> getPipelineJobs(String projectId, long pipelineId) {
        Optional<JsonNode> body =
                get("/projects/" + encode(projectId) + "/pipelines/" + pipelineId + "/jobs?per_page=" + PAGE_SIZE);
        if (body.isEmpty() || !body.get().isArray()) {
            return List.of();
        }
        List<PipelineJob> jobs = new ArrayList<>();
        for (JsonNode node : body.get()) {
            jobs.add(new PipelineJob(
                    node.path("id").asLong(),
                    node.path("name").asText(""),
                    node.path("stage").asText(""),
                    PipelineStatus.from(node.path("status").asText()),
                    node.path("web_url").asText(""),
                    node.path("allow_failure").asBoolean(false)));
        }
        return jobs;
    }

    @Override
    @Retry(name = "gitlab")
    @CircuitBreaker(name = "gitlab")
    public String getJobLog(String projectId, long jobId, int maxChars) {
        try {
            String trace = restClient()
                    .get()
                    .uri("/projects/" + encode(projectId) + "/jobs/" + jobId + "/trace")
                    .retrieve()
                    .body(String.class);
            if (trace == null) {
                return "";
            }
            // The useful part of a Maven or CI log is always at the end.
            return trace.length() <= maxChars
                    ? trace
                    : "...[log truncated]...\n" + trace.substring(trace.length() - maxChars);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return "";
            }
            throw new GitLabException("Unable to read job log " + jobId, e);
        }
    }

    @Override
    public List<ScannerReport> getSecurityReports(String projectId, long pipelineId, int maxCharsPerReport) {
        List<ScannerReport> reports = new ArrayList<>();
        Map<String, PipelineJob> candidates = new LinkedHashMap<>();
        for (PipelineJob job : getPipelineJobs(projectId, pipelineId)) {
            if (job.status() == PipelineStatus.SUCCESS || job.status() == PipelineStatus.FAILED) {
                candidates.put(job.name(), job);
            }
        }
        for (PipelineJob job : candidates.values()) {
            for (Map.Entry<String, String> artifact : SECURITY_ARTIFACTS.entrySet()) {
                downloadArtifact(projectId, job.id(), artifact.getValue(), maxCharsPerReport)
                        .ifPresent(content -> reports.add(new ScannerReport(artifact.getKey(), job.name(), content)));
            }
        }
        return reports;
    }

    private Optional<String> downloadArtifact(String projectId, long jobId, String artifactPath, int maxChars) {
        try {
            byte[] content = restClient()
                    .get()
                    .uri("/projects/" + encode(projectId) + "/jobs/" + jobId + "/artifacts/" + artifactPath)
                    .retrieve()
                    .body(byte[].class);
            if (content == null || content.length == 0) {
                return Optional.empty();
            }
            String text = new String(content, StandardCharsets.UTF_8);
            return Optional.of(text.length() <= maxChars ? text : text.substring(0, maxChars));
        } catch (RestClientResponseException e) {
            HttpStatusCode status = e.getStatusCode();
            if (status.value() == 404 || status.value() == 403) {
                return Optional.empty();
            }
            log.debug("Unable to download artifact {} of job {}: {}", artifactPath, jobId, e.toString());
            return Optional.empty();
        }
    }

    private Optional<JsonNode> get(String uri) {
        try {
            return Optional.ofNullable(restClient().get().uri(uri).retrieve().body(JsonNode.class));
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return Optional.empty();
            }
            throw new GitLabException("GitLab call failed " + uri + " (HTTP " + e.getStatusCode().value() + ")", e);
        }
    }

    private MergeRequest mapMergeRequest(JsonNode node) {
        return new MergeRequest(
                node.path("id").asLong(),
                node.path("iid").asLong(),
                node.path("title").asText(""),
                node.path("description").asText(""),
                node.path("source_branch").asText(""),
                node.path("target_branch").asText(""),
                node.path("state").asText(""),
                node.path("web_url").asText(""),
                node.path("sha").asText(""),
                node.hasNonNull("has_conflicts") ? node.path("has_conflicts").asBoolean() : null,
                node.path("detailed_merge_status").asText(null));
    }

    private RestClient restClient() {
        try {
            return RestClientConfig.gitlabRestClient(settings.gitlab());
        } catch (IllegalStateException e) {
            throw new GitLabException(e.getMessage(), e);
        }
    }

    private static Pipeline mapPipeline(JsonNode node) {
        return new Pipeline(
                node.path("id").asLong(),
                PipelineStatus.from(node.path("status").asText()),
                node.path("ref").asText(""),
                node.path("sha").asText(""),
                node.path("web_url").asText(""));
    }

    /** GitLab identifies projects and paths as URL-encoded strings, including the slashes. */
    private static String encode(String value) {
        return UriUtils.encode(value, StandardCharsets.UTF_8);
    }
}
