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

    @Test
    void letsTheUserStartAWorkflowFromATicketOrDirectMessage() throws IOException {
        try (var page = getClass().getResourceAsStream("/static/index.html")) {
            assertThat(page).as("console page resource").isNotNull();

            String html = new String(page.readAllBytes(), StandardCharsets.UTF_8);

            assertThat(html).contains("Message direct");
            assertThat(html).contains("/api/workflows/message");
            assertThat(html).contains("id=\"directMessage\"");
        }
    }

    @Test
    void letsTheUserClarifyAndResumeABlockedWorkflow() throws IOException {
        try (var page = getClass().getResourceAsStream("/static/index.html")) {
            assertThat(page).as("console page resource").isNotNull();

            String html = new String(page.readAllBytes(), StandardCharsets.UTF_8);

            assertThat(html).contains("Compléter la demande");
            assertThat(html).contains("/clarification");
            assertThat(html).contains("Enregistrer et reprendre");
            assertThat(html).contains("Retour de l’IA");
            assertThat(html).contains("Ce que vous devez préciser");
        }
    }
}
