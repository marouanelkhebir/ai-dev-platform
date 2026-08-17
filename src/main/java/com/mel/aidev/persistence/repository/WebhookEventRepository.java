package com.mel.aidev.persistence.repository;

import com.mel.aidev.persistence.entity.WebhookEventEntity;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WebhookEventRepository extends JpaRepository<WebhookEventEntity, UUID> {

    boolean existsBySourceAndExternalId(String source, String externalId);

    @Modifying
    @Query("delete from WebhookEventEntity e where e.receivedAt < :before")
    int deleteOlderThan(@Param("before") Instant before);
}
