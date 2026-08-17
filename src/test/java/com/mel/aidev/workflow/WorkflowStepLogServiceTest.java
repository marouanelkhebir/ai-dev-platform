package com.mel.aidev.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mel.aidev.config.WorkflowProperties;
import com.mel.aidev.observability.StepLogBuffer;
import com.mel.aidev.persistence.entity.WorkflowStepEntity;
import com.mel.aidev.persistence.entity.WorkflowStepLogEntity;
import com.mel.aidev.persistence.repository.WorkflowStepLogRepository;
import com.mel.aidev.security.SecretRedactor;
import java.io.StringWriter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Storage of a step log: compression, redaction, and what is deliberately not stored. */
class WorkflowStepLogServiceTest {

    private final WorkflowStepLogRepository repository = mock(WorkflowStepLogRepository.class);
    private final WorkflowStepLogService service =
            new WorkflowStepLogService(repository, new SecretRedactor(), properties(20_000_000));

    private static WorkflowProperties properties(int maxCharsPerStep) {
        return new WorkflowProperties(
                null, null, null, null, null, null, null, null, null, null,
                new WorkflowProperties.Logs(true, maxCharsPerStep, 14));
    }

    @Test
    @DisplayName("a step log survives a compression round trip")
    void shouldStoreAndReadBackTheWholeLog() {
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        WorkflowStepEntity step = new WorkflowStepEntity(UUID.randomUUID(), 3, WorkflowStatus.DEVELOPING);

        StepLogBuffer buffer = service.begin(step, "BANK-1245");
        buffer.line("[INFO] Building customer-management");
        buffer.line("[ERROR] Fee.java:[42,9] cannot find symbol");
        service.persist(buffer);

        ArgumentCaptor<WorkflowStepLogEntity> captor = ArgumentCaptor.forClass(WorkflowStepLogEntity.class);
        verify(repository).save(captor.capture());
        WorkflowStepLogEntity stored = captor.getValue();
        assertThat(stored.text()).contains("=== step 3 DEVELOPING ticket=BANK-1245");
        assertThat(stored.text()).contains("[ERROR] Fee.java:[42,9] cannot find symbol");
        assertThat(stored.getSequenceNumber()).isEqualTo(3);
        assertThat(stored.isTruncated()).isFalse();
    }

    @Test
    @DisplayName("repetitive build output is stored far smaller than it was produced")
    void shouldCompressBuildOutput() {
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        WorkflowStepEntity step = new WorkflowStepEntity(UUID.randomUUID(), 1, WorkflowStatus.RUNNING_LOCAL_TESTS);

        StepLogBuffer buffer = service.begin(step, "BANK-1245");
        for (int i = 0; i < 20_000; i++) {
            buffer.line("[INFO] Downloading from central: https://repo.maven.apache.org/maven2/artifact-" + i + ".jar");
        }
        service.persist(buffer);

        ArgumentCaptor<WorkflowStepLogEntity> captor = ArgumentCaptor.forClass(WorkflowStepLogEntity.class);
        verify(repository).save(captor.capture());
        WorkflowStepLogEntity stored = captor.getValue();
        // Compression is what makes storing whole build logs in the database defensible at all.
        assertThat(stored.getCompressedBytes()).isLessThan(stored.getUncompressedChars() / 10);
    }

    @Test
    @DisplayName("a credential that reached the container output is masked before storage")
    void shouldRedactSecrets() {
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        WorkflowStepEntity step = new WorkflowStepEntity(UUID.randomUUID(), 2, WorkflowStatus.PUSHING);

        StepLogBuffer buffer = service.begin(step, "BANK-1245");
        buffer.line("fatal: could not read from https://oauth2:glpat-AbCdEf0123456789xyz@gitlab.company.com/bank.git");
        service.persist(buffer);

        ArgumentCaptor<WorkflowStepLogEntity> captor = ArgumentCaptor.forClass(WorkflowStepLogEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().text()).doesNotContain("glpat-AbCdEf0123456789xyz");
        assertThat(captor.getValue().text()).contains("REDACTED");
    }

    @Test
    @DisplayName("nothing is stored when capture is disabled")
    void shouldStoreNothingWhenDisabled() {
        WorkflowStepLogService disabled = new WorkflowStepLogService(
                repository,
                new SecretRedactor(),
                new WorkflowProperties(
                        null, null, null, null, null, null, null, null, null, null,
                        new WorkflowProperties.Logs(false, 20_000_000, 14)));
        WorkflowStepEntity step = new WorkflowStepEntity(UUID.randomUUID(), 1, WorkflowStatus.DEVELOPING);

        StepLogBuffer buffer = disabled.begin(step, "BANK-1245");
        buffer.line("[INFO] Building");
        disabled.persist(buffer);

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("a failure to store a log never fails the step")
    void shouldSwallowStorageFailures() {
        when(repository.save(any())).thenThrow(new IllegalStateException("database is down"));
        WorkflowStepEntity step = new WorkflowStepEntity(UUID.randomUUID(), 1, WorkflowStatus.DEVELOPING);

        StepLogBuffer buffer = service.begin(step, "BANK-1245");
        buffer.line("[INFO] Building");

        service.persist(buffer);
    }

    @Test
    @DisplayName("the whole run is written step by step, in order")
    void shouldWriteEveryStepInOrder() {
        UUID workflowId = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(repository.findIdsByWorkflowIdOrderBySequenceNumber(workflowId)).thenReturn(List.of(first, second));
        when(repository.findById(first))
                .thenReturn(Optional.of(new WorkflowStepLogEntity(workflowId, UUID.randomUUID(), 1, "step one", false)));
        when(repository.findById(second))
                .thenReturn(Optional.of(new WorkflowStepLogEntity(workflowId, UUID.randomUUID(), 2, "step two", false)));

        StringWriter writer = new StringWriter();
        service.writeTo(workflowId, writer);

        assertThat(writer.toString()).isEqualTo("step one\nstep two\n");
    }
}
