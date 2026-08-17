package com.mel.aidev.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.mel.aidev.workflow.WorkflowStatus;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Guards the console page an operator watches while a workflow runs. */
class WorkflowDetailPageTest {

    @Test
    void followsTheWorkflowThroughTheServerEventStream() throws IOException {
        String html = page();

        assertThat(html).contains("/events");
        assertThat(html).contains("text/event-stream");
        assertThat(html).contains("En direct");
        assertThat(html).contains("Reconnexion…");
    }

    @Test
    void drawsThePipelineAndTheStepDurations() throws IOException {
        String html = page();

        assertThat(html).contains("Progression");
        assertThat(html).contains("Durée des étapes");
        assertThat(html).contains("class=\"pipeline\"");
        assertThat(html).contains("class=\"bars\"");
    }

    @Test
    void namesEveryStateTheEngineCanReach() throws IOException {
        String html = page();

        for (WorkflowStatus status : WorkflowStatus.values()) {
            assertThat(html).as("pipeline label for %s", status).contains("'" + status.name() + "'");
        }
    }

    @Test
    void letsTheUserClarifyAndResumeABlockedWorkflow() throws IOException {
        String html = page();

        assertThat(html).contains("Compléter la demande");
        assertThat(html).contains("/clarification");
        assertThat(html).contains("Enregistrer et reprendre");
        assertThat(html).contains("Retour de l’IA");
        assertThat(html).contains("Ce que vous devez préciser");
    }

    private static String page() throws IOException {
        try (var resource = WorkflowDetailPageTest.class.getResourceAsStream("/static/workflow.html")) {
            assertThat(resource).as("workflow detail page resource").isNotNull();
            return new String(resource.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
