package com.company.aidev.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.aidev.config.GitLabProperties;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The platform must be structurally unable to write on a protected branch. */
class BranchPolicyTest {

    private final BranchPolicy policy = new BranchPolicy(new GitLabProperties(
            "https://gitlab.company.com",
            "token",
            null,
            null,
            "ai/",
            "main",
            List.of("main", "master", "develop", "release", "production"),
            null,
            null,
            null,
            null,
            null));

    @Test
    @DisplayName("builds the branch name from the ticket")
    void shouldBuildBranchName() {
        assertThat(policy.branchFor("BANK-1245")).isEqualTo("ai/BANK-1245");
    }

    @Test
    @DisplayName("sanitises a ticket key containing unexpected characters")
    void shouldSanitizeTicketKey() {
        assertThat(policy.branchFor("BANK 1245;rm")).isEqualTo("ai/BANK-1245-rm");
    }

    @ParameterizedTest
    @ValueSource(strings = {"main", "master", "develop", "release", "release/2026.1", "production"})
    @DisplayName("refuses to write on a protected branch")
    void shouldRejectProtectedBranches(String branch) {
        assertThat(policy.isProtected(branch)).isTrue();
        assertThatThrownBy(() -> policy.assertNotProtected(branch))
                .isInstanceOf(BranchPolicy.BranchPolicyViolationException.class);
    }

    @Test
    @DisplayName("refuses any branch outside the ai/ prefix")
    void shouldRejectNonAgentBranch() {
        assertThatThrownBy(() -> policy.assertAgentBranch("feature/BANK-1245"))
                .isInstanceOf(BranchPolicy.BranchPolicyViolationException.class)
                .hasMessageContaining("ai/");
    }

    @Test
    @DisplayName("accepts an ai/ branch")
    void shouldAcceptAgentBranch() {
        assertThatCode(() -> {
                    policy.assertAgentBranch("ai/BANK-1245");
                    policy.assertNotProtected("ai/BANK-1245");
                })
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("treats a null branch as protected")
    void shouldTreatNullAsProtected() {
        assertThat(policy.isProtected(null)).isTrue();
    }
}
