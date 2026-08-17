package com.mel.aidev.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Verifies the shared secret carried by incoming webhooks.
 *
 * <p>The comparison is constant time. A webhook endpoint that starts a workflow — and therefore a
 * container, a GPU budget and a push to GitLab — is an attractive target, and a timing oracle on the
 * secret is a cheap mistake to avoid.
 */
@Component
public class WebhookAuthenticator {

    private static final Logger log = LoggerFactory.getLogger(WebhookAuthenticator.class);

    /**
     * @param configuredSecret the expected secret, or {@code null} when none is configured
     * @param presentedToken the token received in the request header
     * @param source label used for logging
     * @throws WebhookAuthenticationException when the token does not match
     */
    public void verify(String configuredSecret, String presentedToken, String source) {
        if (configuredSecret == null || configuredSecret.isBlank()) {
            // Refusing to start is safer than silently accepting anonymous calls: a missing secret is
            // a deployment mistake, not a supported mode.
            throw new WebhookAuthenticationException(
                    "No webhook secret configured for " + source + "; refusing to process the request");
        }
        if (presentedToken == null || presentedToken.isBlank()) {
            log.warn("Rejected {} webhook without a token", source);
            throw new WebhookAuthenticationException("Missing webhook token");
        }
        boolean matches = MessageDigest.isEqual(
                configuredSecret.getBytes(StandardCharsets.UTF_8), presentedToken.getBytes(StandardCharsets.UTF_8));
        if (!matches) {
            log.warn("Rejected {} webhook with an invalid token", source);
            throw new WebhookAuthenticationException("Invalid webhook token");
        }
    }

    /** Raised when a webhook cannot be authenticated. */
    public static class WebhookAuthenticationException extends RuntimeException {
        public WebhookAuthenticationException(String message) {
            super(message);
        }
    }
}
