package com.mel.aidev.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mel.aidev.persistence.entity.ProjectEntity;
import com.mel.aidev.persistence.entity.WorkflowEntity;
import com.mel.aidev.persistence.repository.ProjectRepository;
import com.mel.aidev.persistence.repository.WorkflowRepository;
import com.mel.aidev.workflow.WorkflowStatus;
import java.util.EnumSet;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Runs the Flyway migration against a real PostgreSQL.
 *
 * <p>The schema relies on a partial unique index, which no in-memory database reproduces faithfully.
 * That index is the last line of defence against two concurrent workflows opening two merge requests
 * for the same ticket, so it is verified against the engine that will actually enforce it.
 */
@Testcontainers
@DataJpaTest
@ActiveProfiles("test")
@EnabledIf("dockerAvailable")
@TestPropertySource(
        properties = {
            "spring.flyway.enabled=true",
            "spring.jpa.hibernate.ddl-auto=validate",
            "spring.test.database.replace=none"
        })
class FlywayMigrationIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    /**
     * Skips rather than fails when no Docker daemon is reachable, so a developer without Docker can
     * still run {@code mvn verify}. The CI job runs with a daemon, where this test is mandatory.
     */
    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Autowired
    private WorkflowRepository workflowRepository;

    @Autowired
    private ProjectRepository projectRepository;

    private UUID projectId;

    /** A workflow now carries a mandatory foreign key to its project, enforced by the real engine. */
    @org.junit.jupiter.api.BeforeEach
    void createProject() {
        projectId = projectRepository
                .saveAndFlush(new ProjectEntity(UUID.randomUUID(), "Banque " + UUID.randomUUID(), "bank/cm"))
                .getId();
    }

    @Test
    @DisplayName("the migration produces a schema the JPA mapping validates against")
    void shouldMigrateAndValidateMapping() {
        WorkflowEntity workflow = new WorkflowEntity(UUID.randomUUID(), projectId, "BANK-1245", "bank/cm", "main");
        workflow.setBranch("ai/BANK-1245");
        workflow.setTicketAnalysisJson("{\"ticketId\":\"BANK-1245\"}");

        WorkflowEntity saved = workflowRepository.saveAndFlush(workflow);

        assertThat(workflowRepository.findById(saved.getId())).isPresent();
    }

    @Test
    @DisplayName("the database refuses a second active workflow for the same ticket")
    void shouldRejectDuplicateActiveWorkflow() {
        workflowRepository.saveAndFlush(new WorkflowEntity(UUID.randomUUID(), projectId, "BANK-9000", "bank/cm", "main"));

        assertThatThrownBy(() -> workflowRepository.saveAndFlush(
                        new WorkflowEntity(UUID.randomUUID(), projectId, "BANK-9000", "bank/cm", "main")))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("a terminated workflow does not block a new one for the same ticket")
    void shouldAllowNewWorkflowAfterTermination() {
        WorkflowEntity first = new WorkflowEntity(UUID.randomUUID(), projectId, "BANK-9001", "bank/cm", "main");
        first.setStatus(WorkflowStatus.DONE);
        workflowRepository.saveAndFlush(first);

        WorkflowEntity second = workflowRepository.saveAndFlush(
                new WorkflowEntity(UUID.randomUUID(), projectId, "BANK-9001", "bank/cm", "main"));

        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(workflowRepository.findActiveByJiraTicket(
                        "BANK-9001",
                        EnumSet.of(
                                WorkflowStatus.DONE,
                                WorkflowStatus.FAILED,
                                WorkflowStatus.CANCELLED,
                                WorkflowStatus.NEEDS_CLARIFICATION)))
                .hasSize(1);
    }
}
