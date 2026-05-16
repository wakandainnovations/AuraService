package com.aura.service.repository;

import com.aura.service.dto.SentimentStats;
import com.aura.service.entity.Mention;
import com.aura.service.enums.Platform;
import com.aura.service.enums.Sentiment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface MentionRepository extends JpaRepository<Mention, Long> {

    List<Mention> findByManagedEntityId(Long entityId);

    long countByManagedEntityId(Long entityId);

    long countByManagedEntityIdIn(List<Long> entityIds);

    long countByManagedEntityIdAndSentiment(Long entityId, Sentiment sentiment);

    long countByManagedEntityIdInAndSentiment(List<Long> entityIds, Sentiment sentiment);

    @Query("SELECT m.platform, m.sentiment, COUNT(m) FROM Mention m WHERE m.managedEntity.id = :entityId GROUP BY m.platform, m.sentiment")
    List<Object[]> countByPlatformForEntity(@Param("entityId") Long entityId);

    @Query(value = "SELECT * FROM mentions m WHERE " +
           "(:entityIds IS NULL OR m.managed_entity_id IN (:entityIds)) " +
           "AND (CAST(:platform AS VARCHAR) IS NULL OR m.platform = CAST(:platform AS VARCHAR))",
           countQuery = "SELECT count(*) FROM mentions m WHERE " +
           "(:entityIds IS NULL OR m.managed_entity_id IN (:entityIds)) " +
           "AND (CAST(:platform AS VARCHAR) IS NULL OR m.platform = CAST(:platform AS VARCHAR))",
           nativeQuery = true)
    Page<Mention> findFilteredMentions(
        @Param("entityIds") List<Long> entityIds,
        @Param("platform") String platform,
        Pageable pageable
    );

    @Query("SELECT m FROM Mention m WHERE m.managedEntity.id IN :entityIds " +
           "AND m.postDate >= :startDate AND m.postDate <= :endDate")
    List<Mention> findByEntityIdsAndDateRange(
        @Param("entityIds") List<Long> entityIds,
        @Param("startDate") Instant startDate,
        @Param("endDate") Instant endDate
    );

    @Query("SELECT new com.aura.service.dto.SentimentStats(" +
            "AVG(m.sentimentScore), " +
            "CAST(SUM(CASE WHEN m.sentiment = com.aura.service.enums.Sentiment.POSITIVE THEN 1 ELSE 0 END) AS Double) / COUNT(m)) " +
            "FROM Mention m WHERE m.managedEntity.id = :entityId")
    Optional<SentimentStats> getSentimentStats(@Param("entityId") Long entityId);

    @Query("SELECT new com.aura.service.dto.SentimentStats(" +
            "AVG(m.sentimentScore), " +
            "CAST(SUM(CASE WHEN m.sentiment = com.aura.service.enums.Sentiment.POSITIVE THEN 1 ELSE 0 END) AS Double) / COUNT(m)) " +
            "FROM Mention m WHERE m.managedEntity.id IN :entityIds")
    Optional<SentimentStats> getSentimentStats(@Param("entityIds") List<Long> entityIds);

    @Query(value = "SELECT m.* FROM mentions m " +
           "WHERE m.post_id IN ( " +
           "  SELECT post_id FROM mentions " +
           "  WHERE managed_entity_id IN (:entityIds) " +
           "  GROUP BY post_id " +
           "  HAVING COUNT(DISTINCT managed_entity_id) = :count " +
           ")", nativeQuery = true)
    List<Mention> findIntersectionOfMentions(@Param("entityIds") List<Long> entityIds, @Param("count") int count);

    @Query(value = "SELECT * FROM mentions " +
            "WHERE managed_entity_id IN (:entityIds)",
            nativeQuery = true)
    List<Mention> findUnionOfMentions(@Param("entityIds") List<Long> entityIds);

    @Query(value = "SELECT EXTRACT(HOUR FROM m.post_date) AS hour, " +
            "COUNT(DISTINCT m.author) AS active_users " +
            "FROM mentions m " +
            "WHERE m.managed_entity_id = :entityId " +
            "  AND m.post_date >= :startDate " +
            "  AND m.post_date <= :endDate " +
            "  AND EXISTS ( " +
            "    SELECT 1 FROM entity_keywords ek " +
            "    WHERE ek.entity_id = m.managed_entity_id " +
            "      AND (CAST(:language AS TEXT) IS NULL OR ek.language = CAST(:language AS TEXT)) " +
            "      AND (CAST(:industry AS TEXT) IS NULL OR ek.industry = CAST(:industry AS TEXT)) " +
            "      AND (CAST(:state    AS TEXT) IS NULL OR ek.state    = CAST(:state    AS TEXT)) " +
            "      AND m.content ILIKE '%' || ek.keyword || '%' " +
            "  ) " +
            "GROUP BY EXTRACT(HOUR FROM m.post_date) " +
            "ORDER BY hour",
            nativeQuery = true)
    List<Object[]> countActiveUsersByHour(
            @Param("entityId") Long entityId,
            @Param("startDate") Instant startDate,
            @Param("endDate") Instant endDate,
            @Param("language") String language,
            @Param("industry") String industry,
            @Param("state") String state
    );

    @Query(value = "SELECT COUNT(DISTINCT m.author) " +
            "FROM mentions m " +
            "WHERE m.managed_entity_id = :entityId " +
            "  AND m.post_date >= :startDate " +
            "  AND m.post_date <= :endDate " +
            "  AND EXISTS ( " +
            "    SELECT 1 FROM entity_keywords ek " +
            "    WHERE ek.entity_id = m.managed_entity_id " +
            "      AND (CAST(:language AS TEXT) IS NULL OR ek.language = CAST(:language AS TEXT)) " +
            "      AND (CAST(:industry AS TEXT) IS NULL OR ek.industry = CAST(:industry AS TEXT)) " +
            "      AND (CAST(:state    AS TEXT) IS NULL OR ek.state    = CAST(:state    AS TEXT)) " +
            "      AND m.content ILIKE '%' || ek.keyword || '%' " +
            "  )",
            nativeQuery = true)
    long countDistinctActiveUsers(
            @Param("entityId") Long entityId,
            @Param("startDate") Instant startDate,
            @Param("endDate") Instant endDate,
            @Param("language") String language,
            @Param("industry") String industry,
            @Param("state") String state
    );

    @Query(value = "SELECT " +
            "  TO_CHAR(m.post_date AT TIME ZONE 'UTC', 'YYYY-MM-DD') AS day, " +
            "  EXTRACT(HOUR FROM m.post_date AT TIME ZONE 'UTC') AS hour, " +
            "  COUNT(DISTINCT m.author) AS active_users " +
            "FROM mentions m " +
            "WHERE m.managed_entity_id = :entityId " +
            "  AND m.post_date >= :startDate " +
            "  AND m.post_date <= :endDate " +
            "  AND EXISTS ( " +
            "    SELECT 1 FROM entity_keywords ek " +
            "    WHERE ek.entity_id = m.managed_entity_id " +
            "      AND (CAST(:language AS TEXT) IS NULL OR ek.language = CAST(:language AS TEXT)) " +
            "      AND (CAST(:industry AS TEXT) IS NULL OR ek.industry = CAST(:industry AS TEXT)) " +
            "      AND (CAST(:state    AS TEXT) IS NULL OR ek.state    = CAST(:state    AS TEXT)) " +
            "      AND m.content ILIKE '%' || ek.keyword || '%' " +
            "  ) " +
            "GROUP BY day, hour " +
            "ORDER BY day, hour",
            nativeQuery = true)
    List<Object[]> countActiveUsersByDayAndHour(
            @Param("entityId") Long entityId,
            @Param("startDate") Instant startDate,
            @Param("endDate") Instant endDate,
            @Param("language") String language,
            @Param("industry") String industry,
            @Param("state") String state
    );
}
