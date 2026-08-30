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

    /**
     * Owner-aware variant of the recent-open-alert dedup check. A null
     * {@code ownerUserId} matches only alerts with no owner (default-threshold
     * alerts); a non-null value matches that user's alerts.
     */
    @Query("SELECT CASE WHEN COUNT(a) > 0 THEN true ELSE false END FROM SentimentAlert a " +
            "WHERE a.managedEntityId = :entityId AND a.status = :status AND a.triggeredAt > :after " +
            "AND ((:ownerUserId IS NULL AND a.ownerUserId IS NULL) OR a.ownerUserId = :ownerUserId)")
    boolean existsRecentOpenForOwner(@Param("entityId") Long entityId,
                                     @Param("status") SentimentAlert.Status status,
                                     @Param("after") Instant after,
                                     @Param("ownerUserId") Long ownerUserId);

    /**
     * Dedup check for influencer-negative alerts, scoped per owning user (a
     * null {@code ownerUserId} matches default-threshold alerts with no owner).
     */
    @Query("SELECT CASE WHEN COUNT(a) > 0 THEN true ELSE false END FROM SentimentAlert a " +
            "WHERE a.kind = :kind AND a.sourceMentionId = :sourceMentionId " +
            "AND ((:ownerUserId IS NULL AND a.ownerUserId IS NULL) OR a.ownerUserId = :ownerUserId)")
    boolean existsByKindAndSourceMentionIdForOwner(@Param("kind") SentimentAlert.Kind kind,
                                                   @Param("sourceMentionId") Long sourceMentionId,
                                                   @Param("ownerUserId") Long ownerUserId);

    boolean existsByManagedEntityIdAndKindAndStatus(
            Long managedEntityId,
            SentimentAlert.Kind kind,
            SentimentAlert.Status status
    );

    @Query("SELECT MAX(a.sourceMentionId) FROM SentimentAlert a WHERE a.kind = :kind")
    Long findMaxSourceMentionIdByKind(@Param("kind") SentimentAlert.Kind kind);

    /**
     * {@code ownerId} scopes the feed to alerts on movies that owner owns (null, admin-only, means
     * "no owner filter" — every movie's alerts). Joined via a subquery since {@code managedEntityId}
     * is a bare id, not a mapped relation.
     */
    @Query("SELECT a FROM SentimentAlert a WHERE " +
            "(:entityId IS NULL OR a.managedEntityId = :entityId) " +
            "AND (:status IS NULL OR a.status = :status) " +
            "AND (:ownerId IS NULL OR a.managedEntityId IN " +
            "     (SELECT e.id FROM ManagedEntity e WHERE e.owner.id = :ownerId))")
    Page<SentimentAlert> findFiltered(
            @Param("entityId") Long entityId,
            @Param("status") SentimentAlert.Status status,
            @Param("ownerId") Long ownerId,
            Pageable pageable
    );
}
