package com.company.aidev.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

import com.company.aidev.gitlab.GitLabClient;
import com.company.aidev.jira.JiraClient;
import com.company.aidev.persistence.entity.WorkflowEntity;
import com.company.aidev.persistence.repository.WorkflowRepository;
import com.company.aidev.persistence.repository.WorkflowStepRepository;
import com.company.aidev.sandbox.SandboxManager;
import com.company.aidev.security.ApiKeyFilter;
import com.company.aidev.workflow.DevelopmentWorkflowService;
import com.company.aidev.workflow.WorkflowEngine;
import com.company.aidev.workflow.WorkflowStateStore;
import com.company.aidev.workflow.WorkflowStatus;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Verifies that a console following a workflow is pushed its changes.
 *
 * <p>The console is the only place an operator can see a step that takes minutes actually moving, so
 * the fan-out is tested against the real HTTP endpoint rather than against the broker in isolation.
 */
@SpringBootTest
@ActiveProfiles("test")
class WorkflowEventStreamIT {

    private static final String API_KEY = "test-api-key";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private WorkflowRepository workflowRepository;

    @Autowired
    private WorkflowStepRepository stepRepository;

    @Autowired
    private DevelopmentWorkflowService workflowService;

    @Autowired
    private WorkflowStateStore stateStore;

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
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(new ApiKeyFilter(API_KEY))
                .build();
        stepRepository.deleteAll();
        workflowRepository.deleteAll();
    }

    @Test
    @DisplayName("pushes a status change to the console following that workflow")
    void shouldPushStatusChange() throws Exception {
        WorkflowEntity workflow = givenWorkflow("BANK-1245");

        MvcResult stream = openStream(workflow.getId());
        assertThat(stream.getResponse().getContentAsString()).contains("event:ready");

        workflowService.cancel(workflow.getId());

        assertThat(stream.getResponse().getContentAsString())
                .contains("event:workflow")
                .contains(WorkflowStatus.CANCELLED.name());
    }

    @Test
    @DisplayName("pushes a step as soon as it starts, before it has a duration")
    void shouldPushStepStartAndCompletion() throws Exception {
        WorkflowEntity workflow = givenWorkflow("BANK-1246");
        MvcResult stream = openStream(workflow.getId());

        var step = stateStore.beginStep(workflow.getId(), WorkflowStatus.ANALYZING_JIRA);
        assertThat(stream.getResponse().getContentAsString())
                .contains("event:step")
                .contains("\"from\":\"ANALYZING_JIRA\"");

        stateStore.completeStep(step, WorkflowStatus.PLANNING, true, "3 acceptance criteria identified", null);
        assertThat(stream.getResponse().getContentAsString())
                .contains("\"to\":\"PLANNING\"")
                .contains("3 acceptance criteria identified");
    }

    @Test
    @DisplayName("does not leak a workflow into the stream of another one")
    void shouldNotLeakAcrossWorkflows() throws Exception {
        WorkflowEntity watched = givenWorkflow("BANK-1247");
        WorkflowEntity other = givenWorkflow("BANK-1248");

        MvcResult stream = openStream(watched.getId());
        workflowService.cancel(other.getId());

        assertThat(stream.getResponse().getContentAsString()).doesNotContain(WorkflowStatus.CANCELLED.name());
    }

    @Test
    @DisplayName("refuses a stream without the platform API key")
    void shouldRequireApiKey() throws Exception {
        WorkflowEntity workflow = givenWorkflow("BANK-1249");

        mockMvc.perform(get("/api/workflows/{id}/events", workflow.getId()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
    }

    private WorkflowEntity givenWorkflow(String ticket) {
        return workflowRepository.save(
                new WorkflowEntity(UUID.randomUUID(), ticket, "bank/customer-management", "main"));
    }

    private MvcResult openStream(UUID workflowId) throws Exception {
        return mockMvc.perform(get("/api/workflows/{id}/events", workflowId).header("X-Api-Key", API_KEY))
                .andExpect(request().asyncStarted())
                .andReturn();
    }
}
