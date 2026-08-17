package com.mel.aidev.sandbox;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mel.aidev.config.SandboxProperties;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * These tests document the two properties that make command injection impossible: no shell, and an
 * executable allowlist.
 */
class CommandGuardTest {

    private final CommandGuard guard = new CommandGuard(new SandboxProperties(
            null, null, null, null, null, null, null, null, null, null, null, null, null, List.of("git", "mvn", "grep"), null, null,
            null));

    @Test
    @DisplayName("accepts an allowlisted executable")
    void shouldAcceptAllowedExecutable() {
        assertThatCode(() -> guard.validate(List.of("mvn", "-B", "test"))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("rejects an executable outside the allowlist")
    void shouldRejectUnknownExecutable() {
        assertThatThrownBy(() -> guard.validate(List.of("curl", "https://evil.example")))
                .isInstanceOf(SandboxException.class)
                .hasMessageContaining("not allowed");
    }

    @Test
    @DisplayName("rejects any attempt to spawn a shell")
    void shouldRejectShells() {
        for (String shell : List.of("sh", "bash", "zsh", "env")) {
            assertThatThrownBy(() -> guard.validate(List.of(shell, "-c", "rm -rf /")))
                    .isInstanceOf(SandboxException.class);
        }
    }

    @Test
    @DisplayName("treats shell metacharacters as ordinary arguments, not separators")
    void shouldNotTreatMetacharactersAsSeparators() {
        // No shell is involved, so this is a single grep pattern, not two commands. It must pass the
        // guard: rejecting it would break legitimate searches while proving nothing about safety.
        assertThatCode(() -> guard.validate(List.of("grep", "-r", "foo; rm -rf /", ".")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("rejects the git subcommands that could rewrite history or leak credentials")
    void shouldRejectDangerousGitSubcommands() {
        for (String subcommand : List.of("config", "remote", "credential", "filter-branch")) {
            assertThatThrownBy(() -> guard.validate(List.of("git", subcommand, "--list")))
                    .isInstanceOf(SandboxException.class)
                    .hasMessageContaining("forbidden");
        }
    }

    @Test
    @DisplayName("accepts the git subcommands the agents legitimately need")
    void shouldAcceptSafeGitSubcommands() {
        assertThatCode(() -> guard.validate(List.of("git", "status", "--porcelain"))).doesNotThrowAnyException();
        assertThatCode(() -> guard.validate(List.of("git", "diff", "HEAD"))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("rejects malformed commands")
    void shouldRejectMalformedCommands() {
        assertThatThrownBy(() -> guard.validate(List.of())).isInstanceOf(SandboxException.class);
        assertThatThrownBy(() -> guard.validate(null)).isInstanceOf(SandboxException.class);
        assertThatThrownBy(() -> guard.validate(Arrays.asList("git", null))).isInstanceOf(SandboxException.class);
        assertThatThrownBy(() -> guard.validate(List.of("git", "status\0--evil")))
                .isInstanceOf(SandboxException.class);
    }
}
