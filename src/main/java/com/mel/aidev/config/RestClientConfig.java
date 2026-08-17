package com.mel.aidev.config;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.DefaultUriBuilderFactory;

/**
 * HTTP clients for Jira and GitLab.
 *
 * <p>Neither is a bean. Both integrations are configured from the settings screen, usually after the
 * first boot and repeatedly afterwards; a client built once at startup would keep the values it was
 * born with while the screen displays the current ones. Callers build one per call from an effective
 * settings snapshot instead — the cost is a builder invocation, and the alternative is a platform
 * that talks to the wrong instance without saying so.
 *
 * <p>Both clients declare explicit connect and read timeouts. An agent platform that hangs on a
 * socket read holds a worker thread and a Postgres row lock for as long as the peer stays silent,
 * which is the fastest way to wedge the whole engine.
 */
public final class RestClientConfig {

    private static final Logger log = LoggerFactory.getLogger(RestClientConfig.class);

    private static final int MAX_CONNECTIONS = 100;
    private static final int MAX_CONNECTIONS_PER_ROUTE = 25;

    /**
     * Beyond this many distinct timeout pairs, stop pooling rather than accumulate transports.
     *
     * <p>Each entry owns a connection pool and an eviction thread, and the key comes from settings a
     * human can edit at runtime. Two or three pairs is the realistic number — Jira, GitLab and
     * Bitbucket all default to the same one — so reaching the cap means something is wrong, and the
     * unpooled fallback keeps the platform working instead of leaking transports until it does not.
     */
    private static final int MAX_POOLED_TRANSPORTS = 16;

    /** One pooled transport per distinct (connect, read) timeout pair. */
    private static final Map<TimeoutKey, ClientHttpRequestFactory> TRANSPORTS = new ConcurrentHashMap<>();

    private RestClientConfig() {}

    /**
     * Builds a Jira client from an effective settings snapshot.
     *
     * <p>Not a bean, for the same reason as {@link #gitlabRestClient(GitLabProperties)}: Jira is
     * almost always configured from the settings screen <em>after</em> the first boot, and a
     * singleton built here at startup would keep pointing at the bootstrap URL with an obsolete
     * token — while the screen shows the new values, which is the worst of both.
     */
    public static RestClient jiraRestClient(JiraProperties properties) {
        if (!properties.isConfigured()) {
            throw new IllegalStateException(
                    "Jira is not configured: set the URL, the technical account and the API token in the settings screen");
        }
        String credentials = properties.email() + ":" + properties.apiToken();
        String basicAuth = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));

        return RestClient.builder()
                .baseUrl(properties.apiBaseUrl())
                .requestFactory(requestFactory(properties.connectTimeout(), properties.readTimeout()))
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + basicAuth)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /**
     * Builds a GitLab client from an effective settings snapshot.
     *
     * <p>This intentionally is not a bean: GitLab settings can be changed from the settings
     * screen after the application has started. A singleton client constructed here at startup
     * would retain the bootstrap fallback URL and an obsolete token.
     */
    public static RestClient gitlabRestClient(GitLabProperties properties) {
        if (!properties.isConfigured()) {
            throw new IllegalStateException("GitLab is not configured: set the URL and API token in the settings screen");
        }
        // GitLab identifies projects and file paths as URL-encoded segments ("group%2Fproject").
        // The default TEMPLATE_AND_VALUES mode would re-encode the percent signs, so encoding is done
        // explicitly in RestGitLabClient and disabled here.
        DefaultUriBuilderFactory uriBuilderFactory = new DefaultUriBuilderFactory(properties.apiBaseUrl());
        uriBuilderFactory.setEncodingMode(DefaultUriBuilderFactory.EncodingMode.NONE);

        return RestClient.builder()
                .uriBuilderFactory(uriBuilderFactory)
                .requestFactory(requestFactory(properties.connectTimeout(), properties.readTimeout()))
                .defaultHeader("PRIVATE-TOKEN", properties.apiToken())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /**
     * A pooled transport for the given timeouts, shared by every client built with them.
     *
     * <p>The {@link RestClient} wrapper above is rebuilt per call, which is cheap and is what keeps a
     * re-configured token from being ignored. The transport underneath must <em>not</em> follow it:
     * with an unpooled factory every Jira and GitLab call paid a fresh TCP handshake and a fresh TLS
     * handshake, and a single workflow makes dozens of them. Pooling here reuses the connection while
     * leaving the per-call rebuild — and the settings freshness it buys — untouched.
     */
    public static ClientHttpRequestFactory requestFactory(Duration connectTimeout, Duration readTimeout) {
        return requestFactory(connectTimeout, readTimeout, true);
    }

    /**
     * Same transport, with the option of surfacing redirects to the caller.
     *
     * <p>GitHub answers the Actions log endpoint with a 302 to a pre-signed storage URL, and that URL
     * rejects a request that still carries our {@code Authorization} header. Following the redirect
     * transparently would therefore turn a readable log into an HTTP error, so that one call handles
     * the hop itself.
     */
    public static ClientHttpRequestFactory requestFactory(
            Duration connectTimeout, Duration readTimeout, boolean followRedirects) {
        TimeoutKey key = new TimeoutKey(connectTimeout, readTimeout, followRedirects);
        ClientHttpRequestFactory existing = TRANSPORTS.get(key);
        if (existing != null) {
            return existing;
        }
        if (TRANSPORTS.size() >= MAX_POOLED_TRANSPORTS) {
            log.warn(
                    "Reached {} distinct HTTP timeout configurations; serving connect={} read={} unpooled",
                    MAX_POOLED_TRANSPORTS,
                    connectTimeout,
                    readTimeout);
            return unpooledFactory(connectTimeout, readTimeout);
        }
        return TRANSPORTS.computeIfAbsent(key, RestClientConfig::buildPooledFactory);
    }

    private static ClientHttpRequestFactory buildPooledFactory(TimeoutKey key) {
        var connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(MAX_CONNECTIONS)
                .setMaxConnPerRoute(MAX_CONNECTIONS_PER_ROUTE)
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.of(key.connectTimeout()))
                        .setSocketTimeout(Timeout.of(key.readTimeout()))
                        // A pooled connection can be closed by the peer or by an intermediate proxy
                        // without us hearing about it; revalidate one that has been idle rather than
                        // discover it on a request that then has to be retried.
                        .setValidateAfterInactivity(TimeValue.ofSeconds(10))
                        .setTimeToLive(TimeValue.ofMinutes(5))
                        .build())
                .build();

        var clientBuilder = HttpClients.custom();
        if (!key.followRedirects()) {
            clientBuilder.disableRedirectHandling();
        }
        var httpClient = clientBuilder
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(RequestConfig.custom()
                        // Waiting for a pooled slot is not waiting for the peer: bound it separately
                        // so a saturated pool surfaces as a fast failure instead of a silent stall.
                        .setConnectionRequestTimeout(Timeout.of(key.connectTimeout()))
                        .setResponseTimeout(Timeout.of(key.readTimeout()))
                        .build())
                .evictIdleConnections(TimeValue.ofMinutes(1))
                .build();

        log.info(
                "HTTP transport pooled connect={} read={} redirects={} maxTotal={} maxPerRoute={}",
                key.connectTimeout(),
                key.readTimeout(),
                key.followRedirects(),
                MAX_CONNECTIONS,
                MAX_CONNECTIONS_PER_ROUTE);
        return new HttpComponentsClientHttpRequestFactory(httpClient);
    }

    private static ClientHttpRequestFactory unpooledFactory(Duration connectTimeout, Duration readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) connectTimeout.toMillis());
        factory.setReadTimeout((int) readTimeout.toMillis());
        return factory;
    }

    /** Identifies a transport. Two clients with the same timeouts share one connection pool. */
    private record TimeoutKey(Duration connectTimeout, Duration readTimeout, boolean followRedirects) {}
}
