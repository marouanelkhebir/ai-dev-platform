package com.company.aidev.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.aidev.gitlab.GitLabClient;
import com.company.aidev.gitlab.model.Pipeline;
import com.company.aidev.gitlab.model.PipelineStatus;
import com.company.aidev.jira.JiraClient;
import com.company.aidev.persistence.entity.WorkflowEntity;
import com.company.aidev.persistence.repository.WebhookEventRepository;
import com.company.aidev.persistence.repository.WorkflowRepository;
import com.company.aidev.sandbox.SandboxManager;
import com.company.aidev.workflow.WorkflowEngine;
import com.company.aidev.workflow.WorkflowStatus;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Integration test of the Jira and GitLab webhooks.
 *
 * <p>The properties under test are the ones that decide whether a real merge request gets opened:
 * authentication, opt-in, and idempotency on retried deliveries.
 */
@SpringBootTest
@ActiveProfiles("test")
class WebhookIT {

    private static final String JIRA_SECRET = "test-jira-webhook-secret";
    private static final String GITLAB_SECRET = "test-gitlab-webhook-secret";
    private static final String PROJECT = "bank/customer-management";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private WorkflowRepository workflowRepository;

    @Autowired
    private WebhookEventRepository webhookEventRepository;

    @MockBean
    private WorkflowEngine workflowEngine;

    @MockBean
    private JiraClient jiraClient;

    @MockBean
    private GitLabClient gitLabClient;

    @MockBean
    private SandboxManager sandboxManager;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        workflowRepository.deleteAll();
        webhookEventRepository.deleteAll();
    }

    // ------------------------------------------------------------------ Jira

    @Test
    @DisplayName("a labelled ticket starts a workflow")
    void shouldStartWorkflowOnAgentReadyLabel() throws Exception {
        mockMvc.perform(post("/webhooks/jira")
                        .header("X-Webhook-Token", JIRA_SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jiraPayload("BANK-1245", "agent-ready", "gitlab-project:" + PROJECT, 1)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("accepted"));

        assertThat(workflowRepository.findAll()).hasSize(1);
        assertThat(workflowRepository.findAll().get(0).getGitlabProject()).isEqualTo(PROJECT);
    }

    @Test
    @DisplayName("a ticket without the trigger label is ignored")
    void shouldIgnoreTicketWithoutLabel() throws Exception {
        mockMvc.perform(post("/webhooks/jira")
                        .header("X-Webhook-Token", JIRA_SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jiraPayload("BANK-1246", "some-other-label", "gitlab-project:" + PROJECT, 2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ignored"));

        assertThat(workflowRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("a labelled ticket without a project label is not started")
    void shouldRefuseWithoutProjectLabel() throws Exception {
        mockMvc.perform(post("/webhooks/jira")
                        .header("X-Webhook-Token", JIRA_SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jiraPayload("BANK-1247", "agent-ready", "team-payments", 3)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ignored"));

        assertThat(workflowRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("a redelivered Jira webhook does not start a second workflow")
    void shouldBeIdempotent() throws Exception {
        String payload = jiraPayload("BANK-1248", "agent-ready", "gitlab-project:" + PROJECT, 4);

        mockMvc.perform(post("/webhooks/jira")
                        .header("X-Webhook-Token", JIRA_SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isAccepted());
        mockMvc.perform(post("/webhooks/jira")
                        .header("X-Webhook-Token", JIRA_SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("duplicate"));

        assertThat(workflowRepository.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("a Jira webhook with a wrong token is rejected")
    void shouldRejectBadJiraToken() throws Exception {
        mockMvc.perform(post("/webhooks/jira")
                        .header("X-Webhook-Token", "wrong")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jiraPayload("BANK-1249", "agent-ready", "gitlab-project:" + PROJECT, 5)))
                .andExpect(status().isUnauthorized());

        assertThat(workflowRepository.findAll()).isEmpty();
    }

    // ---------------------------------------------------------------- GitLab

    @Test
    @DisplayName("a finished pipeline on an ai/ branch resumes the workflow")
    void shouldResumeOnPipelineEvent() throws Exception {
        WorkflowEntity workflow = givenWorkflowWaitingForPipeline();
        when(gitLabClient.getPipeline(anyString(), anyLong()))
                .thenReturn(Optional.of(new Pipeline(77L, PipelineStatus.SUCCESS, "ai/BANK-1250", "sha", "url")));

        mockMvc.perform(post("/webhooks/gitlab")
                        .header("X-Gitlab-Token", GITLAB_SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pipelinePayload(77L, "success", "ai/BANK-1250")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("resumed"));

        verify(workflowEngine).onPipelineFinished(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        assertThat(workflowRepository.findById(workflow.getId())).isPresent();
    }

    @Test
    @DisplayName("a pipeline on a branch the platform does not own is ignored")
    void shouldIgnoreForeignBranch() throws Exception {
        mockMvc.perform(post("/webhooks/gitlab")
                        .header("X-Gitlab-Token", GITLAB_SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pipelinePayload(78L, "success", "feature/manual-work")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ignored"));

        verify(workflowEngine, never())
                .onPipelineFinished(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("a redelivered pipeline event is processed once")
    void shouldDeduplicatePipelineEvents() throws Exception {
        givenWorkflowWaitingForPipeline();
        when(gitLabClient.getPipeline(anyString(), anyLong()))
                .thenReturn(Optional.of(new Pipeline(79L, PipelineStatus.SUCCESS, "ai/BANK-1250", "sha", "url")));
        String payload = pipelinePayload(79L, "success", "ai/BANK-1250");

        mockMvc.perform(post("/webhooks/gitlab")
                        .header("X-Gitlab-Token", GITLAB_SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk());
        mockMvc.perform(post("/webhooks/gitlab")
                        .header("X-Gitlab-Token", GITLAB_SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(jsonPath("$.status").value("duplicate"));

        verify(workflowEngine)
                .onPipelineFinished(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("a GitLab webhook with a wrong token is rejected")
    void shouldRejectBadGitLabToken() throws Exception {
        mockMvc.perform(post("/webhooks/gitlab")
                        .header("X-Gitlab-Token", "wrong")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pipelinePayload(80L, "success", "ai/BANK-1250")))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------- fixtures

    private WorkflowEntity givenWorkflowWaitingForPipeline() {
        WorkflowEntity workflow = new WorkflowEntity(UUID.randomUUID(), "BANK-1250", PROJECT, "main");
        workflow.setBranch("ai/BANK-1250");
        workflow.setStatus(WorkflowStatus.WAITING_PIPELINE);
        return workflowRepository.save(workflow);
    }

    private static String jiraPayload(String key, String label, String secondLabel, long timestamp) {
        return """
                {
                  "webhookEvent": "jira:issue_updated",
                  "timestamp": %d,
                  "issue": {
                    "key": "%s",
                    "fields": {
                      "summary": "Suspend fees",
                      "labels": ["%s", "%s"],
                      "status": {"name": "In Progress"}
                    }
                  }
                }
                """
                .formatted(timestamp, key, label, secondLabel);
    }

    private static String pipelinePayload(long pipelineId, String status, String ref) {
        return """
                {
                  "object_kind": "pipeline",
                  "object_attributes": {"id": %d, "status": "%s", "ref": "%s"},
                  "project": {"path_with_namespace": "%s"}
                }
                """
                .formatted(pipelineId, status, ref, PROJECT);
    }
}
