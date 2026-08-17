package com.mel.aidev.gitlab;

/** Encodes the provider in the persisted legacy repository column without changing workflow history. */
public final class ScmProjectId {

    public static final String BITBUCKET_PREFIX = "bitbucket:";
    public static final String GITHUB_PREFIX = "github:";

    private ScmProjectId() {}

    public static boolean isBitbucket(String projectId) {
        return projectId != null && projectId.startsWith(BITBUCKET_PREFIX);
    }

    public static boolean isGitHub(String projectId) {
        return projectId != null && projectId.startsWith(GITHUB_PREFIX);
    }

    public static String bitbucket(String repository) {
        return BITBUCKET_PREFIX + repository.trim();
    }

    public static String github(String repository) {
        return GITHUB_PREFIX + repository.trim();
    }

    public static String repository(String projectId) {
        if (isBitbucket(projectId)) {
            return projectId.substring(BITBUCKET_PREFIX.length());
        }
        if (isGitHub(projectId)) {
            return projectId.substring(GITHUB_PREFIX.length());
        }
        return projectId;
    }
}
