package com.mel.aidev.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.mel.aidev.settings.PlatformSettings;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Protects {@code /api/**} with the current platform API key.
 *
 * <p>Deliberately minimal: this service is internal and sits behind the company gateway. What
 * matters here is that {@code POST /api/workflows} — which spends GPU time and pushes to GitLab —
 * cannot be called by anything on the network that finds the port open.
 */
public class ApiKeyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyFilter.class);
    private static final String HEADER = "X-Api-Key";

    private final Supplier<String> apiKeySupplier;

    public ApiKeyFilter(PlatformSettings settings) {
        this(() -> settings.platform().apiKey());
    }

    /** Kept for focused filter tests and fixed-key deployments. */
    public ApiKeyFilter(String apiKey) {
        this(() -> apiKey);
    }

    private ApiKeyFilter(Supplier<String> apiKeySupplier) {
        this.apiKeySupplier = apiKeySupplier;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String apiKey = apiKeySupplier.get();
        if (apiKey == null || apiKey.isBlank()) {
            // No key configured: refuse rather than expose the API unauthenticated.
            reject(response, "API key is not configured on the server");
            return;
        }
        String presented = request.getHeader(HEADER);
        if (presented == null
                || !MessageDigest.isEqual(
                        apiKey.getBytes(StandardCharsets.UTF_8), presented.getBytes(StandardCharsets.UTF_8))) {
            log.warn("Rejected API call to {} without a valid API key", request.getRequestURI());
            reject(response, "Missing or invalid API key");
            return;
        }
        chain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/");
    }

    private static void reject(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"status\":401,\"error\":\"Unauthorized\",\"message\":\"" + message + "\"}");
    }

    /**
     * Registers the filter for {@code /api/*} only. The key is read for every request so a key
     * changed in the settings screen takes effect immediately.
     *
     * <p>The filter is not a {@code @Component}: Spring Boot would then also auto-register it for
     * every URL, and it would run twice per request.
     */
    @Configuration
    public static class Registration {

        @Bean
        public FilterRegistrationBean<ApiKeyFilter> apiKeyFilterRegistration(
                PlatformSettings settings) {
            FilterRegistrationBean<ApiKeyFilter> registration = new FilterRegistrationBean<>(new ApiKeyFilter(settings));
            registration.addUrlPatterns("/api/*");
            registration.setOrder(1);
            return registration;
        }
    }
}
