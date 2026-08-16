package com.company.aidev.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ConsolePageTest {

    @Test
    void hidesTheConnectionScreenAfterSuccessfulAuthentication() throws IOException {
        try (var page = getClass().getResourceAsStream("/static/index.html")) {
            assertThat(page).as("console page resource").isNotNull();

            String html = new String(page.readAllBytes(), StandardCharsets.UTF_8);

            assertThat(html).contains("$('#setup').remove(); $('#app').hidden = false;");
            assertThat(html).contains("localStorage.removeItem('aidev-api-key'); window.location.reload();");
        }
    }
}
