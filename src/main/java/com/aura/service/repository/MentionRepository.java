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

    /**
     * Attributes already-collected mentions to {@code entityId} by the SAME rule the ingestion uses to
     * decide a post is about an entity: the post's <em>collection keyword</em> — the search term the
     * crawler captured the post under, stored on each platform's source row
     * ({@code youtube_comments.keyword}, {@code x_posts.keyword}, {@code instagram_posts.keyword},
     * {@code reddit_posts.keyword}) and reached through {@code mention.post_id} — matches one of the
     * entity's keywords (case-insensitively). This is deliberately NOT a {@code content ILIKE '%keyword%'}
     * match: most posts never contain the keyword in their text (e.g. comments on a celebrity's video
     * collected under the handle "VinayRai" read "He is so awesome"), so a content match links almost
     * nothing for handle/hashtag keywords and leaves the new entity's dashboards and competitor snapshot
     * at zero. Matching the collection keyword reproduces the ingestion's own attribution, which is what
     * lets an entity created — or re-keyworded — after its keywords' posts were already collected still
     * see their history (the {@code mention_entities} join table that every entity-scoped query reads is
     * otherwise only populated for posts collected while the entity already existed). Idempotent: the
     * {@code NOT EXISTS} guard skips links that are already present, and only the join table is touched.
     * {@code flushAutomatically} forces the entity's just-saved {@code entity_keywords} rows to the DB
     * before this native statement reads them.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "INSERT INTO mention_entities (mention_id, managed_entity_id) " +
           "SELECT DISTINCT m.id, :entityId FROM mentions m " +
           "JOIN (SELECT id, keyword FROM youtube_comments " +
           "      UNION ALL SELECT id, keyword FROM x_posts " +
           "      UNION ALL SELECT id, keyword FROM instagram_posts " +
           "      UNION ALL SELECT id, keyword FROM reddit_posts) src ON src.id = m.post_id " +
           "JOIN entity_keywords ek ON ek.entity_id = :entityId " +
           "WHERE LOWER(src.keyword) = LOWER(ek.keyword) " +
           "AND NOT EXISTS (SELECT 1 FROM mention_entities me " +
           "  WHERE me.mention_id = m.id AND me.managed_entity_id = :entityId)",
           nativeQuery = true)
    int linkExistingMentionsByKeyword(@Param("entityId") Long entityId);

    /**
     * Drops {@code entityId}'s links to mentions whose collection keyword no longer matches any of its
     * current keywords — the inverse of {@link #linkExistingMentionsByKeyword}, using the same
     * collection-keyword rule so after a keyword set is replaced the join table reflects only the current
     * keywords. Removes only this entity's join rows; a post shared with other entities keeps its other
     * links (orphans are purged separately, only on entity delete).
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "DELETE FROM mention_entities me WHERE me.managed_entity_id = :entityId " +
           "AND NOT EXISTS (SELECT 1 FROM mentions m " +
           "  JOIN (SELECT id, keyword FROM youtube_comments " +
           "        UNION ALL SELECT id, keyword FROM x_posts " +
           "        UNION ALL SELECT id, keyword FROM instagram_posts " +
           "        UNION ALL SELECT id, keyword FROM reddit_posts) src ON src.id = m.post_id " +
           "  JOIN entity_keywords ek ON ek.entity_id = :entityId " +
           "  WHERE m.id = me.mention_id AND LOWER(src.keyword) = LOWER(ek.keyword))",
           nativeQuery = true)
    int unlinkStaleMentionsByKeyword(@Param("entityId") Long entityId);

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
