package com.mel.aidev.github;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.mel.aidev.config.GitHubProperties;
import com.mel.aidev.config.GitLabProperties;
import com.mel.aidev.gitlab.GitLabException;
import com.mel.aidev.gitlab.ScmProjectId;
import com.mel.aidev.gitlab.model.CreateMergeRequestCommand;
import com.mel.aidev.gitlab.model.GitLabProject;
import com.mel.aidev.gitlab.model.MergeRequest;
import com.mel.aidev.gitlab.model.PipelineStatus;
import com.mel.aidev.security.BranchPolicy;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Contract test of the GitHub client against a stubbed API.
 *
 * <p>What is pinned here is the translation: GitHub speaks pull requests, numbers and Actions runs,
 * while everything above this class speaks merge requests, iids and pipelines. A mapping mistake in
 * that seam is invisible until a workflow reports a failed build as green.
 */
class GitHubClientIT {

    private static final String PROJECT = ScmProjectId.github("bank/customer-management");
    private static final String REPO = "/repos/bank/customer-management";

    private static WireMockServer wireMock;
    private static GitHubClient client;

    @BeforeAll
    static void startServer() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();

        GitLabProperties gitlab = new GitLabProperties(
                "http://localhost:1", "t", null, null, "ai/", "main", List.of("main", "master"), "AI-GENERATED",
                null, null, null, null);
        client = new GitHubClient(
                new GitHubProperties("http://localhost:" + wireMock.port(), "test-token", null, null),
                new ObjectMapper(),
                new BranchPolicy(gitlab));
    }

    @AfterAll
    static void stopServer() {
        wireMock.stop();
    }

    @BeforeEach
    void reset() {
        wireMock.resetAll();
    }

    @Test
    @DisplayName("authenticates with a bearer token and keeps the provider-qualified identifier")
    void shouldFetchRepository() {
        wireMock.stubFor(get(urlEqualTo(REPO))
                .willReturn(json(
                        """
                        {"id":12,"name":"customer-management","full_name":"bank/customer-management",
                         "default_branch":"main","html_url":"https://github.com/bank/customer-management",
                         "clone_url":"https://github.com/bank/customer-management.git"}
                        """)));

        GitLabProject project = client.getProject(PROJECT);

        assertThat(project.defaultBranch()).isEqualTo("main");
        assertThat(project.httpUrlToRepo()).isEqualTo("https://github.com/bank/customer-management.git");
        // The engine feeds this value straight back into the contract, so it must keep its marker.
        assertThat(project.pathWithNamespace()).isEqualTo(PROJECT);
        wireMock.verify(getRequestedFor(urlEqualTo(REPO))
                .withHeader("Authorization", equalTo("Bearer test-token"))
                .withHeader("X-GitHub-Api-Version", equalTo("2022-11-28")));
    }

    @Test
    @DisplayName("refuses an identifier that is not owner/repository rather than calling a wrong endpoint")
    void shouldRejectMalformedRepository() {
        assertThatThrownBy(() -> client.getProject(ScmProjectId.github("customer-management")))
                .isInstanceOf(GitLabException.class)
                .hasMessageContaining("owner/repository");
        assertThat(wireMock.getAllServeEvents()).isEmpty();
    }

    @Test
    @DisplayName("returns empty rather than failing when a file does not exist")
    void shouldReturnEmptyOnMissingFile() {
        wireMock.stubFor(get(urlPathEqualTo(REPO + "/contents/.ai/architecture.md"))
                .willReturn(aResponse().withStatus(404)));

        assertThat(client.readFile(PROJECT, "main", ".ai/architecture.md")).isEmpty();
    }

    @Test
    @DisplayName("reads a file as raw content on the requested ref")
    void shouldReadFile() {
        wireMock.stubFor(get(urlEqualTo(REPO + "/contents/README.md?ref=ai/BANK-1245"))
                .willReturn(aResponse().withBody("# Customer management")));

        assertThat(client.readFile(PROJECT, "ai/BANK-1245", "README.md")).contains("# Customer management");
        wireMock.verify(getRequestedFor(urlEqualTo(REPO + "/contents/README.md?ref=ai/BANK-1245"))
                .withHeader("Accept", equalTo("application/vnd.github.raw+json")));
    }

    @Test
    @DisplayName("maps a created pull request onto the merge request vocabulary and labels it")
    void shouldCreatePullRequest() {
        wireMock.stubFor(post(urlEqualTo(REPO + "/pulls"))
                .willReturn(json(
                        """
                        {"id":900,"number":42,"title":"BANK-1245 Suspend fees","body":"d","state":"open",
                         "head":{"ref":"ai/BANK-1245","sha":"abc"},"base":{"ref":"main"},
                         "html_url":"https://github.com/bank/customer-management/pull/42","mergeable":true,
                         "mergeable_state":"clean"}
                        """)));
        wireMock.stubFor(post(urlEqualTo(REPO + "/issues/42/labels")).willReturn(json("[]")));

        MergeRequest pullRequest = client.createMergeRequest(new CreateMergeRequestCommand(
                PROJECT, "ai/BANK-1245", "main", "BANK-1245 Suspend fees", "d", List.of("AI-GENERATED"), false, false));

        assertThat(pullRequest.iid()).isEqualTo(42);
        assertThat(pullRequest.isOpen()).isTrue();
        assertThat(pullRequest.sha()).isEqualTo("abc");
        assertThat(pullRequest.hasConflicts()).isFalse();
        wireMock.verify(postRequestedFor(urlEqualTo(REPO + "/issues/42/labels")));
    }

    @Test
    @DisplayName("reuses the open pull request when GitHub rejects a duplicate")
    void shouldReuseExistingPullRequest() {
        wireMock.stubFor(post(urlEqualTo(REPO + "/pulls"))
                .willReturn(aResponse().withStatus(422).withBody("{\"message\":\"A pull request already exists\"}")));
        wireMock.stubFor(get(urlEqualTo(REPO + "/pulls?state=open&head=bank:ai/BANK-1245"))
                .willReturn(json(
                        """
                        [{"id":900,"number":42,"title":"t","body":"d","state":"open",
                          "head":{"ref":"ai/BANK-1245","sha":"abc"},"base":{"ref":"main"},"html_url":"u"}]
                        """)));

        MergeRequest pullRequest = client.createMergeRequest(new CreateMergeRequestCommand(
                PROJECT, "ai/BANK-1245", "main", "t", "d", List.of(), false, false));

        assertThat(pullRequest.iid()).isEqualTo(42);
    }

    @Test
    @DisplayName("refuses to open a pull request from a branch outside ai/")
    void shouldRefuseNonAgentSourceBranch() {
        assertThatThrownBy(() -> client.createMergeRequest(new CreateMergeRequestCommand(
                        PROJECT, "feature/manual", "main", "t", "d", List.of(), false, false)))
                .isInstanceOf(BranchPolicy.BranchPolicyViolationException.class);

        assertThat(wireMock.getAllServeEvents()).isEmpty();
    }

    @Test
    @DisplayName("reports a merged pull request as merged, not as merely closed")
    void shouldMapMergedState() {
        wireMock.stubFor(get(urlEqualTo(REPO + "/pulls/42"))
                .willReturn(json(
                        """
                        {"id":900,"number":42,"state":"closed","merged":true,"merged_at":"2026-01-05T10:00:00Z",
                         "head":{"ref":"ai/BANK-1245","sha":"abc"},"base":{"ref":"main"},"html_url":"u"}
                        """)));

        assertThat(client.getMergeRequest(PROJECT, 42)).get().extracting(MergeRequest::state).isEqualTo("merged");
    }

    @Test
    @DisplayName("a finished Actions run is read from its conclusion, not from its status")
    void shouldMapRunConclusion() {
        wireMock.stubFor(get(urlEqualTo(REPO + "/actions/runs/7"))
                .willReturn(json(
                        """
                        {"id":7,"status":"completed","conclusion":"failure","head_branch":"ai/BANK-1245",
                         "head_sha":"abc","html_url":"u"}
                        """)));

        assertThat(client.getPipeline(PROJECT, 7)).get().extracting(p -> p.status()).isEqualTo(PipelineStatus.FAILED);
    }

    @Test
    @DisplayName("a running Actions run is not mistaken for a finished one")
    void shouldMapRunningRun() {
        wireMock.stubFor(get(urlEqualTo(REPO + "/actions/runs?per_page=1&branch=ai/BANK-1245"))
                .willReturn(json(
                        """
                        {"workflow_runs":[{"id":7,"status":"in_progress","conclusion":null,
                         "head_branch":"ai/BANK-1245","head_sha":"abc","html_url":"u"}]}
                        """)));

        assertThat(client.getLatestPipelineForRef(PROJECT, "ai/BANK-1245"))
                .get()
                .extracting(p -> p.status())
                .isEqualTo(PipelineStatus.RUNNING);
    }

    @Test
    @DisplayName("follows the signed log redirect without leaking the API token to storage")
    void shouldFollowLogRedirectWithoutCredentials() {
        String trace = "noise\n".repeat(2000) + "BUILD FAILURE: FeeTest";
        wireMock.stubFor(get(urlEqualTo(REPO + "/actions/jobs/9/logs"))
                .willReturn(aResponse().withStatus(302).withHeader("Location", wireMock.baseUrl() + "/signed-logs")));
        wireMock.stubFor(get(urlEqualTo("/signed-logs")).willReturn(aResponse().withBody(trace)));

        String log = client.getJobLog(PROJECT, 9, 200);

        assertThat(log).endsWith("BUILD FAILURE: FeeTest").contains("log truncated").hasSizeLessThan(300);
        // Pre-signed storage URLs reject a request that also carries an Authorization header.
        wireMock.verify(getRequestedFor(urlEqualTo("/signed-logs")).withoutHeader("Authorization"));
    }

    @Test
    @DisplayName("surfaces a server error instead of pretending the call worked")
    void shouldFailOnServerError() {
        wireMock.stubFor(get(urlEqualTo(REPO)).willReturn(aResponse().withStatus(500).withBody("boom")));

        assertThatThrownBy(() -> client.getProject(PROJECT)).isInstanceOf(GitLabException.class);
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(String body) {
        return aResponse().withHeader("Content-Type", "application/json").withBody(body);
    }
}
