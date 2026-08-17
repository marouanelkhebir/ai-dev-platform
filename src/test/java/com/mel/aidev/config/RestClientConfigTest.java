package com.mel.aidev.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;

/**
 * The transport underneath the per-call {@code RestClient} must be shared.
 *
 * <p>{@link RestClientConfig} rebuilds the {@code RestClient} wrapper on every call so a token
 * changed in the settings screen takes effect at once. That is only affordable because the transport
 * below it is reused: a fresh one per call means a TCP and TLS handshake per Jira or GitLab request,
 * and a single workflow makes dozens of them.
 */
class RestClientConfigTest {

    @Test
    @DisplayName("clients with the same timeouts share one pooled transport")
    void shouldReuseTransportForIdenticalTimeouts() {
        var first = RestClientConfig.requestFactory(Duration.ofSeconds(5), Duration.ofSeconds(30));
        var second = RestClientConfig.requestFactory(Duration.ofSeconds(5), Duration.ofSeconds(30));

        assertThat(first).isSameAs(second);
    }

    @Test
    @DisplayName("the transport pools connections rather than opening one per request")
    void shouldUsePooledTransport() {
        var factory = RestClientConfig.requestFactory(Duration.ofSeconds(7), Duration.ofSeconds(21));

        assertThat(factory).isInstanceOf(HttpComponentsClientHttpRequestFactory.class);
    }

    @Test
    @DisplayName("different timeouts get their own transport, so one integration cannot impose its timeouts on another")
    void shouldNotShareTransportAcrossTimeouts() {
        var fast = RestClientConfig.requestFactory(Duration.ofSeconds(1), Duration.ofSeconds(2));
        var slow = RestClientConfig.requestFactory(Duration.ofSeconds(9), Duration.ofSeconds(99));

        assertThat(fast).isNotSameAs(slow);
    }
}
