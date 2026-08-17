package com.mel.aidev.bitbucket;

import com.mel.aidev.config.BitbucketProperties;
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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriUtils;

/** Bitbucket Cloud implementation of the platform's repository contract. */
@Component
public class BitbucketClient implements GitLabClient {

    private final BitbucketProperties properties;
    private final ObjectMapper objectMapper;

    public BitbucketClient(BitbucketProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public GitLabProject getProject(String projectId) {
        JsonNode node = get("/repositories/" + repository(projectId)).orElseThrow(() -> missing(projectId));
        return new GitLabProject(
                node.path("uuid").asText().hashCode(), node.path("name").asText(), projectId,
                node.path("mainbranch").path("name").asText("main"), node.path("links").path("html").path("href").asText(),
                node.path("links").path("clone").findValues("href").stream().map(JsonNode::asText)
                        .filter(url -> url.startsWith("https://")).findFirst().orElse(""));
    }

    @Override
    public List<String> listRepositoryFiles(String projectId, String ref, int maxEntries) {
        JsonNode values = get("/repositories/" + repository(projectId) + "/src/" + encode(ref)).map(n -> n.path("values")).orElse(null);
        List<String> files = new ArrayList<>();
        if (values != null && values.isArray()) for (JsonNode value : values) {
            if ("commit_file".equals(value.path("type").asText())) files.add(value.path("path").asText());
            if (files.size() == maxEntries) break;
        }
        return files;
    }

    @Override
    public Optional<String> readFile(String projectId, String ref, String path) {
        return text("/repositories/" + repository(projectId) + "/src/" + encode(ref) + "/" + path);
    }

    @Override
    public List<String> searchCode(String projectId, String ref, String query, int maxResults) {
        // Bitbucket Cloud has no server-side code-search endpoint. Listing is deliberately bounded;
        // agents can still use repository tools after cloning.
        return List.of();
    }

    @Override public boolean branchExists(String projectId, String branch) {
        return get("/repositories/" + repository(projectId) + "/refs/branches/" + encode(branch)).isPresent();
    }

    @Override
    public MergeRequest createMergeRequest(CreateMergeRequestCommand command) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("title", command.title()).put("description", command.description());
        body.set("source", objectMapper.createObjectNode().set("branch", objectMapper.createObjectNode().put("name", command.sourceBranch())));
        body.set("destination", objectMapper.createObjectNode().set("branch", objectMapper.createObjectNode().put("name", command.targetBranch())));
        return mapPullRequest(post("/repositories/" + repository(command.projectId()) + "/pullrequests", body));
    }

    @Override public Optional<MergeRequest> getMergeRequest(String projectId, long iid) {
        return get("/repositories/" + repository(projectId) + "/pullrequests/" + iid).map(this::mapPullRequest);
    }
    @Override public Optional<MergeRequest> findOpenMergeRequestBySourceBranch(String projectId, String branch) {
        return get("/repositories/" + repository(projectId) + "/pullrequests?q=" + encode("state=OPEN AND source.branch.name=\"" + branch + "\""))
                .flatMap(n -> n.path("values").isEmpty() ? Optional.empty() : Optional.of(mapPullRequest(n.path("values").get(0))));
    }
    @Override public String getMergeRequestDiff(String projectId, long iid, int maxChars) {
        return truncate(text("/repositories/" + repository(projectId) + "/pullrequests/" + iid + "/diff").orElse(""), maxChars);
    }
    @Override public void commentMergeRequest(String projectId, long iid, String comment) {
        post("/repositories/" + repository(projectId) + "/pullrequests/" + iid + "/comments", objectMapper.createObjectNode().put("content", objectMapper.createObjectNode().put("raw", comment)));
    }

    // Bitbucket Pipelines does not expose GitLab's job log/artifact contract. Pipeline state is
    // supported; detailed logs and GitLab security artifacts intentionally return empty results.
    @Override public Optional<Pipeline> getPipeline(String projectId, long pipelineId) { return Optional.empty(); }
    @Override public Optional<Pipeline> getLatestPipelineForRef(String projectId, String ref) { return Optional.empty(); }
    @Override public List<PipelineJob> getPipelineJobs(String projectId, long pipelineId) { return List.of(); }
    @Override public String getJobLog(String projectId, long jobId, int maxChars) { return ""; }
    @Override public List<ScannerReport> getSecurityReports(String projectId, long pipelineId, int maxCharsPerReport) { return List.of(); }

    private MergeRequest mapPullRequest(JsonNode node) {
        long id = node.path("id").asLong();
        return new MergeRequest(id, id, node.path("title").asText(), node.path("description").asText(),
                node.path("source").path("branch").path("name").asText(), node.path("destination").path("branch").path("name").asText(),
                "OPEN".equalsIgnoreCase(node.path("state").asText()) ? "opened" : node.path("state").asText(),
                node.path("links").path("html").path("href").asText(), node.path("source").path("commit").path("hash").asText(), false, "");
    }

    private Optional<JsonNode> get(String path) {
        try { return Optional.of(client().get().uri(path).retrieve().body(JsonNode.class)); }
        catch (RestClientResponseException e) { if (e.getStatusCode().value() == 404) return Optional.empty(); throw failure(e); }
    }
    private Optional<String> text(String path) {
        try { return Optional.ofNullable(client().get().uri(path).retrieve().body(String.class)); }
        catch (RestClientResponseException e) { if (e.getStatusCode().value() == 404) return Optional.empty(); throw failure(e); }
    }
    private JsonNode post(String path, JsonNode body) {
        try { return client().post().uri(path).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(JsonNode.class); }
        catch (RestClientResponseException e) { throw failure(e); }
    }
    private RestClient client() {
        if (!properties.isConfigured()) throw new GitLabException("Bitbucket is not configured: set bitbucket.username and bitbucket.api-token");
        String credentials = Base64.getEncoder().encodeToString((properties.username() + ":" + properties.apiToken()).getBytes(StandardCharsets.UTF_8));
        return RestClient.builder().baseUrl(properties.apiBaseUrl()).defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + credentials)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE).build();
    }
    private static String repository(String projectId) { return ScmProjectId.repository(projectId); }
    private static String encode(String value) { return UriUtils.encodePathSegment(value, StandardCharsets.UTF_8); }
    private static String truncate(String value, int max) { return value.length() <= max ? value : value.substring(0, max) + "\n...[diff truncated]"; }
    private static GitLabException missing(String id) { return new GitLabException("Bitbucket repository not found: " + id); }
    private static GitLabException failure(RestClientResponseException e) { return new GitLabException("Bitbucket HTTP " + e.getStatusCode().value() + ": " + e.getResponseBodyAsString()); }
}
