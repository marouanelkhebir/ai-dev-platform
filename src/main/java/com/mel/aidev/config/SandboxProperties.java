package com.mel.aidev.config;

import com.mel.aidev.domain.BuildProfile;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Docker sandbox configuration.
 *
 * <p>The developer agent never touches the filesystem of the platform: every ticket runs inside its
 * own throwaway container, with a restricted executable allowlist and no production credentials.
 */
@ConfigurationProperties(prefix = "sandbox")
public record SandboxProperties(
        String dockerHost,
        String image,
        String angularImage,
        String pythonImage,
        String workspaceRoot,
        Long memoryLimitBytes,
        Long cpuQuota,
        Long pidsLimit,
        Boolean networkEnabled,
        Boolean readOnlyRootFilesystem,
        Duration commandTimeout,
        Duration maxLifetime,
        Integer maxOutputChars,
        List<String> allowedExecutables,
        Map<String, String> environment,
        List<String> allowedRegistries,
        Boolean requireImageDigest) {

    public SandboxProperties {
        dockerHost = blankTo(dockerHost, "unix:///var/run/docker.sock");
        image = blankTo(image, "ai-dev-sandbox:21");
        angularImage = blankTo(angularImage, "ai-dev-sandbox-angular:22");
        pythonImage = blankTo(pythonImage, "ai-dev-sandbox-python:3.11");
        workspaceRoot = blankTo(workspaceRoot, "/workspaces");
        memoryLimitBytes = memoryLimitBytes == null ? 4L * 1024 * 1024 * 1024 : memoryLimitBytes;
        cpuQuota = cpuQuota == null ? 200_000L : cpuQuota; // 2 CPUs with the default 100ms period
        pidsLimit = pidsLimit == null ? 512L : pidsLimit;
        networkEnabled = networkEnabled == null || networkEnabled; // needed for git + maven central
        readOnlyRootFilesystem = readOnlyRootFilesystem != null && readOnlyRootFilesystem;
        commandTimeout = commandTimeout == null ? Duration.ofMinutes(20) : commandTimeout;
        maxLifetime = maxLifetime == null ? Duration.ofHours(2) : maxLifetime;
        maxOutputChars = maxOutputChars == null ? 200_000 : maxOutputChars;
        allowedExecutables = allowedExecutables == null || allowedExecutables.isEmpty()
                ? List.of("git", "mvn", "./mvnw", "mvnw", "java", "ls", "cat", "grep", "find", "test", "npm", "node", "python")
                : List.copyOf(allowedExecutables);
        environment = environment == null ? Map.of() : Map.copyOf(environment);
        // Empty means "no registry restriction": a fresh deployment builds its images locally and
        // would not start otherwise. Filling it in is what turns the check into a real lock.
        allowedRegistries = allowedRegistries == null ? List.of() : List.copyOf(allowedRegistries);
        requireImageDigest = requireImageDigest != null && requireImageDigest;
    }

    /** Resolves a fixed, administrator-configured image; agents never choose container images. */
    public String imageFor(BuildProfile profile) {
        return switch (profile) {
            case MAVEN -> image;
            case ANGULAR -> angularImage;
            case PYTHON -> pythonImage;
            case UNSUPPORTED -> throw new IllegalArgumentException("Unsupported repository build profile");
        };
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /** Absolute workspace path of a ticket inside the container, e.g. {@code /workspaces/BANK-1245}. */
    public String workspacePathFor(String jiraTicket) {
        return workspaceRoot + "/" + jiraTicket;
    }
}
