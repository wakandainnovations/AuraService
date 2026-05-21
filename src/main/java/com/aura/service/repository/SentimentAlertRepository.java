package com.aura.service.repository;

import com.aura.service.entity.SentimentAlert;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;

@Repository
public interface SentimentAlertRepository extends JpaRepository<SentimentAlert, Long> {

    boolean existsByManagedEntityIdAndStatusAndTriggeredAtAfter(
            Long managedEntityId,
            SentimentAlert.Status status,
            Instant triggeredAfter
    );

    boolean existsByKindAndSourceMentionId(SentimentAlert.Kind kind, Long sourceMentionId);

    @Query("SELECT MAX(a.sourceMentionId) FROM SentimentAlert a WHERE a.kind = :kind")
    Long findMaxSourceMentionIdByKind(@Param("kind") SentimentAlert.Kind kind);

    @Query("SELECT a FROM SentimentAlert a WHERE " +
            "(:entityId IS NULL OR a.managedEntityId = :entityId) " +
            "AND (:status IS NULL OR a.status = :status)")
    Page<SentimentAlert> findFiltered(
            @Param("entityId") Long entityId,
            @Param("status") SentimentAlert.Status status,
            Pageable pageable
    );
}
