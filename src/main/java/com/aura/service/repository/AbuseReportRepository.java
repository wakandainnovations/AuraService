package com.aura.service.repository;

import com.aura.service.entity.AbuseReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface AbuseReportRepository extends JpaRepository<AbuseReport, Long> {

    /** Reports filed against a single mention, newest first. */
    List<AbuseReport> findByMentionIdOrderBySubmittedAtDesc(Long mentionId);

    /** Remove every report filed against a mention (used when the mention itself is deleted). */
    void deleteByMentionId(Long mentionId);

    /** Every report a user has filed, newest first. */
    List<AbuseReport> findByUserIdOrderBySubmittedAtDesc(Long userId);

    /** A user's reports in a given status, newest first. */
    List<AbuseReport> findByUserIdAndStatusOrderBySubmittedAtDesc(Long userId, AbuseReport.Status status);

    /** Total reports a user has filed. */
    long countByUserId(Long userId);

    /** A user's reports in a given status. */
    long countByUserIdAndStatus(Long userId, AbuseReport.Status status);

    /** Reports still in a given status that were submitted before {@code cutoff} (the review window). */
    List<AbuseReport> findByStatusAndSubmittedAtBefore(AbuseReport.Status status, Instant cutoff);

    /**
     * A user's reports that reached a terminal {@code status} after {@code since}, scoped to mentions
     * belonging to {@code entityId}. Drives the "reward of the self" What's-New cards.
     */
    @Query("SELECT r FROM AbuseReport r WHERE r.userId = :userId AND r.status = :status " +
            "AND r.resolvedAt > :since AND r.mentionId IN " +
            "(SELECT m.id FROM Mention m WHERE EXISTS " +
            "(SELECT e FROM m.managedEntities e WHERE e.id = :entityId))")
    List<AbuseReport> findResolvedForUserAndEntitySince(
            @Param("userId") Long userId,
            @Param("entityId") Long entityId,
            @Param("status") AbuseReport.Status status,
            @Param("since") Instant since);
}
