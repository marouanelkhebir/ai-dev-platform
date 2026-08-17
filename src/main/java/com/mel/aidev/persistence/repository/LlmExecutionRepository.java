package com.mel.aidev.persistence.repository;

import com.mel.aidev.persistence.entity.LlmExecutionEntity;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LlmExecutionRepository extends JpaRepository<LlmExecutionEntity, UUID> {

    List<LlmExecutionEntity> findByWorkflowIdOrderByCreatedAtAsc(UUID workflowId);

    /** Token and cost totals of a project over a period. */
    @Query(
            """
            select coalesce(sum(l.promptTokens), 0)     as promptTokens,
                   coalesce(sum(l.completionTokens), 0) as completionTokens,
                   coalesce(sum(l.totalTokens), 0)      as totalTokens,
                   coalesce(sum(l.costMicros), 0)       as costMicros,
                   count(l)                             as calls
            from LlmExecutionEntity l
            where l.projectId = :projectId
              and l.createdAt >= :from and l.createdAt < :to
            """)
    TokenTotals totalsFor(
            @Param("projectId") UUID projectId, @Param("from") Instant from, @Param("to") Instant to);

    @Query(
            """
            select l.model                              as label,
                   coalesce(sum(l.totalTokens), 0)      as totalTokens,
                   coalesce(sum(l.costMicros), 0)       as costMicros,
                   count(l)                             as calls
            from LlmExecutionEntity l
            where l.projectId = :projectId
              and l.createdAt >= :from and l.createdAt < :to
            group by l.model
            order by coalesce(sum(l.totalTokens), 0) desc
            """)
    List<Breakdown> breakdownByModel(
            @Param("projectId") UUID projectId, @Param("from") Instant from, @Param("to") Instant to);

    @Query(
            """
            select l.agent                              as label,
                   coalesce(sum(l.totalTokens), 0)      as totalTokens,
                   coalesce(sum(l.costMicros), 0)       as costMicros,
                   count(l)                             as calls
            from LlmExecutionEntity l
            where l.projectId = :projectId
              and l.createdAt >= :from and l.createdAt < :to
            group by l.agent
            order by coalesce(sum(l.totalTokens), 0) desc
            """)
    List<Breakdown> breakdownByAgent(
            @Param("projectId") UUID projectId, @Param("from") Instant from, @Param("to") Instant to);

    /**
     * Models whose calls were recorded without a price.
     *
     * <p>Reported separately rather than folded into the total: a cost that silently ignores part of
     * the traffic is worse than no cost at all.
     */
    @Query(
            """
            select distinct l.model from LlmExecutionEntity l
            where l.projectId = :projectId
              and l.createdAt >= :from and l.createdAt < :to
              and l.costMicros is null
              and l.successful = true
              and l.model is not null
            """)
    List<String> unpricedModels(
            @Param("projectId") UUID projectId, @Param("from") Instant from, @Param("to") Instant to);

    /** Attaches the project to rows written before the workflow knew it, e.g. after a backfill. */
    @org.springframework.data.jpa.repository.Modifying
    @Query("update LlmExecutionEntity l set l.projectId = :projectId where l.workflowId = :workflowId and l.projectId is null")
    int assignProject(@Param("workflowId") UUID workflowId, @Param("projectId") UUID projectId);

    /** Aggregated token and cost totals. */
    interface TokenTotals {
        long getPromptTokens();

        long getCompletionTokens();

        long getTotalTokens();

        long getCostMicros();

        long getCalls();
    }

    /** Totals grouped by model or by agent. */
    interface Breakdown {
        String getLabel();

        long getTotalTokens();

        long getCostMicros();

        long getCalls();
    }
}
