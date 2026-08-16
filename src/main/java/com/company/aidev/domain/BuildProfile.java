package com.company.aidev.domain;

import com.company.aidev.sandbox.Sandbox;
import com.company.aidev.sandbox.SandboxManager;

/** Supported repository build ecosystems and their isolated sandbox profiles. */
public enum BuildProfile {
    MAVEN,
    ANGULAR,
    UNSUPPORTED;

    public static BuildProfile detect(RepositoryContext repository) {
        if (repository.fileTree().stream().anyMatch("angular.json"::equals)) {
            return ANGULAR;
        }
        if (repository.fileTree().stream().anyMatch(path -> path.equals("pom.xml") || path.equals("mvnw"))) {
            return MAVEN;
        }
        return UNSUPPORTED;
    }

    /** Re-detects from the cloned workspace before selecting agent tools. */
    public static BuildProfile detect(SandboxManager sandboxManager, Sandbox sandbox) {
        if (sandboxManager.exists(sandbox, "angular.json")) {
            return ANGULAR;
        }
        if (sandboxManager.exists(sandbox, "pom.xml") || sandboxManager.exists(sandbox, "mvnw")) {
            return MAVEN;
        }
        return UNSUPPORTED;
    }
}
