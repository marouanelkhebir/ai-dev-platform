package com.mel.aidev.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mel.aidev.gitlab.GitLabClient;
import com.mel.aidev.gitlab.GitLabException;
import com.mel.aidev.gitlab.model.GitLabProject;
import com.mel.aidev.jira.JiraClient;
import com.mel.aidev.persistence.entity.WorkflowEntity;
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
 * Contract of the project API, with the full Spring context.
 *
 * <p>The engine is mocked so what is under test is the API and its rules: validation, archiving,
 * cloning, the ownership check on workflows and the deletion guards.
 */
@SpringBootTest
@ActiveProfiles("test")
class ProjectApiIT {

    private static final String API_KEY = "test-api-key";
    private static final String REPOSITORY = "bank/customer-management";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ProjectRepository projectRepository;

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
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(new ApiKeyFilter(API_KEY))
                .build();
        workflowRepository.deleteAll();
        projectRepository.deleteAll();

        when(gitLabClient.getProject(anyString()))
                .thenReturn(new GitLabProject(1L, "cm", REPOSITORY, "main", "https://gitlab/x", "https://gitlab/x.git"));
        when(jiraClient.projectExists(anyString())).thenReturn(true);
    }

    // ---------------------------------------------------------------------- CRUD

    @Test
    @DisplayName("creates a project and exposes its resolved configuration")
    void shouldCreateProject() throws Exception {
        mockMvc.perform(post("/api/projects")
                        .header("X-Api-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Banque", REPOSITORY, "BANK")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.summary.name").value("Banque"))
                .andExpect(jsonPath("$.summary.jiraProjectKey").value("BANK"))
                .andExpect(jsonPath("$.summary.workflowCount").value(0))
                // Nothing is pinned, so the resolved view says what the platform would apply.
                .andExpect(jsonPath("$.resolved.branchPrefix").value("ai/"))
                .andExpect(jsonPath("$.resolved.baseBranch").value("main"));

        assertThat(projectRepository.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("refuses a second project carrying the same name")
    void shouldRefuseDuplicateName() throws Exception {
        createProject("Banque");

        mockMvc.perform(post("/api/projects")
                        .header("X-Api-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("banque", "other/repo", "BANK")))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("refuses a repository the configuration cannot reach")
    void shouldRefuseUnreachableRepository() throws Exception {
        when(gitLabClient.getProject("bank/ghost")).thenThrow(new GitLabException("HTTP 404"));

        mockMvc.perform(post("/api/projects")
                        .header("X-Api-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Fantôme", "bank/ghost", "BANK")))
                .andExpect(status().isUnprocessableEntity());

        assertThat(projectRepository.findAll()).isEmpty();
    }

    /** The registry allowlist is empty in the test configuration, but a moving tag is always refused. */
    @Test
    @DisplayName("refuses an image pinned to a moving tag")
    void shouldRefuseLatestImage() throws Exception {
        mockMvc.perform(post("/api/projects")
                        .header("X-Api-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Banque\",\"gitlabProject\":\"" + REPOSITORY
                                + "\",\"dockerImage\":\"registry.test/team/sandbox:latest\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("refuses a test command the sandbox would reject")
    void shouldRefuseForbiddenCommand() throws Exception {
        mockMvc.perform(post("/api/projects")
                        .header("X-Api-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Banque\",\"gitlabProject\":\"" + REPOSITORY
                                + "\",\"testCommand\":[\"bash\",\"-c\",\"mvn test\"]}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("refuses a variable that looks like a secret")
    void shouldRefuseSecretVariable() throws Exception {
        mockMvc.perform(post("/api/projects")
                        .header("X-Api-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Banque\",\"gitlabProject\":\"" + REPOSITORY
                                + "\",\"variables\":{\"GITLAB_TOKEN\":\"abc\"}}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("updates the configuration of a project")
    void shouldUpdateProject() throws Exception {
        UUID id = createProject("Banque");

        mockMvc.perform(put("/api/projects/{id}", id)
                        .header("X-Api-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Banque\",\"gitlabProject\":\"" + REPOSITORY
                                + "\",\"jiraProjectKey\":\"BANK\",\"branchPrefix\":\"bot/\","
                                + "\"testCommand\":[\"mvn\",\"-B\",\"verify\"],\"retentionDays\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.branchPrefix").value("bot/"))
                .andExpect(jsonPath("$.testCommand[0]").value("mvn"))
                .andExpect(jsonPath("$.retentionDays").value(0))
                .andExpect(jsonPath("$.resolved.branchPrefix").value("bot/"));
    }

    // -------------------------------------------------------------- lifecycle

    @Test
    @DisplayName("archives a project instead of deleting it, and refuses to start work on it")
    void shouldArchiveProject() throws Exception {
        UUID id = createProject("Banque");

        mockMvc.perform(delete("/api/projects/{id}", id).header("X-Api-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.active").value(false));

        mockMvc.perform(post("/api/projects/{id}/workflows", id)
                        .header("X-Api-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jiraTicket\":\"BANK-1245\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("refuses to archive a project that still has a running workflow")
    void shouldRefuseArchivingWithRunningWorkflow() throws Exception {
        UUID id = createProject("Banque");
        workflowRepository.save(new WorkflowEntity(UUID.randomUUID(), id, "BANK-1245", REPOSITORY, "main"));

        mockMvc.perform(delete("/api/projects/{id}", id).header("X-Api-Key", API_KEY))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("refuses to delete a project that still holds a workflow")
    void shouldRefuseDeletingProjectWithWorkflows() throws Exception {
        UUID id = createProject("Banque");
        WorkflowEntity workflow =
                workflowRepository.save(new WorkflowEntity(UUID.randomUUID(), id, "BANK-1245", REPOSITORY, "main"));
        workflow.setStatus(WorkflowStatus.DONE);
        workflowRepository.save(workflow);

        mockMvc.perform(delete("/api/projects/{id}?force=true", id).header("X-Api-Key", API_KEY))
                .andExpect(status().isConflict());

        assertThat(projectRepository.findById(id)).isPresent();
    }

    @Test
    @DisplayName("deletes a project that holds no workflow at all")
    void shouldDeleteEmptyProject() throws Exception {
        UUID id = createProject("Banque");

        mockMvc.perform(delete("/api/projects/{id}?force=true", id).header("X-Api-Key", API_KEY))
                .andExpect(status().isNoContent());

        assertThat(projectRepository.findById(id)).isEmpty();
    }

    /** A clone copies the configuration, never the history. */
    @Test
    @DisplayName("clones a project without its workflows")
    void shouldCloneProject() throws Exception {
        UUID id = createProject("Banque");
        WorkflowEntity workflow =
                workflowRepository.save(new WorkflowEntity(UUID.randomUUID(), id, "BANK-1245", REPOSITORY, "main"));
        workflow.setStatus(WorkflowStatus.DONE);
        workflowRepository.save(workflow);

        mockMvc.perform(post("/api/projects/{id}/clone", id)
                        .header("X-Api-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Banque — intégration\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.summary.name").value("Banque — intégration"))
                .andExpect(jsonPath("$.summary.gitlabProject").value(REPOSITORY))
                .andExpect(jsonPath("$.summary.workflowCount").value(0));

        assertThat(projectRepository.findAll()).hasSize(2);
    }

    // --------------------------------------------------------------- workflows

    @Test
    @DisplayName("starts a workflow that inherits the repository of its project")
    void shouldStartWorkflowFromProject() throws Exception {
        UUID id = createProject("Banque");

        mockMvc.perform(post("/api/projects/{id}/workflows", id)
                        .header("X-Api-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jiraTicket\":\"BANK-1245\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.projectId").value(id.toString()))
                .andExpect(jsonPath("$.gitlabProject").value(REPOSITORY))
                .andExpect(jsonPath("$.branch").value("ai/BANK-1245"));
    }

    @Test
    @DisplayName("refuses a ticket that belongs to another Jira project")
    void shouldRefuseTicketOfAnotherJiraProject() throws Exception {
        UUID id = createProject("Banque");

        mockMvc.perform(post("/api/projects/{id}/workflows", id)
                        .header("X-Api-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jiraTicket\":\"OTHER-1245\"}"))
                .andExpect(status().isUnprocessableEntity());

        assertThat(workflowRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("deletes a terminated workflow of the project")
    void shouldDeleteTerminatedWorkflow() throws Exception {
        UUID id = createProject("Banque");
        WorkflowEntity workflow =
                workflowRepository.save(new WorkflowEntity(UUID.randomUUID(), id, "BANK-1245", REPOSITORY, "main"));
        workflow.setStatus(WorkflowStatus.DONE);
        workflowRepository.save(workflow);

        mockMvc.perform(delete("/api/projects/{p}/workflows/{w}", id, workflow.getId())
                        .header("X-Api-Key", API_KEY)
                        .header("X-Actor", "alice"))
                .andExpect(status().isNoContent());

        assertThat(workflowRepository.findById(workflow.getId())).isEmpty();
    }

    @Test
    @DisplayName("refuses to delete a workflow that is still running")
    void shouldRefuseDeletingRunningWorkflow() throws Exception {
        UUID id = createProject("Banque");
        WorkflowEntity workflow =
                workflowRepository.save(new WorkflowEntity(UUID.randomUUID(), id, "BANK-1245", REPOSITORY, "main"));

        mockMvc.perform(delete("/api/projects/{p}/workflows/{w}", id, workflow.getId()).header("X-Api-Key", API_KEY))
                .andExpect(status().isConflict());

        assertThat(workflowRepository.findById(workflow.getId())).isPresent();
    }

    /** 404 and not 403: that an id exists elsewhere is information the caller did not have. */
    @Test
    @DisplayName("answers 404 for a workflow that belongs to another project")
    void shouldNotRevealWorkflowOfAnotherProject() throws Exception {
        UUID owner = createProject("Banque");
        UUID other = createProject("Assurance");
        WorkflowEntity workflow =
                workflowRepository.save(new WorkflowEntity(UUID.randomUUID(), owner, "BANK-1245", REPOSITORY, "main"));
        workflow.setStatus(WorkflowStatus.DONE);
        workflowRepository.save(workflow);

        mockMvc.perform(delete("/api/projects/{p}/workflows/{w}", other, workflow.getId()).header("X-Api-Key", API_KEY))
                .andExpect(status().isNotFound());

        assertThat(workflowRepository.findById(workflow.getId())).isPresent();
    }

    @Test
    @DisplayName("archives a workflow instead of destroying its audit trail")
    void shouldArchiveWorkflow() throws Exception {
        UUID id = createProject("Banque");
        WorkflowEntity workflow =
                workflowRepository.save(new WorkflowEntity(UUID.randomUUID(), id, "BANK-1245", REPOSITORY, "main"));
        workflow.setStatus(WorkflowStatus.DONE);
        workflowRepository.save(workflow);

        mockMvc.perform(post("/api/projects/{p}/workflows/{w}/archive", id, workflow.getId())
                        .header("X-Api-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archivedAt").isNotEmpty());

        mockMvc.perform(get("/api/projects/{p}/workflows", id).header("X-Api-Key", API_KEY))
                .andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get("/api/projects/{p}/workflows?includeArchived=true", id).header("X-Api-Key", API_KEY))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    // --------------------------------------------------------------- dashboard

    @Test
    @DisplayName("exposes a dashboard even when nothing happened yet")
    void shouldExposeEmptyDashboard() throws Exception {
        UUID id = createProject("Banque");

        mockMvc.perform(get("/api/projects/{id}/dashboard", id).header("X-Api-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflows.total").value(0))
                .andExpect(jsonPath("$.workflows.successRate").doesNotExist())
                .andExpect(jsonPath("$.llm.totalTokens").value(0));
    }

    // ---------------------------------------------------------------- internals

    private UUID createProject(String name) throws Exception {
        String response = mockMvc.perform(post("/api/projects")
                        .header("X-Api-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(name, REPOSITORY, "BANK")))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(response.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1"));
    }

    private static String body(String name, String repository, String jiraKey) {
        return "{\"name\":\"" + name + "\",\"gitlabProject\":\"" + repository + "\",\"jiraProjectKey\":\"" + jiraKey
                + "\"}";
    }
}
