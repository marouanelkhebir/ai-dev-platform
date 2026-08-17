package com.mel.aidev.gitlab;

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

import com.mel.aidev.config.GitLabProperties;
import com.mel.aidev.gitlab.model.CreateMergeRequestCommand;
import com.mel.aidev.gitlab.model.GitLabProject;
import com.mel.aidev.gitlab.model.MergeRequest;
import com.mel.aidev.gitlab.model.PipelineStatus;
import com.mel.aidev.security.BranchPolicy;
import com.mel.aidev.settings.PlatformSettings;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Contract test of the GitLab client against a stubbed API.
 *
 * <p>The details worth pinning down here are the ones that silently break in production: URL
 * encoding of the project path, the {@code PRIVATE-TOKEN} header, 404 handling, and the fact that
 * the client refuses to open a merge request from a branch it does not own.
 */
class RestGitLabClientIT {

    private static final String PROJECT = "bank/customer-management";
    private static final String ENCODED_PROJECT = "bank%2Fcustomer-management";

    private static WireMockServer wireMock;
    private static RestGitLabClient client;
    private static GitLabProperties properties;

    @BeforeAll
    static void startServer() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();

        properties = new GitLabProperties(
                "http://localhost:" + wireMock.port(),
                "test-token",
                null,
                null,
                "ai/",
                "main",
                List.of("main", "master"),
                "AI-GENERATED",
                null,
                null,
                null,
                null);
        client = new RestGitLabClient(
                PlatformSettings.builder().gitlab(properties).build(),
                new ObjectMapper(),
                new BranchPolicy(properties));
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
    @DisplayName("encodes the project path and authenticates with PRIVATE-TOKEN")
    void shouldFetchProject() {
        wireMock.stubFor(get(urlEqualTo("/api/v4/projects/" + ENCODED_PROJECT))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(
                                """
                                {"id":12,"name":"customer-management","path_with_namespace":"bank/customer-management",
                                 "default_branch":"main","web_url":"http://gitlab/x","http_url_to_repo":"http://gitlab/x.git"}
                                """)));

        GitLabProject project = client.getProject(PROJECT);

        assertThat(project.id()).isEqualTo(12);
        assertThat(project.defaultBranch()).isEqualTo("main");
        wireMock.verify(getRequestedFor(urlEqualTo("/api/v4/projects/" + ENCODED_PROJECT))
                .withHeader("PRIVATE-TOKEN", equalTo("test-token")));
    }

    @Test
    @DisplayName("returns empty rather than failing when a file does not exist")
    void shouldReturnEmptyOnMissingFile() {
        wireMock.stubFor(get(urlPathEqualTo("/api/v4/projects/" + ENCODED_PROJECT + "/repository/files/.ai%2Farchitecture.md/raw"))
                .willReturn(aResponse().withStatus(404)));

        assertThat(client.readFile(PROJECT, "main", ".ai/architecture.md")).isEmpty();
    }

    @Test
    @DisplayName("reads a repository file")
    void shouldReadFile() {
        wireMock.stubFor(get(urlPathEqualTo("/api/v4/projects/" + ENCODED_PROJECT + "/repository/files/README.md/raw"))
                .willReturn(aResponse().withBody("# Customer management")));

        assertThat(client.readFile(PROJECT, "main", "README.md")).contains("# Customer management");
    }

    @Test
    @DisplayName("creates a merge request with the AI-GENERATED label")
    void shouldCreateMergeRequest() {
        wireMock.stubFor(post(urlEqualTo("/api/v4/projects/" + ENCODED_PROJECT + "/merge_requests"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(
                                """
                                {"id":900,"iid":42,"title":"BANK-1245 Suspend fees","description":"d",
                                 "source_branch":"ai/BANK-1245","target_branch":"main","state":"opened",
                                 "web_url":"http://gitlab/mr/42","sha":"abc"}
                                """)));

        MergeRequest mergeRequest = client.createMergeRequest(new CreateMergeRequestCommand(
                PROJECT, "ai/BANK-1245", "main", "BANK-1245 Suspend fees", "d", List.of("AI-GENERATED"), false, false));

        assertThat(mergeRequest.iid()).isEqualTo(42);
        wireMock.verify(postRequestedFor(urlEqualTo("/api/v4/projects/" + ENCODED_PROJECT + "/merge_requests")));
    }

    @Test
    @DisplayName("refuses to open a merge request from a branch outside ai/")
    void shouldRefuseNonAgentSourceBranch() {
        assertThatThrownBy(() -> client.createMergeRequest(new CreateMergeRequestCommand(
                        PROJECT, "feature/manual", "main", "t", "d", List.of(), false, false)))
                .isInstanceOf(BranchPolicy.BranchPolicyViolationException.class);

        // The call never reached GitLab.
        assertThat(wireMock.getAllServeEvents()).isEmpty();
    }

    @Test
    @DisplayName("reads a pipeline status")
    void shouldReadPipeline() {
        wireMock.stubFor(get(urlEqualTo("/api/v4/projects/" + ENCODED_PROJECT + "/pipelines/7"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":7,\"status\":\"failed\",\"ref\":\"ai/BANK-1245\",\"sha\":\"abc\",\"web_url\":\"u\"}")));

        assertThat(client.getPipeline(PROJECT, 7))
                .get()
                .extracting(p -> p.status())
                .isEqualTo(PipelineStatus.FAILED);
    }

    @Test
    @DisplayName("keeps the end of a long job log, where the failure is")
    void shouldTruncateJobLogFromTheStart() {
        String trace = "noise\n".repeat(2000) + "BUILD FAILURE: FeeTest";
        wireMock.stubFor(get(urlEqualTo("/api/v4/projects/" + ENCODED_PROJECT + "/jobs/9/trace"))
                .willReturn(aResponse().withBody(trace)));

        String log = client.getJobLog(PROJECT, 9, 200);

        assertThat(log).endsWith("BUILD FAILURE: FeeTest").contains("log truncated").hasSizeLessThan(300);
    }

    @Test
    @DisplayName("surfaces a server error instead of pretending the call worked")
    void shouldFailOnServerError() {
        wireMock.stubFor(get(urlEqualTo("/api/v4/projects/" + ENCODED_PROJECT))
                .willReturn(aResponse().withStatus(500).withBody("boom")));

        assertThatThrownBy(() -> client.getProject(PROJECT)).isInstanceOf(GitLabException.class);
    }
}
