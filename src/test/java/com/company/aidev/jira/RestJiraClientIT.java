package com.company.aidev.jira;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.company.aidev.config.JiraProperties;
import com.company.aidev.config.RestClientConfig;
import com.company.aidev.jira.model.JiraIssue;
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
        client = new RestJiraClient(
                new RestClientConfig().jiraRestClient(properties), properties, new ObjectMapper());
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
    @DisplayName("reports a missing transition instead of failing the workflow")
    void shouldReturnFalseWhenTransitionMissing() {
        wireMock.stubFor(get(urlPathEqualTo("/rest/api/3/issue/BANK-1245/transitions"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"transitions\":[{\"id\":\"31\",\"name\":\"Close\",\"to\":{\"name\":\"Done\"}}]}")));

        assertThat(client.transitionTo("BANK-1245", "AI_READY_FOR_REVIEW")).isFalse();
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
}
