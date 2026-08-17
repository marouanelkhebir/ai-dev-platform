package com.mel.aidev.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mel.aidev.config.SandboxProperties;
import com.mel.aidev.settings.PlatformSettings;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The second lock on which images may run.
 *
 * <p>The registry allowlist and the digest requirement are configured by a human in a screen; these
 * tests are what makes the enforcement itself trustworthy.
 */
class ImagePolicyTest {

    private static final String DIGEST = "@sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    @DisplayName("accepts any image when no registry is configured")
    void shouldAcceptAnyImageWithoutAllowlist() {
        ImagePolicy policy = policy(List.of(), false);

        assertThatCode(() -> policy.assertAllowed("ai-dev-sandbox:21")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("refuses an image from a registry outside the allowlist")
    void shouldRefuseUnknownRegistry() {
        ImagePolicy policy = policy(List.of("registry.example.com"), false);

        assertThatThrownBy(() -> policy.assertAllowed("docker.io/library/alpine:3.19"))
                .isInstanceOf(ImagePolicy.ImagePolicyViolationException.class)
                .hasMessageContaining("docker.io");
    }

    @Test
    @DisplayName("accepts an image from an allowed registry")
    void shouldAcceptAllowedRegistry() {
        ImagePolicy policy = policy(List.of("registry.example.com"), false);

        assertThatCode(() -> policy.assertAllowed("registry.example.com/team/sandbox:21"))
                .doesNotThrowAnyException();
    }

    /**
     * A moving tag makes the audit wrong: two workflows recording the same image would not have run
     * the same code.
     */
    @Test
    @DisplayName("refuses the latest tag")
    void shouldRefuseLatestTag() {
        ImagePolicy policy = policy(List.of(), false);

        assertThatThrownBy(() -> policy.assertAllowed("registry.example.com/team/sandbox:latest"))
                .isInstanceOf(ImagePolicy.ImagePolicyViolationException.class)
                .hasMessageContaining("latest");
        assertThatThrownBy(() -> policy.assertAllowed("ai-dev-sandbox"))
                .isInstanceOf(ImagePolicy.ImagePolicyViolationException.class);
    }

    @Test
    @DisplayName("demands a digest when the platform requires immutable references")
    void shouldRequireDigest() {
        ImagePolicy policy = policy(List.of(), true);

        assertThatThrownBy(() -> policy.assertAllowed("registry.example.com/team/sandbox:21"))
                .isInstanceOf(ImagePolicy.ImagePolicyViolationException.class)
                .hasMessageContaining("digest");
        assertThatCode(() -> policy.assertAllowed("registry.example.com/team/sandbox" + DIGEST))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("refuses an empty image and one carrying a space")
    void shouldRefuseMalformedReference() {
        ImagePolicy policy = policy(List.of(), false);

        assertThatThrownBy(() -> policy.assertAllowed(null)).isInstanceOf(ImagePolicy.ImagePolicyViolationException.class);
        assertThatThrownBy(() -> policy.assertAllowed("image:21 --privileged"))
                .isInstanceOf(ImagePolicy.ImagePolicyViolationException.class);
    }

    /**
     * Docker's own rule, not a heuristic: the first segment is a registry only when it looks like a
     * host. {@code team/image} lives on Docker Hub, and a local image has no registry at all.
     */
    @Test
    @DisplayName("reads the registry the way Docker does")
    void shouldExtractRegistry() {
        ImagePolicy policy = policy(List.of(), false);

        assertThat(policy.registryOf("registry.example.com/team/image:1")).isEqualTo("registry.example.com");
        assertThat(policy.registryOf("localhost:5000/team/image:1")).isEqualTo("localhost:5000");
        assertThat(policy.registryOf("team/image:1")).isEmpty();
        assertThat(policy.registryOf("ai-dev-sandbox:21")).isEmpty();
    }

    private static ImagePolicy policy(List<String> allowedRegistries, boolean requireDigest) {
        SandboxProperties sandbox = new SandboxProperties(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                allowedRegistries, requireDigest);
        return new ImagePolicy(PlatformSettings.builder().sandbox(sandbox).build());
    }
}
