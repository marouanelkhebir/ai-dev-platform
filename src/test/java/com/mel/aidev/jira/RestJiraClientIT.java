package com.mel.aidev.jira;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mel.aidev.config.JiraProperties;
import com.mel.aidev.jira.model.JiraIssue;
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
 * Contract test of the Jira client against a stubbed API.
 *
 * <p>The interesting cases are the ones that quietly produce a wrong analysis rather than an error:
 * an ADF description flattened badly, or acceptance criteria that live in the description because
 * nobody filled the custom field.
 */
class RestJiraClientIT {

    private static WireMockServer wireMock;
    private static RestJiraClient client;

    @BeforeAll
    static void startServer() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();

        JiraProperties properties = new JiraProperties(
                "http://localhost:" + wireMock.port(),
                "bot@company.test",
                "test-token",
                "3",
                null,
                null,
                null,
                null,
                null,
                List.of("customfield_10100"));
        // The client reads its settings at the point of use, so the test hands it a fixed snapshot
        // rather than a pre-built HTTP client.
        client = new RestJiraClient(
                PlatformSettings.builder().jira(properties).build(), new ObjectMapper());
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
    @DisplayName("maps an issue with an ADF description and a custom acceptance criteria field")
    void shouldMapCloudIssue() {
        wireMock.stubFor(get(urlPathEqualTo("/rest/api/3/issue/BANK-1245"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(
                                """
                                {
                                  "key": "BANK-1245",
                                  "fields": {
                                    "summary": "Suspend fees for fragile customers",
                                    "description": {
                                      "type": "doc", "version": 1,
                                      "content": [
                                        {"type":"paragraph","content":[{"type":"text","text":"When a customer becomes fragile."}]},
                                        {"type":"bulletList","content":[
                                          {"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"Suspend the active fee"}]}]}
                                        ]}
                                      ]
                                    },
                                    "customfield_10100": "AC1: The active fee must be suspended\\nAC2: The customer is notified",
                                    "labels": ["agent-ready"],
                                    "priority": {"name": "High"},
                                    "status": {"name": "READY_FOR_AI"},
                                    "issuetype": {"name": "Story"},
                                    "issuelinks": [
                                      {"type":{"name":"blocks"},"inwardIssue":{"key":"BANK-1200","fields":{"summary":"Fragility flag","status":{"name":"Done"}}}}
                                    ],
                                    "comment": {"comments": [
                                      {"id":"1","author":{"displayName":"Alice"},
                                       "body":{"type":"doc","version":1,"content":[{"type":"paragraph","content":[{"type":"text","text":"Only monthly fees."}]}]},
                                       "created":"2026-01-15T10:30:00.000+0000"}
                                    ]}
                                  }
                                }
                                """)));

        JiraIssue issue = client.getIssue("BANK-1245");

        assertThat(issue.key()).isEqualTo("BANK-1245");
        assertThat(issue.description()).contains("When a customer becomes fragile", "- Suspend the active fee");
        assertThat(issue.acceptanceCriteria())
                .containsExactly("The active fee must be suspended", "The customer is notified");
        assertThat(issue.labels()).containsExactly("agent-ready");
        assertThat(issue.links()).hasSize(1);
        assertThat(issue.links().get(0).issueKey()).isEqualTo("BANK-1200");
        assertThat(issue.comments()).hasSize(1);
        assertThat(issue.comments().get(0).body()).isEqualTo("Only monthly fees.");
        // The Jira timestamp format is not ISO-8601 and must still be parsed.
        assertThat(issue.comments().get(0).created()).isNotNull();

        wireMock.verify(getRequestedFor(urlPathEqualTo("/rest/api/3/issue/BANK-1245"))
                .withHeader("Authorization", containing("Basic ")));
    }

    @Test
    @DisplayName("falls back to the description when the acceptance criteria field is empty")
    void shouldParseCriteriaFromDescription() {
        wireMock.stubFor(get(urlPathEqualTo("/rest/api/3/issue/BANK-1246"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(
                                """
                                {
                                  "key": "BANK-1246",
                                  "fields": {
                                    "summary": "s",
                                    "description": "Context here.\\n\\n## Acceptance criteria\\n- The fee is suspended\\n- The customer is notified\\n\\n## Notes\\nnothing",
                                    "labels": [],
                                    "priority": {"name":"Low"},
                                    "status": {"name":"Open"},
                                    "issuetype": {"name":"Bug"},
                                    "comment": {"comments": []}
                                  }
                                }
                                """)));

        JiraIssue issue = client.getIssue("BANK-1246");

        assertThat(issue.acceptanceCriteria())
                .containsExactly("The fee is suspended", "The customer is notified");
    }

    @Test
    @DisplayName("posts a comment as an ADF document")
    void shouldPostAdfComment() {
        wireMock.stubFor(post(urlEqualTo("/rest/api/3/issue/BANK-1245/comment"))
                .willReturn(aResponse().withStatus(201).withBody("{}")));

        client.addComment("BANK-1245", "Merge request opened.");

        wireMock.verify(postRequestedFor(urlEqualTo("/rest/api/3/issue/BANK-1245/comment"))
                .withRequestBody(containing("\"type\":\"doc\""))
                .withRequestBody(containing("Merge request opened.")));
    }

    @Test
    @DisplayName("adds a label without replacing the issue labels")
    void shouldAddLabel() {
        wireMock.stubFor(put(urlEqualTo("/rest/api/3/issue/BANK-1245"))
                .willReturn(aResponse().withStatus(204)));

        client.addLabel("BANK-1245", "traitee-par-ia");

        wireMock.verify(putRequestedFor(urlEqualTo("/rest/api/3/issue/BANK-1245"))
                .withRequestBody(containing("\"update\""))
                .withRequestBody(containing("\"add\":\"traitee-par-ia\"")));
    }

    @Test
    @DisplayName("moves the issue through the transition matching the target status")
    void shouldTransitionByTargetStatus() {
        wireMock.stubFor(get(urlPathEqualTo("/rest/api/3/issue/BANK-1245/transitions"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(
                                """
                                {"transitions":[
                                  {"id":"11","name":"Start AI","to":{"name":"AI_IN_PROGRESS"}},
                                  {"id":"21","name":"Need info","to":{"name":"AI_NEEDS_CLARIFICATION"}}
                                ]}
                                """)));
        wireMock.stubFor(post(urlEqualTo("/rest/api/3/issue/BANK-1245/transitions"))
                .willReturn(aResponse().withStatus(204)));

        assertThat(client.transitionTo("BANK-1245", "AI_IN_PROGRESS")).isTrue();

        wireMock.verify(postRequestedFor(urlEqualTo("/rest/api/3/issue/BANK-1245/transitions"))
                .withRequestBody(containing("\"id\":\"11\"")));
    }

    @Test
    @DisplayName("uses an available Jira review status when the configured AI status does not exist")
    void shouldUseAvailableReviewStatus() {
        wireMock.stubFor(get(urlPathEqualTo("/rest/api/3/issue/BANK-1245/transitions"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(
                                """
                                {"transitions":[
                                  {"id":"11","name":"Démarrer","to":{"name":"En cours"}},
                                  {"id":"21","name":"Demander une revue","to":{"name":"Revue en cours"}},
                                  {"id":"31","name":"Terminer","to":{"name":"Terminé"}}
                                ]}
                                """)));
        wireMock.stubFor(post(urlEqualTo("/rest/api/3/issue/BANK-1245/transitions"))
                .willReturn(aResponse().withStatus(204)));

        assertThat(client.transitionTo("BANK-1245", "AI_READY_FOR_REVIEW")).isTrue();

        wireMock.verify(postRequestedFor(urlEqualTo("/rest/api/3/issue/BANK-1245/transitions"))
                .withRequestBody(containing("\"id\":\"21\"")));
    }

    @Test
    @DisplayName("reports a missing transition instead of failing the workflow")
    void shouldReturnFalseWhenTransitionMissing() {
        wireMock.stubFor(get(urlPathEqualTo("/rest/api/3/issue/BANK-1245/transitions"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"transitions\":[{\"id\":\"31\",\"name\":\"Close\",\"to\":{\"name\":\"Done\"}}]}")));

        assertThat(client.transitionTo("BANK-1245", "AI_READY_FOR_REVIEW")).isFalse();
    }

    /**
     * The reason the client is built per call rather than injected as a singleton: Jira is almost
     * always configured from the settings screen after the first boot. A client born at startup
     * would keep calling the bootstrap URL with an obsolete token while the screen shows the new
     * values — and the symptom, a 404 on an existing project, points nowhere near the cause.
     */
    @Test
    @DisplayName("follows a settings change without a restart")
    void shouldFollowSettingsChange() {
        MutableSettings settings = new MutableSettings(jiraProperties("http://localhost:1", "old-token"));
        RestJiraClient followingClient = new RestJiraClient(settings, new ObjectMapper());

        settings.jira = jiraProperties("http://localhost:" + wireMock.port(), "new-token");
        wireMock.stubFor(get(urlPathEqualTo("/rest/api/3/project/BANK")).willReturn(aResponse().withStatus(200)));

        assertThat(followingClient.projectExists("BANK")).isTrue();

        String expected = "Basic "
                + java.util.Base64.getEncoder()
                        .encodeToString("bot@company.test:new-token".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        wireMock.verify(getRequestedFor(urlPathEqualTo("/rest/api/3/project/BANK"))
                .withHeader("Authorization", equalTo(expected)));
    }

    /** Jira answers 404 both for a missing project and for one the account may not browse. */
    @Test
    @DisplayName("reports a project the account cannot see as absent")
    void shouldReportUnknownProjectAsAbsent() {
        wireMock.stubFor(get(urlPathEqualTo("/rest/api/3/project/GHOST")).willReturn(aResponse().withStatus(404)));

        assertThat(client.projectExists("GHOST")).isFalse();
    }

    /** An unconfigured Jira must say so, not fire a request at a fallback host. */
    @Test
    @DisplayName("refuses to call anything when Jira is not configured")
    void shouldRefuseWhenNotConfigured() {
        RestJiraClient unconfigured = new RestJiraClient(
                new MutableSettings(jiraProperties(null, null)), new ObjectMapper());

        assertThatThrownBy(() -> unconfigured.projectExists("BANK"))
                .isInstanceOf(JiraException.class)
                .hasMessageContaining("not configured");
    }

    @Test
    @DisplayName("uses Basic authentication built from the email and the token")
    void shouldSendBasicAuth() {
        wireMock.stubFor(get(urlPathEqualTo("/rest/api/3/issue/BANK-1"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"key\":\"BANK-1\",\"fields\":{\"comment\":{\"comments\":[]}}}")));

        client.getIssue("BANK-1");

        String expected = "Basic "
                + java.util.Base64.getEncoder()
                        .encodeToString("bot@company.test:test-token".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        wireMock.verify(getRequestedFor(urlPathEqualTo("/rest/api/3/issue/BANK-1"))
                .withHeader("Authorization", equalTo(expected)));
    }

    private static JiraProperties jiraProperties(String baseUrl, String token) {
        return new JiraProperties(
                baseUrl, "bot@company.test", token, "3", null, null, null, null, null, List.of("customfield_10100"));
    }

    /** Settings whose Jira section can change between two calls, like the settings screen does. */
    private static final class MutableSettings implements PlatformSettings {

        private JiraProperties jira;

        private MutableSettings(JiraProperties jira) {
            this.jira = jira;
        }

        @Override
        public JiraProperties jira() {
            return jira;
        }

        @Override
        public com.mel.aidev.config.GitLabProperties gitlab() {
            return PlatformSettings.builder().build().gitlab();
        }

        @Override
        public com.mel.aidev.config.AiProperties ai() {
            return PlatformSettings.builder().build().ai();
        }

        @Override
        public com.mel.aidev.config.WorkflowProperties workflow() {
            return PlatformSettings.builder().build().workflow();
        }

        @Override
        public com.mel.aidev.config.SandboxProperties sandbox() {
            return PlatformSettings.builder().build().sandbox();
        }

        @Override
        public com.mel.aidev.config.PlatformProperties platform() {
            return PlatformSettings.builder().build().platform();
        }

        @Override
        public long version() {
            return 0L;
        }
    }
}
