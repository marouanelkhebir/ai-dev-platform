package com.mel.aidev.jira;

import com.mel.aidev.config.JiraProperties;
import com.mel.aidev.config.WorkflowProperties;
import com.mel.aidev.persistence.entity.ProjectEntity;
import com.mel.aidev.persistence.entity.WorkflowEntity;
import com.mel.aidev.project.ProjectService;
import com.mel.aidev.security.WebhookAuthenticator;
import com.mel.aidev.workflow.DevelopmentWorkflowService;
import com.mel.aidev.workflow.WebhookIdempotencyService;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Entry point for Jira webhooks.
 *
 * <p>A workflow starts only when the ticket is explicitly opted in: the configured label is present,
 * or the ticket moved into the configured status. Anything else is acknowledged and ignored — a
 * webhook that fires on every field change must not turn every edit into a merge request.
 */
@RestController
@RequestMapping("/webhooks/jira")
public class JiraWebhookController {

    private static final Logger log = LoggerFactory.getLogger(JiraWebhookController.class);

    /** Custom field on the Jira issue naming the GitLab project to work on. */
    private static final String PROJECT_FIELD_LABEL_PREFIX = "gitlab-project:";

    private final JiraProperties jiraProperties;
    private final WorkflowProperties workflowProperties;
    private final WebhookAuthenticator authenticator;
    private final WebhookIdempotencyService idempotency;
    private final DevelopmentWorkflowService workflowService;
    private final ProjectService projectService;

    public JiraWebhookController(
            JiraProperties jiraProperties,
            WorkflowProperties workflowProperties,
            WebhookAuthenticator authenticator,
            WebhookIdempotencyService idempotency,
            DevelopmentWorkflowService workflowService,
            ProjectService projectService) {
        this.jiraProperties = jiraProperties;
        this.workflowProperties = workflowProperties;
        this.authenticator = authenticator;
        this.idempotency = idempotency;
        this.workflowService = workflowService;
        this.projectService = projectService;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> receive(
            @RequestHeader(value = "X-Webhook-Token", required = false) String token, @RequestBody JsonNode payload) {

        authenticator.verify(jiraProperties.webhook().secret(), token, "jira");

        String issueKey = payload.path("issue").path("key").asText(null);
        String eventType = payload.path("webhookEvent").asText("unknown");
        if (issueKey == null || issueKey.isBlank()) {
            return ResponseEntity.ok(Map.of("status", "ignored", "reason", "no issue key in payload"));
        }

        String externalId = issueKey + ":" + eventType + ":" + payload.path("timestamp").asText("0");
        if (!idempotency.registerIfNew("jira", externalId, eventType, payload.toString())) {
            return ResponseEntity.ok(Map.of("status", "duplicate", "issue", issueKey));
        }

        if (!workflowProperties.autoStartFromJiraWebhook()) {
            return ResponseEntity.ok(Map.of("status", "ignored", "reason", "auto-start disabled"));
        }
        if (!isEligible(payload)) {
            return ResponseEntity.ok(Map.of("status", "ignored", "issue", issueKey, "reason", "not opted in"));
        }

        Optional<ProjectEntity> project = resolveProject(issueKey, payload);
        if (project.isEmpty()) {
            log.warn("Ticket {} is eligible but no project could be resolved", issueKey);
            return ResponseEntity.ok(Map.of(
                    "status", "ignored",
                    "issue", issueKey,
                    "reason", "no project resolved; declare the Jira key " + jiraKeyOf(issueKey)
                            + " on a project, or add a '" + PROJECT_FIELD_LABEL_PREFIX + "<project>' label to the ticket"));
        }

        WorkflowEntity workflow = workflowService.createOrGetActive(project.get(), issueKey);
        workflowService.startAsync(workflow.getId());

        log.info("Workflow {} triggered by Jira webhook for {}", workflow.getId(), issueKey);
        return ResponseEntity.accepted()
                .body(Map.of("status", "accepted", "issue", issueKey, "workflowId", workflow.getId().toString()));
    }

    /** The ticket opts in through the configured label or the configured status. */
    private boolean isEligible(JsonNode payload) {
        JsonNode fields = payload.path("issue").path("fields");

        String triggerLabel = jiraProperties.trigger().label();
        for (JsonNode label : fields.path("labels")) {
            if (label.asText("").equalsIgnoreCase(triggerLabel)) {
                return true;
            }
        }

        String status = fields.path("status").path("name").asText("");
        return status.equalsIgnoreCase(jiraProperties.trigger().status());
    }

    /**
     * Finds the project a ticket belongs to.
     *
     * <p>By its Jira key first — {@code BANK-1245} belongs to the project that declares {@code BANK},
     * and nothing has to be written on the ticket. The {@code gitlab-project:} label is only used to
     * disambiguate when several projects claim the same key, which is what it was invented for.
     */
    private Optional<ProjectEntity> resolveProject(String issueKey, JsonNode payload) {
        List<ProjectEntity> byKey = projectService.findStartableByJiraProjectKey(jiraKeyOf(issueKey));
        if (byKey.size() == 1) {
            return Optional.of(byKey.get(0));
        }
        Optional<String> labelled = resolveGitLabProject(payload);
        if (labelled.isEmpty()) {
            if (byKey.size() > 1) {
                log.warn(
                        "Ticket {} matches {} projects on its Jira key; add a '{}<project>' label to choose one",
                        issueKey,
                        byKey.size(),
                        PROJECT_FIELD_LABEL_PREFIX);
            }
            return Optional.empty();
        }
        List<ProjectEntity> candidates = byKey.isEmpty()
                ? projectService.findStartableByGitlabProject(labelled.get())
                : byKey.stream()
                        .filter(project -> project.getGitlabProject().equals(labelled.get()))
                        .toList();
        return candidates.size() == 1 ? Optional.of(candidates.get(0)) : Optional.empty();
    }

    /** Project key part of an issue key: {@code BANK-1245} → {@code BANK}. */
    private static String jiraKeyOf(String issueKey) {
        int dash = issueKey.indexOf('-');
        return dash < 0 ? issueKey.toUpperCase(Locale.ROOT) : issueKey.substring(0, dash).toUpperCase(Locale.ROOT);
    }

    /**
     * Reads the {@code gitlab-project:<path>} label.
     *
     * <p>A label keeps the mapping visible on the ticket itself, which matters when a human has to
     * understand why the platform pushed to a given repository.
     */
    private Optional<String> resolveGitLabProject(JsonNode payload) {
        for (JsonNode label : payload.path("issue").path("fields").path("labels")) {
            String value = label.asText("");
            if (value.toLowerCase(Locale.ROOT).startsWith(PROJECT_FIELD_LABEL_PREFIX)) {
                String project = value.substring(PROJECT_FIELD_LABEL_PREFIX.length()).trim();
                if (!project.isBlank()) {
                    return Optional.of(project);
                }
            }
        }
        return Optional.empty();
    }
}
