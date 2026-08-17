package com.mel.aidev.api;

import com.mel.aidev.gitlab.GitLabException;
import com.mel.aidev.jira.JiraException;
import com.mel.aidev.project.ProjectNotFoundException;
import com.mel.aidev.project.ProjectValidationException;
import com.mel.aidev.security.BranchPolicy;
import com.mel.aidev.security.ImagePolicy;
import com.mel.aidev.security.SecretRedactor;
import com.mel.aidev.security.WebhookAuthenticator;
import com.mel.aidev.workflow.WorkflowNotFoundException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Maps exceptions to HTTP responses.
 *
 * <p>Messages are redacted before leaving the process: an upstream error body can contain a token,
 * and an API error is exactly the kind of place where nobody looks for one.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final SecretRedactor redactor;

    public GlobalExceptionHandler(SecretRedactor redactor) {
        this.redactor = redactor;
    }

    @ExceptionHandler(WorkflowNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(WorkflowNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of(404, "Not Found", e.getMessage()));
    }

    /**
     * Static-resource misses are normal browser behavior (for example Chrome's optional
     * {@code /.well-known} probes) and must not be reported as server failures.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleMissingResource(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of(404, "Not Found", "Resource not found"));
    }

    @ExceptionHandler(ProjectNotFoundException.class)
    public ResponseEntity<ApiError> handleProjectNotFound(ProjectNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError.of(404, "Not Found", e.getMessage()));
    }

    /**
     * A project the caller described but that cannot exist as described.
     *
     * <p>422 rather than 400: the payload is well formed, it is the world it describes that is not —
     * an unreachable repository, a model outside the allowlist, a command the sandbox refuses.
     */
    @ExceptionHandler(ProjectValidationException.class)
    public ResponseEntity<ApiError> handleProjectValidation(ProjectValidationException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ApiError.of(422, "Unprocessable Entity", redactor.redact(e.getMessage()), e.details()));
    }

    @ExceptionHandler(ImagePolicy.ImagePolicyViolationException.class)
    public ResponseEntity<ApiError> handleImagePolicy(ImagePolicy.ImagePolicyViolationException e) {
        log.warn("Image policy violation: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiError.of(403, "Forbidden", e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException e) {
        List<String> details = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .toList();
        return ResponseEntity.badRequest()
                .body(ApiError.of(400, "Bad Request", "Invalid request payload", details));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> handleConflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of(409, "Conflict", redactor.redact(e.getMessage())));
    }

    @ExceptionHandler(BranchPolicy.BranchPolicyViolationException.class)
    public ResponseEntity<ApiError> handleBranchPolicy(BranchPolicy.BranchPolicyViolationException e) {
        log.warn("Branch policy violation: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiError.of(403, "Forbidden", e.getMessage()));
    }

    @ExceptionHandler(WebhookAuthenticator.WebhookAuthenticationException.class)
    public ResponseEntity<ApiError> handleWebhookAuth(WebhookAuthenticator.WebhookAuthenticationException e) {
        // The reason is logged, not returned: telling a caller why its token was rejected helps only
        // the caller that should not be there.
        log.warn("Webhook authentication failed: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiError.of(401, "Unauthorized", "Webhook authentication failed"));
    }

    @ExceptionHandler({JiraException.class, GitLabException.class})
    public ResponseEntity<ApiError> handleUpstream(RuntimeException e) {
        log.error("Upstream call failed", e);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ApiError.of(502, "Bad Gateway", redactor.redact(e.getMessage())));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception e) {
        log.error("Unexpected error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of(500, "Internal Server Error", "Unexpected error"));
    }
}
