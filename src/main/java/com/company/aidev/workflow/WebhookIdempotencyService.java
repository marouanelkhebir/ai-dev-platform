package com.company.aidev.workflow;

import com.company.aidev.persistence.entity.WebhookEventEntity;
import com.company.aidev.persistence.repository.WebhookEventRepository;
import com.company.aidev.security.SecretRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Makes webhook processing idempotent.
 *
 * <p>Jira and GitLab both retry deliveries, and GitLab in particular re-sends the same pipeline
 * event on retryable errors. The unique constraint on {@code (source, external_id)} turns a
 * duplicate into a no-op; the insert is attempted first and its failure is the answer, which is
 * race-free where a "check then insert" would not be.
 */
@Service
public class WebhookIdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(WebhookIdempotencyService.class);
    private static final int MAX_PAYLOAD_CHARS = 50_000;

    private final WebhookEventStore store;
    private final SecretRedactor redactor;

    public WebhookIdempotencyService(WebhookEventStore store, SecretRedactor redactor) {
        this.store = store;
        this.redactor = redactor;
    }

    /**
     * Registers an event.
     *
     * @return true when the event is new and should be processed, false when it is a duplicate
     */
    public boolean registerIfNew(String source, String externalId, String eventType, String payload) {
        try {
            store.insert(source, externalId, eventType, redactor.redactAndTruncate(payload, MAX_PAYLOAD_CHARS));
            return true;
        } catch (DataAccessException e) {
            // Caught outside the transaction on purpose: swallowing a constraint violation inside it
            // would leave the transaction marked rollback-only and fail at commit instead.
            log.info("Duplicate {} webhook ignored: {} ({})", source, externalId, eventType);
            return false;
        }
    }

    /**
     * Insert in its own transaction.
     *
     * <p>Separate bean so that the constraint violation propagates through the transactional proxy
     * and rolls the insert back, leaving the caller free to interpret it.
     */
    @Component
    public static class WebhookEventStore {

        private final WebhookEventRepository repository;

        public WebhookEventStore(WebhookEventRepository repository) {
            this.repository = repository;
        }

        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public void insert(String source, String externalId, String eventType, String payload) {
            repository.saveAndFlush(new WebhookEventEntity(source, externalId, eventType, payload));
        }
    }
}
