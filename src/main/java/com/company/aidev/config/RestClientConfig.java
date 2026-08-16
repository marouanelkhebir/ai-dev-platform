package com.company.aidev.config;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.DefaultUriBuilderFactory;

/**
 * HTTP clients for Jira and GitLab.
 *
 * <p>Both clients declare explicit connect and read timeouts. An agent platform that hangs on a
 * socket read holds a worker thread and a Postgres row lock for as long as the peer stays silent,
 * which is the fastest way to wedge the whole engine.
 */
@Configuration
public class RestClientConfig {

    @Bean
    public RestClient jiraRestClient(JiraProperties properties) {
        String credentials = properties.email() + ":" + properties.apiToken();
        String basicAuth = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));

        return RestClient.builder()
                // A fresh installation has no Jira credentials yet. Keep a client bean available so
                // the settings screen can be reached; readiness prevents the integration being used.
                .baseUrl(properties.isConfigured() ? properties.apiBaseUrl() : "http://localhost")
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
        // See the equivalent Jira fallback above: integrations are configured after first boot.
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
