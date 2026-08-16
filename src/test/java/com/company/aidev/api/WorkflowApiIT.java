package com.company.aidev.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.aidev.gitlab.GitLabClient;
import com.company.aidev.jira.JiraClient;
import com.company.aidev.persistence.repository.WorkflowRepository;
import com.company.aidev.sandbox.SandboxManager;
import com.company.aidev.security.ApiKeyFilter;
import com.company.aidev.workflow.WorkflowEngine;
import com.company.aidev.workflow.WorkflowStatus;
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
    private static final String CREATE_BODY =
            "{\"jiraTicket\":\"BANK-1245\",\"gitlabProjectId\":\"bank/customer-management\"}";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private WorkflowRepository workflowRepository;

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
