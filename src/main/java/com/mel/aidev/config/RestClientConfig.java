package com.mel.aidev.config;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
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
                .requestFactory(requestFactory(
                        (int) properties.connectTimeout().toMillis(), (int) properties.readTimeout().toMillis()))
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
                .requestFactory(requestFactory(
                        (int) properties.connectTimeout().toMillis(), (int) properties.readTimeout().toMillis()))
                .defaultHeader("PRIVATE-TOKEN", properties.apiToken())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    private static ClientHttpRequestFactory requestFactory(int connectTimeoutMs, int readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        return factory;
    }
}
