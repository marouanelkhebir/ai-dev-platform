package com.mel.aidev.project;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ScmProviderTest {

    @Test
    @DisplayName("marks the repository with the provider the SCM clients route on")
    void shouldQualifyRepository() {
        assertThat(ScmProvider.GITLAB.qualify("bank/customer-management")).isEqualTo("bank/customer-management");
        assertThat(ScmProvider.BITBUCKET.qualify("workspace/repository")).isEqualTo("bitbucket:workspace/repository");
        assertThat(ScmProvider.GITHUB.qualify("owner/repository")).isEqualTo("github:owner/repository");
    }

    @Test
    @DisplayName("qualifying an already qualified identifier changes nothing")
    void shouldBeIdempotent() {
        // ProjectService.restore re-validates the stored identifier, which already carries its
        // marker; a second marker would produce a path no provider can resolve.
        assertThat(ScmProvider.GITHUB.qualify("github:owner/repository")).isEqualTo("github:owner/repository");
        assertThat(ScmProvider.BITBUCKET.qualify("bitbucket:workspace/repository"))
                .isEqualTo("bitbucket:workspace/repository");
    }
}
