package com.mel.aidev.gitlab;

/** Encodes the provider in the persisted legacy repository column without changing workflow history. */
public final class ScmProjectId {

    public static final String BITBUCKET_PREFIX = "bitbucket:";

    private ScmProjectId() {}

    public static boolean isBitbucket(String projectId) {
        return projectId != null && projectId.startsWith(BITBUCKET_PREFIX);
    }

    public static String bitbucket(String repository) {
        return BITBUCKET_PREFIX + repository.trim();
    }

    public static String repository(String projectId) {
        return isBitbucket(projectId) ? projectId.substring(BITBUCKET_PREFIX.length()) : projectId;
    }
}
