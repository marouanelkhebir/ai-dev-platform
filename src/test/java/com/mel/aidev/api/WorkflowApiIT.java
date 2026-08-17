package com.mel.aidev.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mel.aidev.gitlab.GitLabClient;
import com.mel.aidev.gitlab.model.GitLabProject;
import com.mel.aidev.jira.JiraClient;
import com.mel.aidev.persistence.entity.ProjectEntity;
import com.mel.aidev.persistence.repository.ProjectRepository;
import com.mel.aidev.persistence.repository.WorkflowRepository;
import com.mel.aidev.sandbox.SandboxManager;
import com.mel.aidev.security.ApiKeyFilter;
import com.mel.aidev.workflow.WorkflowEngine;
import com.mel.aidev.workflow.WorkflowStatus;
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
 * Integration test of the REST API with the full Spring context.
 *
 * <p>The engine is mocked so the API contract is tested on its own: creation, deduplication,
 * validation and the API key gate. The orchestration itself is covered by {@code WorkflowEngineTest}.
 */
@SpringBootTest
@ActiveProfiles("test")
class WorkflowApiIT {

    private static final String API_KEY = "test-api-key";
    private static final UUID PROJECT_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String REPOSITORY = "bank/customer-management";
    private static final String CREATE_BODY =
            "{\"jiraTicket\":\"BANK-1245\",\"gitlabProjectId\":\"bank/customer-management\"}";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private WorkflowRepository workflowRepository;

    @Autowired
    private ProjectRepository projectRepository;

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
        // MockMvc does not run servlet filter registrations, so the API key filter is wired
        // explicitly to keep the authentication path under test.
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(new ApiKeyFilter(API_KEY))
                .build();
        workflowRepository.deleteAll();
        projectRepository.deleteAll();

        // A workflow now always belongs to a project; the legacy endpoints resolve it from the
        // repository path. Seeded through the repository so the API contract is what is under test,
        // not the validation of the project itself.
        ProjectEntity project = new ProjectEntity(PROJECT_ID, "Banque", REPOSITORY);
        project.setJiraProjectKey("BANK");
        projectRepository.save(project);

        org.mockito.Mockito.when(gitLabClient.getProject(REPOSITORY))
                .thenReturn(new GitLabProject(
                        1L, "customer-management", REPOSITORY, "main", "https://gitlab/x", "https://gitlab/x.git"));
    }

    @Test
    @DisplayName("creates a workflow and exposes it")
    void shouldCreateWorkflow() throws Exception {
        mockMvc.perform(post("/api/workflows")
                        .header("X-Api-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.jiraTicket").value("BANK-1245"))
                .andExpect(jsonPath("$.gitlabProject").value("bank/customer-management"))
                .andExpect(jsonPath("$.branch").value("ai/BANK-1245"))
                .andExpect(jsonPath("$.status").value(WorkflowStatus.CREATED.name()));

        assertThat(workflowRepository.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("creates a workflow from a direct message without a Jira ticket")
    void shouldCreateWorkflowFromMessage() throws Exception {
        mockMvc.perform(post("/api/workflows/message")
                        .header("X-Api-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Add an endpoint to suspend a customer fee.\","
                                + "\"gitlabProjectId\":\"bank/customer-management\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.jiraTicket").isEmpty())
                .andExpect(jsonPath("$.requestId").value(org.hamcrest.Matchers.startsWith("MSG-")))
                .andExpect(jsonPath("$.sourceMessage").value("Add an endpoint to suspend a customer fee."))
                .andExpect(jsonPath("$.branch").value(org.hamcrest.Matchers.startsWith("ai/message/MSG-")))
                .andExpect(jsonPath("$.status").value(WorkflowStatus.CREATED.name()));

        assertThat(workflowRepository.findAll()).hasSize(1);
        assertThat(workflowRepository.findAll().get(0).isJiraBacked()).isFalse();
    }

    @Test
    @DisplayName("rejects an invalid Jira key instead of starting a workflow")
    void shouldRejectInvalidTicketKey() throws Exception {
        mockMvc.perform(post("/api/workflows")
                        .header("X-Api-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jiraTicket\":\"not-a-key\",\"gitlabProjectId\":\"g/p\"}"))
                .andExpect(status().isBadRequest());

        assertThat(workflowRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("does not open a second workflow for a ticket that already has an active one")
    void shouldNotDuplicateWorkflowForTicket() throws Exception {
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/api/workflows")
                            .header("X-Api-Key", API_KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(CREATE_BODY))
                    .andExpect(status().isCreated());
        }

        assertThat(workflowRepository.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("exposes the audit view of a workflow")
    void shouldExposeAuditView() throws Exception {
        String response = mockMvc.perform(post("/api/workflows")
                        .header("X-Api-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String id = response.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(get("/api/workflows/{id}", id).header("X-Api-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflow.jiraTicket").value("BANK-1245"))
                .andExpect(jsonPath("$.steps").isArray())
                .andExpect(jsonPath("$.agentExecutions").isArray());
    }

    @Test
    @DisplayName("cancels a running workflow")
    void shouldCancelWorkflow() throws Exception {
        String response = mockMvc.perform(post("/api/workflows")
                        .header("X-Api-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String id = response.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(post("/api/workflows/{id}/cancel", id).header("X-Api-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(WorkflowStatus.CANCELLED.name()));
    }

    @Test
    @DisplayName("stores a clarification and restarts analysis for a blocked workflow")
    void shouldClarifyBlockedWorkflow() throws Exception {
        var workflow = workflowRepository.save(new com.mel.aidev.persistence.entity.WorkflowEntity(
                UUID.randomUUID(), PROJECT_ID, "BANK-1245", "bank/customer-management", "main"));
        workflow.setStatus(WorkflowStatus.NEEDS_CLARIFICATION);
        workflowRepository.save(workflow);

        mockMvc.perform(post("/api/workflows/{id}/clarification", workflow.getId())
                        .header("X-Api-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clarification\":\"The page title must be Jean Brun Immobilier.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(WorkflowStatus.ANALYZING_JIRA.name()))
                .andExpect(jsonPath("$.humanClarification").value("The page title must be Jean Brun Immobilier."));

        var saved = workflowRepository.findById(workflow.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(WorkflowStatus.ANALYZING_JIRA);
        assertThat(saved.getHumanClarification()).isEqualTo("The page title must be Jean Brun Immobilier.");
    }

    @Test
    @DisplayName("refuses clarification for a workflow that is not blocked")
    void shouldRefuseClarificationForRunningWorkflow() throws Exception {
        var workflow = workflowRepository.save(new com.mel.aidev.persistence.entity.WorkflowEntity(
                UUID.randomUUID(), PROJECT_ID, "BANK-1245", "bank/customer-management", "main"));

        mockMvc.perform(post("/api/workflows/{id}/clarification", workflow.getId())
                        .header("X-Api-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clarification\":\"A useful detail.\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("refuses to approve a workflow that is not waiting for a human")
    void shouldRefusePrematureApproval() throws Exception {
        String response = mockMvc.perform(post("/api/workflows")
                        .header("X-Api-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String id = response.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(post("/api/workflows/{id}/approve", id)
                        .header("X-Api-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approver\":\"alice\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("returns 404 for an unknown workflow")
    void shouldReturnNotFound() throws Exception {
        mockMvc.perform(get("/api/workflows/{id}", UUID.randomUUID()).header("X-Api-Key", API_KEY))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("refuses a call without the API key")
    void shouldRejectMissingApiKey() throws Exception {
        mockMvc.perform(get("/api/workflows")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("refuses a call with a wrong API key")
    void shouldRejectWrongApiKey() throws Exception {
        mockMvc.perform(get("/api/workflows").header("X-Api-Key", "wrong")).andExpect(status().isUnauthorized());
    }
}
