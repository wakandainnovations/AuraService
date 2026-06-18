package com.aura.service.repository;

import com.aura.service.dto.SentimentStats;
import com.aura.service.entity.Mention;
import com.aura.service.enums.Platform;
import com.aura.service.enums.Sentiment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * A {@link Mention} can be linked to several entities via the {@code mention_entities} join table
 * (see {@link Mention#getManagedEntities()}). Entity-scoped queries therefore test membership with
 * {@code EXISTS (SELECT e FROM m.managedEntities e WHERE e.id ...)} rather than a single
 * {@code managed_entity_id} column. EXISTS (not a JOIN) keeps each mention counted once even when it
 * is attributed to more than one of the requested entities.
 */
@Repository
public interface MentionRepository extends JpaRepository<Mention, Long> {

    @Query("SELECT m FROM Mention m WHERE EXISTS " +
            "(SELECT e FROM m.managedEntities e WHERE e.id = :entityId)")
    List<Mention> findByManagedEntityId(@Param("entityId") Long entityId);

    /** Removes an entity's rows from the join table without touching the mentions themselves. */
    @Modifying
    @Query(value = "DELETE FROM mention_entities WHERE managed_entity_id = :entityId", nativeQuery = true)
    int unlinkEntityFromMentions(@Param("entityId") Long entityId);

    /** Deletes mentions that are no longer attributed to any entity (orphaned after an unlink). */
    @Modifying
    @Query("DELETE FROM Mention m WHERE m.managedEntities IS EMPTY")
    int deleteMentionsWithNoEntities();

    @Query("SELECT COUNT(m) FROM Mention m WHERE EXISTS " +
            "(SELECT e FROM m.managedEntities e WHERE e.id = :entityId)")
    long countByManagedEntityId(@Param("entityId") Long entityId);

    @Query("SELECT COUNT(m) FROM Mention m WHERE EXISTS " +
            "(SELECT e FROM m.managedEntities e WHERE e.id IN :entityIds)")
    long countByManagedEntityIdIn(@Param("entityIds") List<Long> entityIds);

    @Query("SELECT COUNT(m) FROM Mention m WHERE m.sentiment = :sentiment AND EXISTS " +
            "(SELECT e FROM m.managedEntities e WHERE e.id = :entityId)")
    long countByManagedEntityIdAndSentiment(@Param("entityId") Long entityId,
                                            @Param("sentiment") Sentiment sentiment);

    @Query("SELECT COUNT(m) FROM Mention m WHERE m.postDate >= :start AND m.postDate <= :end AND EXISTS " +
            "(SELECT e FROM m.managedEntities e WHERE e.id = :entityId)")
    long countByManagedEntityIdAndPostDateBetween(@Param("entityId") Long entityId,
                                                  @Param("start") Instant start,
                                                  @Param("end") Instant end);

    @Query("SELECT COUNT(m) FROM Mention m WHERE m.sentiment = :sentiment " +
            "AND m.postDate >= :start AND m.postDate <= :end AND EXISTS " +
            "(SELECT e FROM m.managedEntities e WHERE e.id = :entityId)")
    long countByManagedEntityIdAndSentimentAndPostDateBetween(@Param("entityId") Long entityId,
                                                              @Param("sentiment") Sentiment sentiment,
                                                              @Param("start") Instant start,
                                                              @Param("end") Instant end);

    @Query("SELECT COUNT(m) FROM Mention m WHERE m.postDate > :after AND EXISTS " +
            "(SELECT e FROM m.managedEntities e WHERE e.id = :entityId)")
    long countByManagedEntityIdAndPostDateAfter(@Param("entityId") Long entityId,
                                                @Param("after") Instant after);

    @Query("SELECT COUNT(m) FROM Mention m WHERE m.sentiment = :sentiment " +
            "AND m.postDate > :after AND EXISTS " +
            "(SELECT e FROM m.managedEntities e WHERE e.id = :entityId)")
    long countByManagedEntityIdAndSentimentAndPostDateAfter(@Param("entityId") Long entityId,
                                                            @Param("sentiment") Sentiment sentiment,
                                                            @Param("after") Instant after);

    @Query("SELECT COUNT(m) FROM Mention m WHERE m.postDate <= :cutoff AND EXISTS " +
            "(SELECT e FROM m.managedEntities e WHERE e.id = :entityId)")
    long countByManagedEntityIdAndPostDateLessThanEqual(@Param("entityId") Long entityId,
                                                        @Param("cutoff") Instant cutoff);

    @Query("SELECT COUNT(m) FROM Mention m WHERE m.sentiment = :sentiment " +
            "AND m.postDate <= :cutoff AND EXISTS " +
            "(SELECT e FROM m.managedEntities e WHERE e.id = :entityId)")
    long countByManagedEntityIdAndSentimentAndPostDateLessThanEqual(@Param("entityId") Long entityId,
                                                                    @Param("sentiment") Sentiment sentiment,
                                                                    @Param("cutoff") Instant cutoff);

    @Query("SELECT DISTINCT m.author FROM Mention m " +
            "WHERE m.postDate > :after AND m.author IS NOT NULL AND EXISTS " +
            "(SELECT e FROM m.managedEntities e WHERE e.id = :entityId)")
    List<String> findDistinctAuthorsByEntityIdAndPostDateAfter(
            @Param("entityId") Long entityId, @Param("after") Instant after);

    @Query("SELECT DISTINCT m.author FROM Mention m " +
            "WHERE m.postDate <= :cutoff AND m.author IS NOT NULL AND EXISTS " +
            "(SELECT e FROM m.managedEntities e WHERE e.id = :entityId)")
    List<String> findDistinctAuthorsByEntityIdAndPostDateLessThanEqual(
            @Param("entityId") Long entityId, @Param("cutoff") Instant cutoff);

    @Query("SELECT COUNT(m) FROM Mention m WHERE m.sentiment = :sentiment AND EXISTS " +
            "(SELECT e FROM m.managedEntities e WHERE e.id IN :entityIds)")
    long countByManagedEntityIdInAndSentiment(@Param("entityIds") List<Long> entityIds,
                                              @Param("sentiment") Sentiment sentiment);

    List<Mention> findByIdGreaterThanAndSentimentOrderByIdAsc(Long id, Sentiment sentiment);

    @Query("SELECT m FROM Mention m WHERE m.sentiment = :sentiment AND EXISTS " +
            "(SELECT e FROM m.managedEntities e WHERE e.id = :entityId) " +
            "ORDER BY m.postDate DESC")
    List<Mention> findTop3ByManagedEntityIdAndSentiment(
            @Param("entityId") Long entityId, @Param("sentiment") Sentiment sentiment, Pageable pageable);

    @Query("SELECT m FROM Mention m WHERE m.sentiment = :sentiment AND m.postDate > :after AND EXISTS " +
            "(SELECT e FROM m.managedEntities e WHERE e.id = :entityId) " +
            "ORDER BY m.postDate DESC")
    List<Mention> findTop3ByManagedEntityIdAndSentimentAndPostDateAfter(
            @Param("entityId") Long entityId, @Param("sentiment") Sentiment sentiment,
            @Param("after") Instant after, Pageable pageable);

    @Query("SELECT m FROM Mention m WHERE m.author = :author AND m.sentiment = :sentiment " +
            "AND m.postDate > :after AND EXISTS " +
            "(SELECT e FROM m.managedEntities e WHERE e.id = :entityId) " +
            "ORDER BY m.postDate DESC")
    List<Mention> findTop3ByManagedEntityIdAndAuthorAndSentimentAndPostDateAfter(
            @Param("entityId") Long entityId, @Param("author") String author,
            @Param("sentiment") Sentiment sentiment, @Param("after") Instant after, Pageable pageable);

    @Query("SELECT COALESCE(MAX(m.id), 0) FROM Mention m")
    long findMaxId();

    @Query("SELECT m.author, m.sentiment, COUNT(m) FROM Mention m " +
            "WHERE m.author IN :authors AND EXISTS " +
            "(SELECT e FROM m.managedEntities e WHERE e.id = :entityId) " +
            "GROUP BY m.author, m.sentiment")
    List<Object[]> countSentimentByAuthorsForEntity(
            @Param("entityId") Long entityId,
            @Param("authors") Collection<String> authors
    );

    @Query("SELECT m.platform, m.sentiment, COUNT(m) FROM Mention m WHERE EXISTS " +
            "(SELECT e FROM m.managedEntities e WHERE e.id = :entityId) " +
            "GROUP BY m.platform, m.sentiment")
    List<Object[]> countByPlatformForEntity(@Param("entityId") Long entityId);

    // x_posts is populated by the ingestion pipeline and has no JPA entity in this service;
    // views_count is X's impression metric. The other platform tables carry no impression data.
    @Query(value = "SELECT x.id, x.views_count FROM x_posts x WHERE x.id IN (:postIds)",
           nativeQuery = true)
    List<Object[]> findXPostViewsCounts(@Param("postIds") Collection<String> postIds);

    @Query(value = "SELECT DISTINCT m.* FROM mentions m " +
           "LEFT JOIN mention_entities me ON me.mention_id = m.id WHERE " +
           "(:entityIds IS NULL OR me.managed_entity_id IN (:entityIds)) " +
           "AND (CAST(:platform AS VARCHAR) IS NULL OR m.platform = CAST(:platform AS VARCHAR))",
           countQuery = "SELECT count(DISTINCT m.id) FROM mentions m " +
           "LEFT JOIN mention_entities me ON me.mention_id = m.id WHERE " +
           "(:entityIds IS NULL OR me.managed_entity_id IN (:entityIds)) " +
           "AND (CAST(:platform AS VARCHAR) IS NULL OR m.platform = CAST(:platform AS VARCHAR))",
           nativeQuery = true)
    Page<Mention> findFilteredMentions(
        @Param("entityIds") List<Long> entityIds,
        @Param("platform") String platform,
        Pageable pageable
    );

    @Query("SELECT m FROM Mention m WHERE m.postDate >= :startDate AND m.postDate <= :endDate " +
           "AND EXISTS (SELECT e FROM m.managedEntities e WHERE e.id IN :entityIds)")
    List<Mention> findByEntityIdsAndDateRange(
        @Param("entityIds") List<Long> entityIds,
        @Param("startDate") Instant startDate,
        @Param("endDate") Instant endDate
    );

    @Query("SELECT new com.aura.service.dto.SentimentStats(" +
            "AVG(m.sentimentScore), " +
            "CAST(SUM(CASE WHEN m.sentiment = com.aura.service.enums.Sentiment.POSITIVE THEN 1 ELSE 0 END) AS Double) / COUNT(m)) " +
            "FROM Mention m WHERE EXISTS (SELECT e FROM m.managedEntities e WHERE e.id = :entityId)")
    Optional<SentimentStats> getSentimentStats(@Param("entityId") Long entityId);

    @Query("SELECT new com.aura.service.dto.SentimentStats(" +
            "AVG(m.sentimentScore), " +
            "CAST(SUM(CASE WHEN m.sentiment = com.aura.service.enums.Sentiment.POSITIVE THEN 1 ELSE 0 END) AS Double) / COUNT(m)) " +
            "FROM Mention m WHERE EXISTS (SELECT e FROM m.managedEntities e WHERE e.id IN :entityIds)")
    Optional<SentimentStats> getSentimentStats(@Param("entityIds") List<Long> entityIds);

    // Posts attributed to ALL of the given entities: the join table must carry a distinct row linking
    // the same mention to each requested entity id.
    @Query(value = "SELECT m.* FROM mentions m WHERE ( " +
           "  SELECT COUNT(DISTINCT me.managed_entity_id) FROM mention_entities me " +
           "  WHERE me.mention_id = m.id AND me.managed_entity_id IN (:entityIds) " +
           ") = :count", nativeQuery = true)
    List<Mention> findIntersectionOfMentions(@Param("entityIds") List<Long> entityIds, @Param("count") int count);

    @Query(value = "SELECT DISTINCT m.* FROM mentions m " +
            "JOIN mention_entities me ON me.mention_id = m.id " +
            "WHERE me.managed_entity_id IN (:entityIds)",
            nativeQuery = true)
    List<Mention> findUnionOfMentions(@Param("entityIds") List<Long> entityIds);

    @Query(value = "SELECT EXTRACT(HOUR FROM m.post_date) AS hour, " +
            "COUNT(DISTINCT m.author) AS active_users " +
            "FROM mentions m " +
            "JOIN mention_entities me ON me.mention_id = m.id " +
            "WHERE me.managed_entity_id = :entityId " +
            "  AND m.post_date >= :startDate " +
            "  AND m.post_date <= :endDate " +
            "  AND EXISTS ( " +
            "    SELECT 1 FROM entity_keywords ek " +
            "    WHERE ek.entity_id = me.managed_entity_id " +
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
            "JOIN mention_entities me ON me.mention_id = m.id " +
            "WHERE me.managed_entity_id = :entityId " +
            "  AND m.post_date >= :startDate " +
            "  AND m.post_date <= :endDate " +
            "  AND EXISTS ( " +
            "    SELECT 1 FROM entity_keywords ek " +
            "    WHERE ek.entity_id = me.managed_entity_id " +
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
            "JOIN mention_entities me ON me.mention_id = m.id " +
            "WHERE me.managed_entity_id = :entityId " +
            "  AND m.post_date >= :startDate " +
            "  AND m.post_date <= :endDate " +
            "  AND EXISTS ( " +
            "    SELECT 1 FROM entity_keywords ek " +
            "    WHERE ek.entity_id = me.managed_entity_id " +
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
