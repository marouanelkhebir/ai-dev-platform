package com.mel.aidev.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mel.aidev.config.GitHubProperties;
import com.mel.aidev.config.RestClientConfig;
import com.mel.aidev.gitlab.GitLabClient;
import com.mel.aidev.gitlab.GitLabException;
import com.mel.aidev.gitlab.ScmProjectId;
import com.mel.aidev.gitlab.model.CreateMergeRequestCommand;
import com.mel.aidev.gitlab.model.GitLabProject;
import com.mel.aidev.gitlab.model.MergeRequest;
import com.mel.aidev.gitlab.model.Pipeline;
import com.mel.aidev.gitlab.model.PipelineJob;
import com.mel.aidev.gitlab.model.PipelineStatus;
import com.mel.aidev.gitlab.model.ScannerReport;
import com.mel.aidev.security.BranchPolicy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.DefaultUriBuilderFactory;
import org.springframework.web.util.UriUtils;

/**
 * GitHub implementation of the platform's repository contract.
 *
 * <p>The vocabulary of the contract stays GitLab's, because the workflow engine speaks it: a pull
 * request is returned as a {@link MergeRequest}, and an Actions workflow run as a {@link Pipeline}.
 * The mapping is done here so that nothing above this class has to know which provider answered.
 */
@Component
public class GitHubClient implements GitLabClient {

    private static final Logger log = LoggerFactory.getLogger(GitHubClient.class);

    private static final int PAGE_SIZE = 100;
    private static final String API_VERSION = "2022-11-28";

    private final GitHubProperties properties;
    private final ObjectMapper objectMapper;
    private final BranchPolicy branchPolicy;

    public GitHubClient(GitHubProperties properties, ObjectMapper objectMapper, BranchPolicy branchPolicy) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.branchPolicy = branchPolicy;
    }

    @Override
    public GitLabProject getProject(String projectId) {
        JsonNode node = get(repositoryPath(projectId))
                .orElseThrow(() -> new GitLabException("GitHub repository not found: " + repository(projectId)));
        return new GitLabProject(
                node.path("id").asLong(),
                node.path("name").asText(""),
                // The provider-qualified identifier, not "owner/repo": callers feed it straight back
                // into this contract, and the router needs the prefix to come back here.
                projectId,
                node.path("default_branch").asText("main"),
                node.path("html_url").asText(""),
                node.path("clone_url").asText(""));
    }

    @Override
    public List<String> listRepositoryFiles(String projectId, String ref, int maxEntries) {
        Optional<JsonNode> tree = get(repositoryPath(projectId) + "/git/trees/" + segment(ref) + "?recursive=1");
        if (tree.isEmpty()) {
            return List.of();
        }
        List<String> paths = new ArrayList<>();
        for (JsonNode entry : tree.get().path("tree")) {
            if ("blob".equals(entry.path("type").asText())) {
                paths.add(entry.path("path").asText());
            }
            if (paths.size() >= maxEntries) {
                break;
            }
        }
        return paths;
    }

    @Override
    public Optional<String> readFile(String projectId, String ref, String path) {
        return text(
                repositoryPath(projectId) + "/contents/" + path(path) + "?ref=" + query(ref),
                "application/vnd.github.raw+json");
    }

    @Override
    public List<String> searchCode(String projectId, String ref, String query, int maxResults) {
        // GitHub's code search only ever answers for the indexed default branch, so the ref is not
        // sent: pretending to search an arbitrary ref would return results that do not exist on it.
        Optional<JsonNode> body = get(
                "/search/code?per_page=" + Math.min(maxResults, PAGE_SIZE)
                        + "&q=" + query(query + " repo:" + repository(projectId)),
                "application/vnd.github.text-match+json");
        if (body.isEmpty()) {
            return List.of();
        }
        List<String> results = new ArrayList<>();
        for (JsonNode item : body.get().path("items")) {
            String fragment = item.path("text_matches").isEmpty()
                    ? ""
                    : item.path("text_matches").get(0).path("fragment").asText("");
            results.add(item.path("path").asText("") + ":0:\n" + fragment);
            if (results.size() >= maxResults) {
                break;
            }
        }
        return results;
    }

    @Override
    public boolean branchExists(String projectId, String branch) {
        return get(repositoryPath(projectId) + "/branches/" + segment(branch)).isPresent();
    }

    @Override
    public MergeRequest createMergeRequest(CreateMergeRequestCommand command) {
        // Same last line of defence as on GitLab: the platform opens pull requests from its own
        // branches only, whatever an agent asked for.
        branchPolicy.assertAgentBranch(command.sourceBranch());
        branchPolicy.assertNotProtected(command.sourceBranch());

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("title", command.title());
        payload.put("body", command.description());
        payload.put("head", command.sourceBranch());
        payload.put("base", command.targetBranch());

        MergeRequest pullRequest;
        try {
            pullRequest = mapPullRequest(post(repositoryPath(command.projectId()) + "/pulls", payload));
        } catch (RestClientResponseException e) {
            // GitHub answers 422 when a pull request already exists for this head branch.
            if (e.getStatusCode().value() == 422) {
                pullRequest = findOpenMergeRequestBySourceBranch(command.projectId(), command.sourceBranch())
                        .orElseThrow(() -> failure("create pull request", e));
            } else {
                throw failure("create pull request", e);
            }
        }
        addLabels(command.projectId(), pullRequest.iid(), command.labels());
        log.info(
                "Pull request created project={} number={} source={} target={}",
                repository(command.projectId()),
                pullRequest.iid(),
                command.sourceBranch(),
                command.targetBranch());
        return pullRequest;
    }

    @Override
    public Optional<MergeRequest> getMergeRequest(String projectId, long mergeRequestIid) {
        return get(repositoryPath(projectId) + "/pulls/" + mergeRequestIid).map(this::mapPullRequest);
    }

    @Override
    public Optional<MergeRequest> findOpenMergeRequestBySourceBranch(String projectId, String sourceBranch) {
        // "head" is qualified by the owner because GitHub also accepts pull requests from forks.
        String head = owner(projectId) + ":" + sourceBranch;
        return get(repositoryPath(projectId) + "/pulls?state=open&head=" + query(head))
                .filter(JsonNode::isArray)
                .filter(array -> !array.isEmpty())
                .map(array -> mapPullRequest(array.get(0)));
    }

    @Override
    public String getMergeRequestDiff(String projectId, long mergeRequestIid, int maxChars) {
        String diff = text(repositoryPath(projectId) + "/pulls/" + mergeRequestIid, "application/vnd.github.diff")
                .orElse("");
        return diff.length() <= maxChars ? diff : diff.substring(0, maxChars) + "\n...[diff truncated]";
    }

    @Override
    public void commentMergeRequest(String projectId, long mergeRequestIid, String comment) {
        // A pull request is an issue as far as the comment API is concerned.
        try {
            post(
                    repositoryPath(projectId) + "/issues/" + mergeRequestIid + "/comments",
                    objectMapper.createObjectNode().put("body", comment));
        } catch (RestClientResponseException e) {
            throw failure("comment pull request " + mergeRequestIid, e);
        }
    }

    @Override
    public Optional<Pipeline> getPipeline(String projectId, long pipelineId) {
        return get(repositoryPath(projectId) + "/actions/runs/" + pipelineId).map(GitHubClient::mapRun);
    }

    @Override
    public Optional<Pipeline> getLatestPipelineForRef(String projectId, String ref) {
        return get(repositoryPath(projectId) + "/actions/runs?per_page=1&branch=" + query(ref))
                .map(node -> node.path("workflow_runs"))
                .filter(runs -> !runs.isEmpty())
                .map(runs -> mapRun(runs.get(0)));
    }

    @Override
    public List<PipelineJob> getPipelineJobs(String projectId, long pipelineId) {
        Optional<JsonNode> body =
                get(repositoryPath(projectId) + "/actions/runs/" + pipelineId + "/jobs?per_page=" + PAGE_SIZE);
        if (body.isEmpty()) {
            return List.of();
        }
        List<PipelineJob> jobs = new ArrayList<>();
        for (JsonNode job : body.get().path("jobs")) {
            jobs.add(new PipelineJob(
                    job.path("id").asLong(),
                    job.path("name").asText(""),
                    // Actions has no stage; the workflow name is the closest equivalent and is what a
                    // reader needs to tell two jobs with the same name apart.
                    job.path("workflow_name").asText(""),
                    status(job),
                    job.path("html_url").asText(""),
                    // Actions reports "continue-on-error" per step, never on the job, so a failed job
                    // is always treated as blocking.
                    false));
        }
        return jobs;
    }

    @Override
    public String getJobLog(String projectId, long jobId, int maxChars) {
        String path = repositoryPath(projectId) + "/actions/jobs/" + jobId + "/logs";
        String trace;
        try {
            ResponseEntity<String> response =
                    client(false).get().uri(path).retrieve().toEntity(String.class);
            URI location = response.getHeaders().getLocation();
            trace = response.getStatusCode().is3xxRedirection() && location != null
                    ? download(location)
                    : response.getBody();
        } catch (RestClientResponseException e) {
            // Logs expire, and a job that never started has none.
            if (e.getStatusCode().value() == 404 || e.getStatusCode().value() == 410) {
                return "";
            }
            throw failure("read job log " + jobId, e);
        }
        if (trace == null || trace.isEmpty()) {
            return "";
        }
        // The useful part of a build log is always at the end.
        return trace.length() <= maxChars
                ? trace
                : "...[log truncated]...\n" + trace.substring(trace.length() - maxChars);
    }

    /**
     * GitHub has no equivalent of GitLab's security report artifacts.
     *
     * <p>Code scanning alerts are a different contract entirely — findings, not scanner output — and
     * returning them here would hand the security agent a payload it does not know how to read.
     */
    @Override
    public List<ScannerReport> getSecurityReports(String projectId, long pipelineId, int maxCharsPerReport) {
        return List.of();
    }

    // ----------------------------------------------------------------- internals

    private void addLabels(String projectId, long number, List<String> labels) {
        if (labels.isEmpty()) {
            return;
        }
        ArrayNode names = objectMapper.createArrayNode();
        labels.forEach(names::add);
        try {
            post(
                    repositoryPath(projectId) + "/issues/" + number + "/labels",
                    objectMapper.createObjectNode().set("labels", names));
        } catch (RestClientResponseException e) {
            // A missing label permission must not lose a pull request that is already open.
            log.warn("Unable to label pull request {} of {}: {}", number, repository(projectId), e.toString());
        }
    }

    private MergeRequest mapPullRequest(JsonNode node) {
        boolean merged = node.path("merged").asBoolean(false) || node.hasNonNull("merged_at");
        String state = switch (node.path("state").asText("").toLowerCase(Locale.ROOT)) {
            // The engine tests for "opened"; GitHub says "open".
            case "open" -> "opened";
            case "closed" -> merged ? "merged" : "closed";
            default -> node.path("state").asText("");
        };
        return new MergeRequest(
                node.path("id").asLong(),
                node.path("number").asLong(),
                node.path("title").asText(""),
                node.path("body").asText(""),
                node.path("head").path("ref").asText(""),
                node.path("base").path("ref").asText(""),
                state,
                node.path("html_url").asText(""),
                node.path("head").path("sha").asText(""),
                // Null while GitHub is still computing mergeability, which is exactly what the engine
                // expects to mean "unknown, ask again".
                node.hasNonNull("mergeable") ? !node.path("mergeable").asBoolean() : null,
                node.path("mergeable_state").asText(""));
    }

    private static Pipeline mapRun(JsonNode node) {
        return new Pipeline(
                node.path("id").asLong(),
                status(node),
                node.path("head_branch").asText(""),
                node.path("head_sha").asText(""),
                node.path("html_url").asText(""));
    }

    /**
     * Maps an Actions run or job onto the pipeline vocabulary.
     *
     * <p>Actions splits progress in two fields: {@code status} while the run is alive and
     * {@code conclusion} once it is over. Collapsing them wrongly is how a failed build ends up
     * reported as finished-and-fine, so the conclusion always wins when there is one.
     */
    private static PipelineStatus status(JsonNode node) {
        String conclusion = node.path("conclusion").asText("");
        if (!conclusion.isBlank()) {
            return switch (conclusion.toLowerCase(Locale.ROOT)) {
                case "success" -> PipelineStatus.SUCCESS;
                case "failure", "timed_out", "startup_failure" -> PipelineStatus.FAILED;
                case "cancelled" -> PipelineStatus.CANCELED;
                case "skipped", "neutral", "stale" -> PipelineStatus.SKIPPED;
                case "action_required" -> PipelineStatus.MANUAL;
                default -> PipelineStatus.UNKNOWN;
            };
        }
        return switch (node.path("status").asText("").toLowerCase(Locale.ROOT)) {
            case "queued", "requested", "pending" -> PipelineStatus.PENDING;
            case "in_progress" -> PipelineStatus.RUNNING;
            case "waiting" -> PipelineStatus.WAITING_FOR_RESOURCE;
            default -> PipelineStatus.UNKNOWN;
        };
    }

    private Optional<JsonNode> get(String path) {
        return get(path, null);
    }

    private Optional<JsonNode> get(String path, String accept) {
        try {
            RestClient.RequestHeadersSpec<?> request = client(true).get().uri(path);
            if (accept != null) {
                request = request.accept(MediaType.parseMediaType(accept));
            }
            return Optional.ofNullable(request.retrieve().body(JsonNode.class));
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return Optional.empty();
            }
            throw failure("GET " + path, e);
        }
    }

    private Optional<String> text(String path, String accept) {
        try {
            return Optional.ofNullable(client(true)
                    .get()
                    .uri(path)
                    .accept(MediaType.parseMediaType(accept))
                    .retrieve()
                    .body(String.class));
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return Optional.empty();
            }
            throw failure("GET " + path, e);
        }
    }

    private JsonNode post(String path, JsonNode body) {
        return client(true)
                .post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);
    }

    /** Fetches a pre-signed log URL, deliberately without our credentials. */
    private String download(URI location) {
        return RestClient.builder()
                .requestFactory(RestClientConfig.requestFactory(properties.connectTimeout(), properties.readTimeout()))
                .build()
                .get()
                .uri(location)
                .retrieve()
                .body(String.class);
    }

    private RestClient client(boolean followRedirects) {
        if (!properties.isConfigured()) {
            throw new GitLabException("GitHub is not configured: set github.api-token");
        }
        // Paths are encoded segment by segment below, so the template must be left alone.
        DefaultUriBuilderFactory uriBuilderFactory = new DefaultUriBuilderFactory(properties.apiBaseUrl());
        uriBuilderFactory.setEncodingMode(DefaultUriBuilderFactory.EncodingMode.NONE);
        return RestClient.builder()
                .uriBuilderFactory(uriBuilderFactory)
                .requestFactory(RestClientConfig.requestFactory(
                        properties.connectTimeout(), properties.readTimeout(), followRedirects))
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiToken())
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", API_VERSION)
                .build();
    }

    private static String repositoryPath(String projectId) {
        String repository = repository(projectId);
        int slash = repository.indexOf('/');
        if (slash <= 0 || slash == repository.length() - 1) {
            throw new GitLabException("A GitHub repository is written 'owner/repository', got: " + repository);
        }
        return "/repos/" + segment(repository.substring(0, slash)) + "/" + segment(repository.substring(slash + 1));
    }

    private static String owner(String projectId) {
        String repository = repository(projectId);
        int slash = repository.indexOf('/');
        return slash <= 0 ? repository : repository.substring(0, slash);
    }

    private static String repository(String projectId) {
        return ScmProjectId.repository(projectId).trim();
    }

    /** Encodes one path segment; a slash inside it would otherwise change the endpoint being called. */
    private static String segment(String value) {
        return UriUtils.encodePathSegment(value, StandardCharsets.UTF_8);
    }

    /** Encodes a repository file path, keeping its separators. */
    private static String path(String value) {
        return Arrays.stream(value.split("/")).map(GitHubClient::segment).collect(Collectors.joining("/"));
    }

    private static String query(String value) {
        return UriUtils.encodeQueryParam(value, StandardCharsets.UTF_8);
    }

    private static GitLabException failure(String action, RestClientResponseException e) {
        return new GitLabException(
                "GitHub call failed (" + action + ", HTTP " + e.getStatusCode().value() + "): "
                        + e.getResponseBodyAsString(),
                e);
    }
}
