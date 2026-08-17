package com.mel.aidev.gitlab;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ScmProjectIdTest {

    @Test
    void preservesGitLabIdentifiersAndMarksBitbucketIdentifiers() {
        assertThat(ScmProjectId.repository("group/repository")).isEqualTo("group/repository");
        assertThat(ScmProjectId.bitbucket("workspace/repository")).isEqualTo("bitbucket:workspace/repository");
        assertThat(ScmProjectId.repository("bitbucket:workspace/repository")).isEqualTo("workspace/repository");
        assertThat(ScmProjectId.isBitbucket("bitbucket:workspace/repository")).isTrue();
    }

    @Test
    void marksGitHubIdentifiers() {
        assertThat(ScmProjectId.github("owner/repository")).isEqualTo("github:owner/repository");
        assertThat(ScmProjectId.repository("github:owner/repository")).isEqualTo("owner/repository");
        assertThat(ScmProjectId.isGitHub("github:owner/repository")).isTrue();
        assertThat(ScmProjectId.isGitHub("bitbucket:workspace/repository")).isFalse();
        assertThat(ScmProjectId.isBitbucket("github:owner/repository")).isFalse();
    }
}
