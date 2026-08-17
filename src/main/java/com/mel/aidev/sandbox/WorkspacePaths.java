package com.mel.aidev.sandbox;

import java.util.Locale;

/**
 * Path traversal guard for every file operation an agent can request.
 *
 * <p>The model provides paths as free text, so a path is untrusted input. Resolution is done on the
 * normalised string and the result must still start with the workspace prefix.
 */
public final class WorkspacePaths {

    private WorkspacePaths() {}

    /**
     * Resolves a model-provided relative path against the sandbox repository root.
     *
     * @throws SandboxException when the path escapes the repository
     */
    public static String resolve(Sandbox sandbox, String relativePath) {
        return resolve(sandbox.repositoryPath(), relativePath);
    }

    static String resolve(String root, String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new SandboxException("Empty path");
        }
        String candidate = relativePath.trim().replace('\\', '/');
        if (candidate.indexOf('\0') >= 0) {
            throw new SandboxException("Null byte in path");
        }
        if (candidate.startsWith("~")) {
            throw new SandboxException("Home-relative paths are forbidden: " + relativePath);
        }
        if (candidate.startsWith("/")) {
            // Absolute paths are only accepted when they already point inside the repository.
            String normalizedAbsolute = normalize(candidate);
            if (!isInside(normalizedAbsolute, root)) {
                throw new SandboxException("Path outside of the workspace: " + relativePath);
            }
            return normalizedAbsolute;
        }
        String normalized = normalize(root + "/" + candidate);
        if (!isInside(normalized, root)) {
            throw new SandboxException("Path traversal detected: " + relativePath);
        }
        return normalized;
    }

    /** Rejects paths that agents must never touch, even inside the repository. */
    public static void assertWritable(String absolutePath) {
        String lower = absolutePath.toLowerCase(Locale.ROOT);
        if (lower.contains("/.git/")) {
            throw new SandboxException("Writing inside .git is forbidden: " + absolutePath);
        }
        if (lower.contains("/.ai/")) {
            throw new SandboxException("Repository rules under .ai/ are read-only for agents: " + absolutePath);
        }
        if (lower.endsWith("/.gitlab-ci.yml")) {
            throw new SandboxException("CI configuration is read-only for agents: " + absolutePath);
        }
    }

    private static boolean isInside(String normalizedPath, String root) {
        String normalizedRoot = normalize(root);
        return normalizedPath.equals(normalizedRoot) || normalizedPath.startsWith(normalizedRoot + "/");
    }

    /** Collapses {@code .} and {@code ..} segments without touching the filesystem. */
    static String normalize(String path) {
        String[] segments = path.split("/");
        java.util.Deque<String> stack = new java.util.ArrayDeque<>();
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                stack.pollLast();
            } else {
                stack.addLast(segment);
            }
        }
        return "/" + String.join("/", stack);
    }
}
