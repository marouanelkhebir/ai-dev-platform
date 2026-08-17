package com.mel.aidev.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.mel.aidev.sandbox.Sandbox;
import com.mel.aidev.sandbox.SandboxManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * The Maven goal allowlist is what stops a model from running {@code mvn deploy} on unreviewed code.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MavenToolsTest {

    @Mock
    private SandboxManager sandboxManager;

    @Mock
    private ToolExecutionRecorder recorder;

    private final Sandbox sandbox = new Sandbox(
            UUID.randomUUID(), "cid", "/workspaces/BANK-1", "/workspaces/BANK-1/repo", UUID.randomUUID(), "BANK-1", Instant.now());

    private MavenTools tools() {
        return new MavenTools(
                sandboxManager, recorder, new ToolContext(UUID.randomUUID(), UUID.randomUUID(), sandbox));
    }

    @Test
    @DisplayName("uses the Maven wrapper when the repository ships one")
    void shouldPreferWrapper() {
        when(sandboxManager.exists(any(), anyString())).thenReturn(true);

        assertThat(tools().buildCommand(List.of("test"))).containsExactly("./mvnw", "-B", "-ntp", "test");
    }

    @Test
    @DisplayName("falls back to mvn when there is no wrapper")
    void shouldFallBackToMvn() {
        when(sandboxManager.exists(any(), anyString())).thenReturn(false);

        assertThat(tools().buildCommand(List.of("verify"))).containsExactly("mvn", "-B", "-ntp", "verify");
    }

    @Test
    @DisplayName("rejects a goal outside the allowlist")
    void shouldRejectForbiddenGoal() {
        when(sandboxManager.exists(any(), anyString())).thenReturn(false);

        assertThatThrownBy(() -> tools().buildCommand(List.of("deploy")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("deploy");
    }

    @Test
    @DisplayName("rejects arbitrary Maven options")
    void shouldRejectArbitraryOptions() {
        when(sandboxManager.exists(any(), anyString())).thenReturn(false);

        assertThatThrownBy(() -> tools().buildCommand(List.of("test", "--settings", "/tmp/evil.xml")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("rejects a system property outside the allowlist")
    void shouldRejectUnknownSystemProperty() {
        when(sandboxManager.exists(any(), anyString())).thenReturn(false);

        assertThatThrownBy(() -> tools().buildCommand(List.of("test", "-Dexec.executable=/bin/sh")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exec.executable");
    }

    @Test
    @DisplayName("accepts the test selector property")
    void shouldAcceptTestSelector() {
        when(sandboxManager.exists(any(), anyString())).thenReturn(false);

        assertThat(tools().buildCommand(List.of("test", "-Dtest=FeeServiceTest")))
                .containsExactly("mvn", "-B", "-ntp", "test", "-Dtest=FeeServiceTest");
    }

    @Test
    @DisplayName("defaults to running the tests when no goal is given")
    void shouldDefaultToTest() {
        when(sandboxManager.exists(any(), anyString())).thenReturn(false);

        assertThat(tools().buildCommand(List.of())).containsExactly("mvn", "-B", "-ntp", "test");
    }
}
