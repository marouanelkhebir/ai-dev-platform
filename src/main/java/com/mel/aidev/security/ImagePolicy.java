package com.mel.aidev.security;

import com.mel.aidev.config.SandboxProperties;
import com.mel.aidev.settings.PlatformSettings;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Enforces which container images the platform is allowed to run.
 *
 * <p>Twin of {@link BranchPolicy}, and for the same reason: the registry allowlist is configured by
 * humans in a screen, and this class is the second lock, covered by tests. It is applied when a
 * project is saved <em>and</em> when a sandbox starts — the allowlist can change between the two,
 * and the check that matters is the one made at the moment the container is created.
 */
@Component
public class ImagePolicy {

    /** A reference pinned by digest, e.g. {@code registry.example.com/team/image@sha256:ab...}. */
    private static final String DIGEST_SEPARATOR = "@sha256:";

    private final PlatformSettings settings;

    public ImagePolicy(PlatformSettings settings) {
        this.settings = settings;
    }

    /**
     * @throws ImagePolicyViolationException when the image may not be run
     */
    public void assertAllowed(String image) {
        if (image == null || image.isBlank()) {
            throw new ImagePolicyViolationException("No container image resolved");
        }
        String trimmed = image.trim();
        if (trimmed.indexOf(' ') >= 0) {
            throw new ImagePolicyViolationException("Container image contains a space: " + trimmed);
        }

        SandboxProperties sandbox = settings.sandbox();
        boolean pinnedByDigest = trimmed.contains(DIGEST_SEPARATOR);

        if (sandbox.requireImageDigest() && !pinnedByDigest) {
            throw new ImagePolicyViolationException(
                    "Container images must be pinned by digest (registry/image@sha256:...), got: " + trimmed);
        }
        if (!pinnedByDigest && isLatest(trimmed)) {
            // A moving tag makes the audit trail wrong: two workflows recording the same image would
            // not have run the same code.
            throw new ImagePolicyViolationException(
                    "The 'latest' tag is not allowed; pin a version or a digest: " + trimmed);
        }

        List<String> allowedRegistries = sandbox.allowedRegistries();
        if (allowedRegistries.isEmpty()) {
            return;
        }
        String registry = registryOf(trimmed);
        boolean allowed = allowedRegistries.stream()
                .anyMatch(candidate -> candidate.trim().equalsIgnoreCase(registry));
        if (!allowed) {
            throw new ImagePolicyViolationException(
                    "Registry not allowed: " + registry + " (allowed: " + String.join(", ", allowedRegistries) + ")");
        }
    }

    public boolean isAllowed(String image) {
        try {
            assertAllowed(image);
            return true;
        } catch (ImagePolicyViolationException e) {
            return false;
        }
    }

    /**
     * Registry an image reference points at.
     *
     * <p>Docker's rule, not a heuristic: the first path segment is the registry only when it looks
     * like a host — it contains a dot or a colon, or it is {@code localhost}. Otherwise the
     * reference targets Docker Hub, and a local image such as {@code ai-dev-sandbox:21} has no
     * registry at all.
     */
    String registryOf(String image) {
        String withoutDigest = image.contains(DIGEST_SEPARATOR)
                ? image.substring(0, image.indexOf(DIGEST_SEPARATOR))
                : image;
        int firstSlash = withoutDigest.indexOf('/');
        if (firstSlash < 0) {
            return "";
        }
        String candidate = withoutDigest.substring(0, firstSlash);
        boolean looksLikeHost =
                candidate.indexOf('.') >= 0 || candidate.indexOf(':') >= 0 || "localhost".equals(candidate);
        return looksLikeHost ? candidate : "";
    }

    private static boolean isLatest(String image) {
        int lastSlash = image.lastIndexOf('/');
        String lastSegment = lastSlash < 0 ? image : image.substring(lastSlash + 1);
        int colon = lastSegment.indexOf(':');
        String tag = colon < 0 ? "latest" : lastSegment.substring(colon + 1);
        return "latest".equals(tag.toLowerCase(Locale.ROOT));
    }

    /** Raised when the platform is asked to run an image it must never run. */
    public static class ImagePolicyViolationException extends RuntimeException {
        public ImagePolicyViolationException(String message) {
            super(message);
        }
    }
}
