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
}
