package com.company.aidev.gitlab;

import com.company.aidev.config.GitLabProperties;
import com.company.aidev.security.WebhookAuthenticator;
import com.company.aidev.workflow.DevelopmentWorkflowService;
import com.company.aidev.workflow.WebhookIdempotencyService;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Entry point for GitLab webhooks.
 *
 * <p>Merge request events can close workflows that explicitly wait for human approval. Pipeline
 * events do not resume a workflow: opening the merge request is terminal.
 */
@RestController
@RequestMapping("/webhooks/gitlab")
public class GitLabWebhookController {

    private static final Logger log = LoggerFactory.getLogger(GitLabWebhookController.class);

    private final GitLabProperties properties;
    private final WebhookAuthenticator authenticator;
    private final WebhookIdempotencyService idempotency;
    private final DevelopmentWorkflowService workflowService;

    public GitLabWebhookController(
            GitLabProperties properties,
            WebhookAuthenticator authenticator,
            WebhookIdempotencyService idempotency,
            DevelopmentWorkflowService workflowService) {
        this.properties = properties;
        this.authenticator = authenticator;
        this.idempotency = idempotency;
        this.workflowService = workflowService;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> receive(
            @RequestHeader(value = "X-Gitlab-Token", required = false) String token,
            @RequestHeader(value = "X-Gitlab-Event", required = false) String event,
            @RequestBody JsonNode payload) {

        authenticator.verify(properties.webhook().secret(), token, "gitlab");

        String objectKind = payload.path("object_kind").asText("");
        return switch (objectKind) {
            case "merge_request" -> handleMergeRequest(payload);
            default -> ResponseEntity.ok(Map.of("status", "ignored", "event", objectKind.isBlank() ? String.valueOf(event) : objectKind));
        };
    }

    private ResponseEntity<Map<String, Object>> handleMergeRequest(JsonNode payload) {
        JsonNode attributes = payload.path("object_attributes");
        long iid = attributes.path("iid").asLong();
        String action = attributes.path("action").asText("");
        String project = payload.path("project").path("path_with_namespace").asText("");
        String user = payload.path("user").path("username").asText("unknown");

        if (!idempotency.registerIfNew(
                "gitlab", "merge_request:" + project + ":" + iid + ":" + action, "merge_request", payload.toString())) {
            return ResponseEntity.ok(Map.of("status", "duplicate", "mergeRequest", iid));
        }

        boolean humanValidation = "approved".equals(action) || "merge".equals(action);
        if (!humanValidation) {
            return ResponseEntity.ok(Map.of("status", "ignored", "action", action));
        }

        boolean resumed = workflowService.onMergeRequestApproved(project, iid, user);
        log.info("Merge request webhook project={} iid={} action={} by={} resumed={}", project, iid, action, user, resumed);
        return ResponseEntity.ok(Map.of("status", resumed ? "approved" : "ignored", "mergeRequest", iid));
    }
}
