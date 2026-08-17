package com.mel.aidev.persistence.repository;

import com.mel.aidev.persistence.entity.WorkflowEntity;
import com.mel.aidev.workflow.WorkflowStatus;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/**
 * Filters of the workflow listings.
 *
 * <p>Written as specifications rather than as one query with optional parameters. The usual
 * {@code (:status is null or w.status = :status)} idiom binds the same parameter twice, once with no
 * type context; PostgreSQL then refuses the statement with "could not determine data type", while
 * H2 accepts it — so the failure would only ever appear in production. A specification emits the
 * predicates that were actually asked for and nothing else.
 */
public final class WorkflowSpecifications {

    private WorkflowSpecifications() {}

    /**
     * @param projectId owning project, null for the console listing across projects
     * @param status exact status, null for all of them
     * @param jiraTicket case-insensitive fragment of the ticket, null or blank for all
     * @param from inclusive lower bound on the creation date, null for none
     * @param to exclusive upper bound on the creation date, null for none
     * @param includeArchived when false, archived workflows are hidden
     */
    public static Specification<WorkflowEntity> filtered(
            UUID projectId,
            WorkflowStatus status,
            String jiraTicket,
            Instant from,
            Instant to,
            boolean includeArchived) {

        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (projectId != null) {
                predicates.add(builder.equal(root.get("projectId"), projectId));
            }
            if (status != null) {
                predicates.add(builder.equal(root.get("status"), status));
            }
            if (jiraTicket != null && !jiraTicket.isBlank()) {
                predicates.add(builder.like(
                        builder.lower(root.get("jiraTicket")),
                        "%" + jiraTicket.trim().toLowerCase(Locale.ROOT) + "%"));
            }
            if (from != null) {
                predicates.add(builder.greaterThanOrEqualTo(root.get("createdAt"), from));
            }
            if (to != null) {
                predicates.add(builder.lessThan(root.get("createdAt"), to));
            }
            if (!includeArchived) {
                // Archiving exists to shorten the list without destroying the audit trail, so it is
                // the default of every listing rather than something a caller has to remember.
                predicates.add(builder.isNull(root.get("archivedAt")));
            }
            return predicates.isEmpty() ? builder.conjunction() : builder.and(predicates.toArray(new Predicate[0]));
        };
    }
}
