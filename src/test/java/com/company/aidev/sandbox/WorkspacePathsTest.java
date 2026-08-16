package com.company.aidev.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Paths come from a language model, so they are untrusted input and are tested as such. */
class WorkspacePathsTest {

    private final Sandbox sandbox = new Sandbox(
            UUID.randomUUID(),
            "container-id",
            "/workspaces/BANK-1245",
            "/workspaces/BANK-1245/repo",
            UUID.randomUUID(),
            "BANK-1245",
            Instant.now());

    @Test
    @DisplayName("resolves a plain relative path against the repository root")
    void shouldResolveRelativePath() {
        assertThat(WorkspacePaths.resolve(sandbox, "src/main/java/Fee.java"))
                .isEqualTo("/workspaces/BANK-1245/repo/src/main/java/Fee.java");
    }

    @Test
    @DisplayName("collapses redundant segments")
    void shouldNormalizeInnocuousSegments() {
        assertThat(WorkspacePaths.resolve(sandbox, "./src/./main/../main/Fee.java"))
                .isEqualTo("/workspaces/BANK-1245/repo/src/main/Fee.java");
    }

    @Test
    @DisplayName("accepts an absolute path that is already inside the repository")
    void shouldAcceptAbsolutePathInsideRepository() {
        assertThat(WorkspacePaths.resolve(sandbox, "/workspaces/BANK-1245/repo/pom.xml"))
                .isEqualTo("/workspaces/BANK-1245/repo/pom.xml");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "../../etc/passwd",
                "src/../../../root/.ssh/id_rsa",
                "/etc/shadow",
                "/workspaces/OTHER-1/repo/pom.xml",
                "~/.gitconfig",
                "..",
            })
    @DisplayName("rejects every attempt to escape the repository")
    void shouldRejectEscapes(String path) {
        assertThatThrownBy(() -> WorkspacePaths.resolve(sandbox, path)).isInstanceOf(SandboxException.class);
    }

    @Test
    @DisplayName("rejects an empty path")
    void shouldRejectEmptyPath() {
        assertThatThrownBy(() -> WorkspacePaths.resolve(sandbox, "  ")).isInstanceOf(SandboxException.class);
    }

    @Test
    @DisplayName("refuses to write inside .git, .ai or the CI configuration")
    void shouldProtectSensitivePaths() {
        assertThatThrownBy(() -> WorkspacePaths.assertWritable("/workspaces/BANK-1245/repo/.git/config"))
                .isInstanceOf(SandboxException.class)
                .hasMessageContaining(".git");

        assertThatThrownBy(() -> WorkspacePaths.assertWritable("/workspaces/BANK-1245/repo/.ai/security.md"))
                .isInstanceOf(SandboxException.class)
                .hasMessageContaining("read-only");

        assertThatThrownBy(() -> WorkspacePaths.assertWritable("/workspaces/BANK-1245/repo/.gitlab-ci.yml"))
                .isInstanceOf(SandboxException.class)
                .hasMessageContaining("CI configuration");
    }

    @Test
    @DisplayName("allows writing a normal source file")
    void shouldAllowWritingSource() {
        WorkspacePaths.assertWritable("/workspaces/BANK-1245/repo/src/main/java/Fee.java");
    }
}
