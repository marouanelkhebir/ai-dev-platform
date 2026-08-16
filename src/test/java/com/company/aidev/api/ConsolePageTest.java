package com.company.aidev.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ConsolePageTest {

    @Test
    void hidesTheConnectionScreenAfterSuccessfulAuthentication() throws IOException {
        String html = page();

        assertThat(html).contains("$('#setup').remove(); $('#app').hidden = false;");
        assertThat(html).contains("localStorage.removeItem('aidev-api-key'); window.location.reload();");
    }

    @Test
    void letsTheUserStartAWorkflowFromATicketOrDirectMessage() throws IOException {
        String html = page();

        assertThat(html).contains("Message direct");
        assertThat(html).contains("/api/workflows/message");
        assertThat(html).contains("id=\"directMessage\"");
    }

    @Test
    void opensEachWorkflowOnItsOwnDetailPage() throws IOException {
        String html = page();

        assertThat(html).contains("/workflow.html?id=");
        assertThat(html).contains("<a class=\"workflow\"");
        // Creating a workflow lands on its live detail page rather than on an inline panel.
        assertThat(html).contains("window.location.href = detailUrl(workflow);");
    }

    @Test
    void keepsTheListInSyncWithTheServerEventStream() throws IOException {
        String html = page();

        assertThat(html).contains("/api/workflows/events");
        assertThat(html).contains("text/event-stream");
        assertThat(html).contains("En direct");
    }

    private static String page() throws IOException {
        try (var resource = ConsolePageTest.class.getResourceAsStream("/static/index.html")) {
            assertThat(resource).as("console page resource").isNotNull();
            return new String(resource.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
