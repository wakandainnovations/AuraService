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

    /** Removes all join-table rows for a mention before the mention itself is deleted. */
    @Modifying
    @Query(value = "DELETE FROM mention_entities WHERE mention_id = :mentionId", nativeQuery = true)
    void unlinkMentionFromEntities(@Param("mentionId") Long mentionId);

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

    // ---- Per-platform engagement counts for the graph USER-node weight (see GraphSyncServiceImpl) ----
    // Each ingestion table names its like/comment columns differently; every query below is
    // normalized to (id, likes, comments). None of the four tables track a shares/retweet count —
    // GraphSyncServiceImpl reuses its RETWEETED "RT" detection as the shares proxy instead.

    @Query(value = "SELECT x.id, x.likes_count, x.comment_count FROM x_posts x WHERE x.id IN (:postIds)",
           nativeQuery = true)
    List<Object[]> findXPostEngagement(@Param("postIds") Collection<String> postIds);

    @Query(value = "SELECT y.id, y.likes_count, y.reply_count FROM youtube_comments y WHERE y.id IN (:postIds)",
           nativeQuery = true)
    List<Object[]> findYoutubeCommentEngagement(@Param("postIds") Collection<String> postIds);

    @Query(value = "SELECT r.id, r.score, r.num_comments FROM reddit_posts r WHERE r.id IN (:postIds)",
           nativeQuery = true)
    List<Object[]> findRedditPostEngagement(@Param("postIds") Collection<String> postIds);

    @Query(value = "SELECT i.id, i.like_count, i.comments_count FROM instagram_posts i WHERE i.id IN (:postIds)",
           nativeQuery = true)
    List<Object[]> findInstagramPostEngagement(@Param("postIds") Collection<String> postIds);

    // Every mention this author has posted that's linked to at least one MOVIE entity — the same
    // scope GraphSyncServiceImpl uses to build edges, so the USER node's weight only reflects
    // movie-related activity.
    @Query("SELECT m FROM Mention m WHERE m.author = :author AND EXISTS " +
            "(SELECT e FROM m.managedEntities e WHERE e.type = 'MOVIE')")
    List<Mention> findMovieLinkedMentionsByAuthor(@Param("author") String author);

    /**
     * {@code topicCategory}/{@code contentIntent}/{@code authorType}/{@code region} filter on the
     * same upstream per-platform columns as {@link #findTopicCategoryBreakdownForEntity}/
     * {@link #findContentIntentBreakdownForEntity}/{@link #findAuthorTypeBreakdownForEntity}/
     * {@link #findRegionBuzzForEntity} — the left joins to the four raw ingestion tables resolve
     * each mention's own row (keyed on {@code post_id}+{@code platform}, so at most one join
     * matches per mention, no row duplication) purely so its classification column can be read;
     * {@code COALESCE} picks whichever platform-specific join actually matched. This is the
     * drill-down counterpart to those aggregate breakdowns (Audience Pulse included) and to
     * {@code review_aspect_category} filtering — lets a caller go from "340 posts tagged
     * Screenplay" or "62 posts from Karnataka" to the actual posts, to spot-check the classifier
     * rather than trust the aggregate blindly. Matched case-insensitively since the upstream
     * taxonomy isn't a Java enum (unlike {@code review_aspect_category}, it has no fixed/documented
     * value set this codebase owns).
     *
     * <p>All four additionally resolve each mention's latest override row (from
     * {@code topic_category_overrides}/{@code content_intent_overrides}/{@code author_type_overrides}/
     * {@code region_overrides} respectively) ahead of the raw upstream column — the same overlay
     * {@link #findTopicCategoryBreakdownForEntity}/{@link #findContentIntentBreakdownForEntity}/
     * {@link #findAuthorTypeBreakdownForEntity}/{@link #findRegionBuzzForEntity} apply — so a
     * corrected post is findable under its corrected value.
     */
    @Query(value = "SELECT DISTINCT m.* FROM mentions m " +
           "LEFT JOIN mention_entities me ON me.mention_id = m.id " +
           "LEFT JOIN x_posts x ON x.id = m.post_id AND m.platform = 'X' " +
           "LEFT JOIN youtube_comments y ON y.id = m.post_id AND m.platform = 'YOUTUBE' " +
           "LEFT JOIN reddit_posts r ON r.id = m.post_id AND m.platform = 'REDDIT' " +
           "LEFT JOIN instagram_posts i ON i.id = m.post_id AND m.platform = 'INSTAGRAM' " +
           "WHERE (:entityIds IS NULL OR me.managed_entity_id IN (:entityIds)) " +
           "AND (CAST(:platform AS VARCHAR) IS NULL OR m.platform = CAST(:platform AS VARCHAR)) " +
           "AND (CAST(:reviewAspectCategory AS VARCHAR) IS NULL OR m.review_aspect_category = CAST(:reviewAspectCategory AS VARCHAR)) " +
           "AND (CAST(:topicCategory AS VARCHAR) IS NULL OR LOWER(COALESCE(" +
           "(SELECT tco.new_category FROM topic_category_overrides tco WHERE tco.mention_id = m.id ORDER BY tco.created_at DESC LIMIT 1), " +
           "x.topic_category, y.topic_category, r.topic_category, i.topic_category)) = LOWER(CAST(:topicCategory AS VARCHAR))) " +
           "AND (CAST(:contentIntent AS VARCHAR) IS NULL OR LOWER(COALESCE(" +
           "(SELECT cio.new_category FROM content_intent_overrides cio WHERE cio.mention_id = m.id ORDER BY cio.created_at DESC LIMIT 1), " +
           "x.content_intent, y.content_intent, r.content_intent, i.content_intent)) = LOWER(CAST(:contentIntent AS VARCHAR))) " +
           "AND (CAST(:authorType AS VARCHAR) IS NULL OR LOWER(COALESCE(" +
           "(SELECT ato.new_category FROM author_type_overrides ato WHERE ato.mention_id = m.id ORDER BY ato.created_at DESC LIMIT 1), " +
           "x.author_type, y.author_type, r.author_type, i.author_type)) = LOWER(CAST(:authorType AS VARCHAR))) " +
           "AND (CAST(:region AS VARCHAR) IS NULL OR LOWER(COALESCE(" +
           "(SELECT ro.new_category FROM region_overrides ro WHERE ro.mention_id = m.id ORDER BY ro.created_at DESC LIMIT 1), " +
           "x.predicted_region, y.predicted_region, r.predicted_region, i.predicted_region)) = LOWER(CAST(:region AS VARCHAR))) " +
           // Explicit top-level ORDER BY, since this query's WHERE clause now contains its own
           // per-override "ORDER BY ... LIMIT 1" subqueries (see the overlay COALESCEs above).
           // Hibernate's native-query Pageable/Sort support finds the query's *last* ORDER BY
           // token to append the paging sort to - with a Sort-bearing Pageable it mistook one of
           // those subquery ORDER BYs for the outer query's, appending ", m.post_date desc" after
           // an already-closed subquery instead of adding a real top-level ORDER BY, which
           // Postgres then rejected as a syntax error. Owning the sort here (and passing an
           // unsorted Pageable from DashboardService.getMentions) sidesteps that entirely.
           "ORDER BY m.post_date DESC",
           countQuery = "SELECT count(DISTINCT m.id) FROM mentions m " +
           "LEFT JOIN mention_entities me ON me.mention_id = m.id " +
           "LEFT JOIN x_posts x ON x.id = m.post_id AND m.platform = 'X' " +
           "LEFT JOIN youtube_comments y ON y.id = m.post_id AND m.platform = 'YOUTUBE' " +
           "LEFT JOIN reddit_posts r ON r.id = m.post_id AND m.platform = 'REDDIT' " +
           "LEFT JOIN instagram_posts i ON i.id = m.post_id AND m.platform = 'INSTAGRAM' " +
           "WHERE (:entityIds IS NULL OR me.managed_entity_id IN (:entityIds)) " +
           "AND (CAST(:platform AS VARCHAR) IS NULL OR m.platform = CAST(:platform AS VARCHAR)) " +
           "AND (CAST(:reviewAspectCategory AS VARCHAR) IS NULL OR m.review_aspect_category = CAST(:reviewAspectCategory AS VARCHAR)) " +
           "AND (CAST(:topicCategory AS VARCHAR) IS NULL OR LOWER(COALESCE(" +
           "(SELECT tco.new_category FROM topic_category_overrides tco WHERE tco.mention_id = m.id ORDER BY tco.created_at DESC LIMIT 1), " +
           "x.topic_category, y.topic_category, r.topic_category, i.topic_category)) = LOWER(CAST(:topicCategory AS VARCHAR))) " +
           "AND (CAST(:contentIntent AS VARCHAR) IS NULL OR LOWER(COALESCE(" +
           "(SELECT cio.new_category FROM content_intent_overrides cio WHERE cio.mention_id = m.id ORDER BY cio.created_at DESC LIMIT 1), " +
           "x.content_intent, y.content_intent, r.content_intent, i.content_intent)) = LOWER(CAST(:contentIntent AS VARCHAR))) " +
           "AND (CAST(:authorType AS VARCHAR) IS NULL OR LOWER(COALESCE(" +
           "(SELECT ato.new_category FROM author_type_overrides ato WHERE ato.mention_id = m.id ORDER BY ato.created_at DESC LIMIT 1), " +
           "x.author_type, y.author_type, r.author_type, i.author_type)) = LOWER(CAST(:authorType AS VARCHAR))) " +
           "AND (CAST(:region AS VARCHAR) IS NULL OR LOWER(COALESCE(" +
           "(SELECT ro.new_category FROM region_overrides ro WHERE ro.mention_id = m.id ORDER BY ro.created_at DESC LIMIT 1), " +
           "x.predicted_region, y.predicted_region, r.predicted_region, i.predicted_region)) = LOWER(CAST(:region AS VARCHAR)))",
           nativeQuery = true)
    Page<Mention> findFilteredMentions(
        @Param("entityIds") List<Long> entityIds,
        @Param("platform") String platform,
        @Param("reviewAspectCategory") String reviewAspectCategory,
        @Param("topicCategory") String topicCategory,
        @Param("contentIntent") String contentIntent,
        @Param("authorType") String authorType,
        @Param("region") String region,
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

    // ---- Movie audience/budget-comparison queries (see MovieAudienceServiceImpl) ----
    // All three restrict to mentions with a non-zero sentiment score, per the "only count posts
    // with a non-zero sentiment score" requirement - NULL and 0 are both excluded.

    /**
     * Total unique posters across ALL of the given entities combined (a user posting about two of
     * the entities is still counted once) - used for the language-wide audience count, where
     * {@code entityIds} is every movie in that language.
     */
    @Query("SELECT COUNT(DISTINCT m.author) FROM Mention m " +
            "WHERE m.author IS NOT NULL AND m.sentimentScore IS NOT NULL AND m.sentimentScore <> 0 " +
            "AND EXISTS (SELECT e FROM m.managedEntities e WHERE e.id IN :entityIds)")
    long countDistinctAuthorsByEntityIdsNonZeroSentiment(@Param("entityIds") List<Long> entityIds);

    /**
     * Per-user engagement for one movie (or several entity rows representing the same movie, e.g.
     * duplicate tracked entities with the same name/language): post count, average sentiment score,
     * and how many of that user's posts were POSITIVE. {@code EXISTS} (not a join) so a mention
     * attributed to more than one of {@code entityIds} is still only counted once per author.
     */
    @Query("SELECT m.author, COUNT(m), AVG(m.sentimentScore), " +
            "SUM(CASE WHEN m.sentiment = com.aura.service.enums.Sentiment.POSITIVE THEN 1L ELSE 0L END) " +
            "FROM Mention m " +
            "WHERE m.author IS NOT NULL AND m.sentimentScore IS NOT NULL AND m.sentimentScore <> 0 " +
            "AND EXISTS (SELECT e FROM m.managedEntities e WHERE e.id IN :entityIds) " +
            "GROUP BY m.author")
    List<Object[]> findAuthorEngagementStats(@Param("entityIds") List<Long> entityIds);

    /**
     * Unique poster count and total qualifying post count per entity, for a set of distinct movies
     * (e.g. the target movie plus every budget-comparable movie). Uses a JOIN rather than EXISTS,
     * deliberately: a post shared across two of the requested entities must count toward BOTH of
     * their totals here, since each row in the result is a separate movie's own metadata.
     */
    @Query("SELECT e.id, COUNT(DISTINCT m.author), COUNT(m) FROM Mention m JOIN m.managedEntities e " +
            "WHERE e.id IN :entityIds " +
            "AND m.author IS NOT NULL AND m.sentimentScore IS NOT NULL AND m.sentimentScore <> 0 " +
            "GROUP BY e.id")
    List<Object[]> countAudienceAndPostsPerEntity(@Param("entityIds") List<Long> entityIds);

    /**
     * One row per (entity, mention) attribution for {@code entityIds} within the date range - used by
     * {@code AudiencePatternServiceImpl}'s industry/language cohort comparison. A JOIN (not EXISTS), like
     * {@link #countAudienceAndPostsPerEntity}: a mention shared across two requested entities in the same
     * cohort must count toward that cohort's totals twice, once per movie it's attributed to. Returns just
     * enough of each row to bucket by cohort and resolve engagement: author, platform, postId, sentiment.
     */
    @Query("SELECT e.id, m.author, m.platform, m.postId, m.sentimentScore, m.sentiment " +
            "FROM Mention m JOIN m.managedEntities e " +
            "WHERE e.id IN :entityIds AND m.postDate >= :startDate AND m.postDate <= :endDate")
    List<Object[]> findMentionEngagementInputsForEntities(
            @Param("entityIds") List<Long> entityIds,
            @Param("startDate") Instant startDate,
            @Param("endDate") Instant endDate);

    /**
     * Buzz (post count) per {@code predicted_region}, for the Audience Pulse panel. Like
     * {@link #linkExistingMentionsByKeyword}, {@code predicted_region} only lives on the raw
     * per-platform tables, so each is joined back to {@code mentions}/{@code mention_entities} via
     * {@code post_id} + {@code platform} rather than through a JPA relation. Rows the ingestion
     * pipeline predicted as {@code 'irrelevant'} (case-insensitive) or left {@code NULL} are excluded
     * before grouping. Ordered by buzz descending so the caller can rank without re-sorting.
     *
     * <p>Each branch's value is the latest {@code region_overrides} row for that mention (by
     * {@code created_at}) when one exists, else the raw upstream column — same overlay convention as
     * {@link #findAuthorTypeBreakdownForEntity}; see {@link com.aura.service.entity.RegionOverride}.
     */
    @Query(value = "SELECT region, COUNT(*) AS buzz FROM ( " +
            "  SELECT COALESCE((SELECT ro.new_category FROM region_overrides ro " +
            "    WHERE ro.mention_id = m.id ORDER BY ro.created_at DESC LIMIT 1), x.predicted_region) AS region " +
            "    FROM x_posts x " +
            "    JOIN mentions m ON m.post_id = x.id AND m.platform = 'X' " +
            "    JOIN mention_entities me ON me.mention_id = m.id " +
            "    WHERE me.managed_entity_id = :entityId " +
            "  UNION ALL " +
            "  SELECT COALESCE((SELECT ro.new_category FROM region_overrides ro " +
            "    WHERE ro.mention_id = m.id ORDER BY ro.created_at DESC LIMIT 1), y.predicted_region) AS region " +
            "    FROM youtube_comments y " +
            "    JOIN mentions m ON m.post_id = y.id AND m.platform = 'YOUTUBE' " +
            "    JOIN mention_entities me ON me.mention_id = m.id " +
            "    WHERE me.managed_entity_id = :entityId " +
            "  UNION ALL " +
            "  SELECT COALESCE((SELECT ro.new_category FROM region_overrides ro " +
            "    WHERE ro.mention_id = m.id ORDER BY ro.created_at DESC LIMIT 1), r.predicted_region) AS region " +
            "    FROM reddit_posts r " +
            "    JOIN mentions m ON m.post_id = r.id AND m.platform = 'REDDIT' " +
            "    JOIN mention_entities me ON me.mention_id = m.id " +
            "    WHERE me.managed_entity_id = :entityId " +
            "  UNION ALL " +
            "  SELECT COALESCE((SELECT ro.new_category FROM region_overrides ro " +
            "    WHERE ro.mention_id = m.id ORDER BY ro.created_at DESC LIMIT 1), i.predicted_region) AS region " +
            "    FROM instagram_posts i " +
            "    JOIN mentions m ON m.post_id = i.id AND m.platform = 'INSTAGRAM' " +
            "    JOIN mention_entities me ON me.mention_id = m.id " +
            "    WHERE me.managed_entity_id = :entityId " +
            ") regions " +
            "WHERE region IS NOT NULL AND LOWER(region) <> 'irrelevant' " +
            "GROUP BY region " +
            "ORDER BY buzz DESC",
            nativeQuery = true)
    List<Object[]> findRegionBuzzForEntity(@Param("entityId") Long entityId);

    /**
     * Current effective {@code predicted_region} for one mention — the latest override if one
     * exists, else the raw upstream value. Same purpose as {@link #findCurrentAuthorType}.
     */
    @Query(value = "SELECT COALESCE(" +
            "(SELECT ro.new_category FROM region_overrides ro WHERE ro.mention_id = :mentionId " +
            "  ORDER BY ro.created_at DESC LIMIT 1), " +
            "x.predicted_region, y.predicted_region, r.predicted_region, i.predicted_region) " +
            "FROM mentions m " +
            "LEFT JOIN x_posts x ON x.id = m.post_id AND m.platform = 'X' " +
            "LEFT JOIN youtube_comments y ON y.id = m.post_id AND m.platform = 'YOUTUBE' " +
            "LEFT JOIN reddit_posts r ON r.id = m.post_id AND m.platform = 'REDDIT' " +
            "LEFT JOIN instagram_posts i ON i.id = m.post_id AND m.platform = 'INSTAGRAM' " +
            "WHERE m.id = :mentionId",
            nativeQuery = true)
    String findCurrentRegion(@Param("mentionId") Long mentionId);

    /**
     * Promotional vs. organic post count, for the Promotional Mix panel. {@code is_promotional} is a
     * not-null boolean on every raw platform table (default {@code false}), unlike the other
     * classification columns, so there is no NULL/'irrelevant' row to exclude here. Same
     * join-back-via-{@code post_id}+{@code platform} pattern as {@link #findRegionBuzzForEntity}.
     */
    @Query(value = "SELECT is_promotional, COUNT(*) AS cnt FROM ( " +
            "  SELECT x.is_promotional FROM x_posts x " +
            "    JOIN mentions m ON m.post_id = x.id AND m.platform = 'X' " +
            "    JOIN mention_entities me ON me.mention_id = m.id " +
            "    WHERE me.managed_entity_id = :entityId " +
            "  UNION ALL " +
            "  SELECT y.is_promotional FROM youtube_comments y " +
            "    JOIN mentions m ON m.post_id = y.id AND m.platform = 'YOUTUBE' " +
            "    JOIN mention_entities me ON me.mention_id = m.id " +
            "    WHERE me.managed_entity_id = :entityId " +
            "  UNION ALL " +
            "  SELECT r.is_promotional FROM reddit_posts r " +
            "    JOIN mentions m ON m.post_id = r.id AND m.platform = 'REDDIT' " +
            "    JOIN mention_entities me ON me.mention_id = m.id " +
            "    WHERE me.managed_entity_id = :entityId " +
            "  UNION ALL " +
            "  SELECT i.is_promotional FROM instagram_posts i " +
            "    JOIN mentions m ON m.post_id = i.id AND m.platform = 'INSTAGRAM' " +
            "    JOIN mention_entities me ON me.mention_id = m.id " +
            "    WHERE me.managed_entity_id = :entityId " +
            ") flags " +
            "GROUP BY is_promotional",
            nativeQuery = true)
    List<Object[]> findPromotionalMixForEntity(@Param("entityId") Long entityId);

    /**
     * Post count per {@code author_type} ({@code general_public}, {@code fan_page},
     * {@code media_press}, {@code official_studio}, {@code verified_celebrity_influencer},
     * {@code bot_spam}, ...), for the "who's talking" panel. Rows the pipeline classified as
     * {@code 'irrelevant'} (case-insensitive) or left {@code NULL} (not yet enriched) are excluded,
     * same as {@link #findRegionBuzzForEntity}. Ordered by count descending.
     *
     * <p>Each branch's value is the latest {@code author_type_overrides} row for that mention (by
     * {@code created_at}) when one exists, else the raw upstream column — see
     * {@link com.aura.service.entity.AuthorTypeOverride} for why the override is a separate overlay
     * table rather than a write to {@code x_posts}/etc. A corrected post is counted under its
     * corrected bucket, matching what {@code GET /api/dashboard/{entityId}/mentions?authorType=...}
     * returns for the drill-down.
     */
    @Query(value = "SELECT author_type, COUNT(*) AS cnt FROM ( " +
            "  SELECT COALESCE((SELECT ato.new_category FROM author_type_overrides ato " +
            "    WHERE ato.mention_id = m.id ORDER BY ato.created_at DESC LIMIT 1), x.author_type) AS author_type " +
            "    FROM x_posts x " +
            "    JOIN mentions m ON m.post_id = x.id AND m.platform = 'X' " +
            "    JOIN mention_entities me ON me.mention_id = m.id " +
            "    WHERE me.managed_entity_id = :entityId " +
            "  UNION ALL " +
            "  SELECT COALESCE((SELECT ato.new_category FROM author_type_overrides ato " +
            "    WHERE ato.mention_id = m.id ORDER BY ato.created_at DESC LIMIT 1), y.author_type) AS author_type " +
            "    FROM youtube_comments y " +
            "    JOIN mentions m ON m.post_id = y.id AND m.platform = 'YOUTUBE' " +
            "    JOIN mention_entities me ON me.mention_id = m.id " +
            "    WHERE me.managed_entity_id = :entityId " +
            "  UNION ALL " +
            "  SELECT COALESCE((SELECT ato.new_category FROM author_type_overrides ato " +
            "    WHERE ato.mention_id = m.id ORDER BY ato.created_at DESC LIMIT 1), r.author_type) AS author_type " +
            "    FROM reddit_posts r " +
            "    JOIN mentions m ON m.post_id = r.id AND m.platform = 'REDDIT' " +
            "    JOIN mention_entities me ON me.mention_id = m.id " +
            "    WHERE me.managed_entity_id = :entityId " +
            "  UNION ALL " +
            "  SELECT COALESCE((SELECT ato.new_category FROM author_type_overrides ato " +
            "    WHERE ato.mention_id = m.id ORDER BY ato.created_at DESC LIMIT 1), i.author_type) AS author_type " +
            "    FROM instagram_posts i " +
            "    JOIN mentions m ON m.post_id = i.id AND m.platform = 'INSTAGRAM' " +
            "    JOIN mention_entities me ON me.mention_id = m.id " +
            "    WHERE me.managed_entity_id = :entityId " +
            ") types " +
            "WHERE author_type IS NOT NULL AND LOWER(author_type) <> 'irrelevant' " +
            "GROUP BY author_type " +
            "ORDER BY cnt DESC",
            nativeQuery = true)
    List<Object[]> findAuthorTypeBreakdownForEntity(@Param("entityId") Long entityId);

    /**
     * Current effective {@code author_type} for one mention — the latest override if one exists,
     * else the raw upstream value. Used by {@code override-author-type} to report {@code
     * previousCategory} accurately (what the caller was actually shown before this call), not just
     * the raw upstream value if a prior override already superseded it.
     */
    @Query(value = "SELECT COALESCE(" +
            "(SELECT ato.new_category FROM author_type_overrides ato WHERE ato.mention_id = :mentionId " +
            "  ORDER BY ato.created_at DESC LIMIT 1), " +
            "x.author_type, y.author_type, r.author_type, i.author_type) " +
            "FROM mentions m " +
            "LEFT JOIN x_posts x ON x.id = m.post_id AND m.platform = 'X' " +
            "LEFT JOIN youtube_comments y ON y.id = m.post_id AND m.platform = 'YOUTUBE' " +
            "LEFT JOIN reddit_posts r ON r.id = m.post_id AND m.platform = 'REDDIT' " +
            "LEFT JOIN instagram_posts i ON i.id = m.post_id AND m.platform = 'INSTAGRAM' " +
            "WHERE m.id = :mentionId",
            nativeQuery = true)
    String findCurrentAuthorType(@Param("mentionId") Long mentionId);

    /**
     * Post count per {@code content_intent} ({@code official_promo}, {@code fan_amplified_promo},
     * {@code organic_opinion}, {@code news_press_coverage}, {@code trade_box_office_update},
     * {@code ticket_merch_marketplace}, ...), for the "what kind of buzz" panel. Same
     * NULL/'irrelevant' exclusion and join pattern as {@link #findRegionBuzzForEntity}.
     *
     * <p>Each branch's value is the latest {@code content_intent_overrides} row for that mention (by
     * {@code created_at}) when one exists, else the raw upstream column — same overlay convention as
     * {@link #findAuthorTypeBreakdownForEntity}; see {@link com.aura.service.entity.ContentIntentOverride}.
     */
    @Query(value = "SELECT content_intent, COUNT(*) AS cnt FROM ( " +
            "  SELECT COALESCE((SELECT cio.new_category FROM content_intent_overrides cio " +
            "    WHERE cio.mention_id = m.id ORDER BY cio.created_at DESC LIMIT 1), x.content_intent) AS content_intent " +
            "    FROM x_posts x " +
            "    JOIN mentions m ON m.post_id = x.id AND m.platform = 'X' " +
            "    JOIN mention_entities me ON me.mention_id = m.id " +
            "    WHERE me.managed_entity_id = :entityId " +
            "  UNION ALL " +
            "  SELECT COALESCE((SELECT cio.new_category FROM content_intent_overrides cio " +
            "    WHERE cio.mention_id = m.id ORDER BY cio.created_at DESC LIMIT 1), y.content_intent) AS content_intent " +
            "    FROM youtube_comments y " +
            "    JOIN mentions m ON m.post_id = y.id AND m.platform = 'YOUTUBE' " +
            "    JOIN mention_entities me ON me.mention_id = m.id " +
            "    WHERE me.managed_entity_id = :entityId " +
            "  UNION ALL " +
            "  SELECT COALESCE((SELECT cio.new_category FROM content_intent_overrides cio " +
            "    WHERE cio.mention_id = m.id ORDER BY cio.created_at DESC LIMIT 1), r.content_intent) AS content_intent " +
            "    FROM reddit_posts r " +
            "    JOIN mentions m ON m.post_id = r.id AND m.platform = 'REDDIT' " +
            "    JOIN mention_entities me ON me.mention_id = m.id " +
            "    WHERE me.managed_entity_id = :entityId " +
            "  UNION ALL " +
            "  SELECT COALESCE((SELECT cio.new_category FROM content_intent_overrides cio " +
            "    WHERE cio.mention_id = m.id ORDER BY cio.created_at DESC LIMIT 1), i.content_intent) AS content_intent " +
            "    FROM instagram_posts i " +
            "    JOIN mentions m ON m.post_id = i.id AND m.platform = 'INSTAGRAM' " +
            "    JOIN mention_entities me ON me.mention_id = m.id " +
            "    WHERE me.managed_entity_id = :entityId " +
            "  ) intents " +
            "WHERE content_intent IS NOT NULL AND LOWER(content_intent) <> 'irrelevant' " +
            "GROUP BY content_intent " +
            "ORDER BY cnt DESC",
            nativeQuery = true)
    List<Object[]> findContentIntentBreakdownForEntity(@Param("entityId") Long entityId);

    /**
     * Current effective {@code content_intent} for one mention — the latest override if one exists,
     * else the raw upstream value. Same purpose as {@link #findCurrentAuthorType}.
     */
    @Query(value = "SELECT COALESCE(" +
            "(SELECT cio.new_category FROM content_intent_overrides cio WHERE cio.mention_id = :mentionId " +
            "  ORDER BY cio.created_at DESC LIMIT 1), " +
            "x.content_intent, y.content_intent, r.content_intent, i.content_intent) " +
            "FROM mentions m " +
            "LEFT JOIN x_posts x ON x.id = m.post_id AND m.platform = 'X' " +
            "LEFT JOIN youtube_comments y ON y.id = m.post_id AND m.platform = 'YOUTUBE' " +
            "LEFT JOIN reddit_posts r ON r.id = m.post_id AND m.platform = 'REDDIT' " +
            "LEFT JOIN instagram_posts i ON i.id = m.post_id AND m.platform = 'INSTAGRAM' " +
            "WHERE m.id = :mentionId",
            nativeQuery = true)
    String findCurrentContentIntent(@Param("mentionId") Long mentionId);

    /**
     * Post count per {@code topic_category} ({@code cast_performance}, {@code music_songs},
     * {@code story_screenplay}, {@code direction_technical_craft}, {@code box_office_commercial},
     * {@code politics_personal_life_crossover}, {@code general}, ...), for the "what aspects
     * resonate" panel. Same NULL/'irrelevant' exclusion and join pattern as
     * {@link #findRegionBuzzForEntity}.
     */
    /** Unique posters across an entity's whole history (no date/language/industry/state filter) — Reach panel. */
    @Query("SELECT COUNT(DISTINCT m.author) FROM Mention m WHERE m.author IS NOT NULL AND EXISTS " +
            "(SELECT e FROM m.managedEntities e WHERE e.id = :entityId)")
    long countDistinctAuthorsByEntityId(@Param("entityId") Long entityId);

    /**
     * Same "unique posters" count as {@link #countDistinctAuthorsByEntityId}, but reads the four raw
     * ingestion tables directly instead of going through {@code mentions}/{@code mention_entities} — each
     * table is joined straight to {@code entity_keywords} on {@code lower(<table>.keyword) = lower(keyword)},
     * the same keyword-equivalence {@link #linkExistingMentionsByKeyword} uses to populate
     * {@code mention_entities} in the first place, just applied live instead of through a backfill. This
     * is deliberately keyed on {@code keyword} rather than the raw tables' own {@code entity} column: that
     * column holds whatever string the ingestion pipeline happened to tag the row with (often an
     * abbreviation, e.g. {@code "GDN"} for a movie whose {@code managed_entities.name} is {@code "GD
     * Naidu"}) and does not reliably match {@code managed_entities.name}, which silently undercounts.
     * Rows with {@code author_type = 'irrelevant'} (an upstream classifier's judgment that the post isn't
     * really about the entity) are excluded; NULL/blank {@code author_type} (not yet classified) is still
     * included, matching {@link #findTotalViewsForEntity}'s convention.
     */
    @Query(value = "SELECT COUNT(DISTINCT author) FROM ( " +
            "  SELECT x.author AS author FROM x_posts x " +
            "    JOIN entity_keywords ek ON lower(x.keyword) = lower(ek.keyword) " +
            "    WHERE ek.entity_id = :entityId AND x.author_type IS DISTINCT FROM 'irrelevant' " +
            "  UNION ALL " +
            "  SELECT y.author FROM youtube_comments y " +
            "    JOIN entity_keywords ek ON lower(y.keyword) = lower(ek.keyword) " +
            "    WHERE ek.entity_id = :entityId AND y.author_type IS DISTINCT FROM 'irrelevant' " +
            "  UNION ALL " +
            "  SELECT r.author FROM reddit_posts r " +
            "    JOIN entity_keywords ek ON lower(r.keyword) = lower(ek.keyword) " +
            "    WHERE ek.entity_id = :entityId AND r.author_type IS DISTINCT FROM 'irrelevant' " +
            "  UNION ALL " +
            "  SELECT i.author FROM instagram_posts i " +
            "    JOIN entity_keywords ek ON lower(i.keyword) = lower(ek.keyword) " +
            "    WHERE ek.entity_id = :entityId AND i.author_type IS DISTINCT FROM 'irrelevant' " +
            "  ) authors",
            nativeQuery = true)
    long countDistinctAuthorsDirectByEntityId(@Param("entityId") Long entityId);

    /**
     * Total views for one entity's whole history, across all four platforms — Awareness panel. Each
     * platform's raw ingestion table has a different (or no) native view/impression column, so "views"
     * is a per-platform proxy: X uses {@code x_posts.views_count} directly; Reddit uses
     * {@code subreddit_subscribers} (reach of the subreddit a post landed in, standing in for
     * impressions since Reddit exposes no per-post view count) counted once per distinct subreddit the
     * entity was mentioned in, not once per post — otherwise an entity with several posts in the same
     * subreddit would have that subreddit's subscriber base added again for every post, inflating the
     * total; Instagram uses the {@code views} column,
     * falling back to {@code like_count + comments_count} when {@code views} is NULL or 0 (video-only
     * metric — photo posts have no views); YouTube uses {@code youtube_videos.view_count}, counted once
     * per distinct video rather than once per comment, since {@code mentions.post_id} for platform
     * {@code YOUTUBE} joins to {@code youtube_comments} and a single video can have many comments.
     * Every branch excludes posts/comments whose {@code author_type} is {@code 'irrelevant'} (an
     * upstream classifier's judgment that the post isn't really about the entity — e.g. an unrelated
     * keyword collision) — rows with a NULL {@code author_type} (not yet classified) are still included.
     */
    @Query(value = "SELECT COALESCE(SUM(v.views), 0) FROM ( " +
            "  SELECT x.views_count AS views FROM x_posts x " +
            "    JOIN mentions m ON m.post_id = x.id AND m.platform = 'X' " +
            "    JOIN mention_entities me ON me.mention_id = m.id " +
            "    WHERE me.managed_entity_id = :entityId AND x.author_type IS DISTINCT FROM 'irrelevant' " +
            "  UNION ALL " +
            "  SELECT MAX(r.subreddit_subscribers) AS views FROM reddit_posts r " +
            "    JOIN mentions m ON m.post_id = r.id AND m.platform = 'REDDIT' " +
            "    JOIN mention_entities me ON me.mention_id = m.id " +
            "    WHERE me.managed_entity_id = :entityId AND r.author_type IS DISTINCT FROM 'irrelevant' " +
            "    GROUP BY r.community_name " +
            "  UNION ALL " +
            "  SELECT COALESCE(NULLIF(i.views, 0), COALESCE(i.like_count, 0) + COALESCE(i.comments_count, 0)) AS views " +
            "    FROM instagram_posts i " +
            "    JOIN mentions m ON m.post_id = i.id AND m.platform = 'INSTAGRAM' " +
            "    JOIN mention_entities me ON me.mention_id = m.id " +
            "    WHERE me.managed_entity_id = :entityId AND i.author_type IS DISTINCT FROM 'irrelevant' " +
            "  UNION ALL " +
            "  SELECT yv.view_count AS views FROM ( " +
            "    SELECT DISTINCT yc.video_id FROM youtube_comments yc " +
            "      JOIN mentions m ON m.post_id = yc.id AND m.platform = 'YOUTUBE' " +
            "      JOIN mention_entities me ON me.mention_id = m.id " +
            "      WHERE me.managed_entity_id = :entityId AND yc.author_type IS DISTINCT FROM 'irrelevant' " +
            "  ) ev " +
            "  JOIN (SELECT video_id, MAX(view_count) AS view_count FROM youtube_videos GROUP BY video_id) yv " +
            "    ON yv.video_id = ev.video_id " +
            ") v",
            nativeQuery = true)
    long findTotalViewsForEntity(@Param("entityId") Long entityId);

    /**
     * Same total as {@link #findTotalViewsForEntity}, batched per entity — used by the Awareness panel
     * to rank one movie's views against the comparison set in a single query. An entity with no posts on
     * any platform (or no views yet) is simply absent from the result, not returned as a zero row.
     */
    @Query(value = "SELECT entity_id, SUM(views) FROM ( " +
            "  SELECT me.managed_entity_id AS entity_id, x.views_count AS views FROM x_posts x " +
            "    JOIN mentions m ON m.post_id = x.id AND m.platform = 'X' " +
            "    JOIN mention_entities me ON me.mention_id = m.id " +
            "    WHERE me.managed_entity_id IN (:entityIds) AND x.author_type IS DISTINCT FROM 'irrelevant' " +
            "  UNION ALL " +
            "  SELECT me.managed_entity_id AS entity_id, MAX(r.subreddit_subscribers) AS views FROM reddit_posts r " +
            "    JOIN mentions m ON m.post_id = r.id AND m.platform = 'REDDIT' " +
            "    JOIN mention_entities me ON me.mention_id = m.id " +
            "    WHERE me.managed_entity_id IN (:entityIds) AND r.author_type IS DISTINCT FROM 'irrelevant' " +
            "    GROUP BY me.managed_entity_id, r.community_name " +
            "  UNION ALL " +
            "  SELECT me.managed_entity_id AS entity_id, " +
            "    COALESCE(NULLIF(i.views, 0), COALESCE(i.like_count, 0) + COALESCE(i.comments_count, 0)) AS views " +
            "    FROM instagram_posts i " +
            "    JOIN mentions m ON m.post_id = i.id AND m.platform = 'INSTAGRAM' " +
            "    JOIN mention_entities me ON me.mention_id = m.id " +
            "    WHERE me.managed_entity_id IN (:entityIds) AND i.author_type IS DISTINCT FROM 'irrelevant' " +
            "  UNION ALL " +
            "  SELECT ev.managed_entity_id AS entity_id, yv.view_count AS views FROM ( " +
            "    SELECT DISTINCT me.managed_entity_id, yc.video_id FROM youtube_comments yc " +
            "      JOIN mentions m ON m.post_id = yc.id AND m.platform = 'YOUTUBE' " +
            "      JOIN mention_entities me ON me.mention_id = m.id " +
            "      WHERE me.managed_entity_id IN (:entityIds) AND yc.author_type IS DISTINCT FROM 'irrelevant' " +
            "  ) ev " +
            "  JOIN (SELECT video_id, MAX(view_count) AS view_count FROM youtube_videos GROUP BY video_id) yv " +
            "    ON yv.video_id = ev.video_id " +
            ") all_views " +
            "GROUP BY entity_id",
            nativeQuery = true)
    List<Object[]> findTotalViewsForEntities(@Param("entityIds") List<Long> entityIds);

    /**
     * Whether any tracked post for {@code entityId} contains {@code keyword} as a case-insensitive
     * substring. {@code mentions.content} already holds each platform's raw post text regardless of
     * which of the four ingestion tables it came from, so unlike the classification breakdowns above
     * this needs no per-platform join. Used by {@code RecommendedActionCandidateServiceImpl} to
     * detect a common marketing tactic (e.g. a teaser/trailer release) that's already happened for
     * this movie, so it isn't recommended again.
     */
    @Query("SELECT COUNT(m) > 0 FROM Mention m WHERE EXISTS " +
            "(SELECT e FROM m.managedEntities e WHERE e.id = :entityId) " +
            "AND LOWER(m.content) LIKE LOWER(CONCAT('%', :keyword, '%'))")
    boolean existsByManagedEntityIdAndContentContainingIgnoreCase(
            @Param("entityId") Long entityId, @Param("keyword") String keyword);

    /**
     * Whether {@code author} has already posted/commented about this entity at all. Used by
     * {@code RecommendedActionCandidateServiceImpl}'s cumulative-view-count-gap candidate to filter a
     * comparable, higher-viewed movie's viral-seed accounts down to genuinely new outreach prospects -
     * ones who haven't already engaged with this movie.
     */
    @Query("SELECT COUNT(m) > 0 FROM Mention m WHERE LOWER(m.author) = LOWER(:author) AND EXISTS " +
            "(SELECT e FROM m.managedEntities e WHERE e.id = :entityId)")
    boolean existsByManagedEntityIdAndAuthorIgnoreCase(
            @Param("entityId") Long entityId, @Param("author") String author);

    /**
     * The top 10 highest-viewed posts per author, among posts authored by any of {@code authors} that
     * are attributed to {@code entityId} - the "top spreader's top content" join. A spreader's
     * {@code globalUserId} (from AuraMath's top-50-spreaders payload, see
     * {@code TopSpreaderLookupService.SpreaderProfile}) is treated as directly comparable to
     * {@code mentions.author}, the same identity equivalence
     * {@code RecommendedActionCandidateServiceImpl}'s evangelist-mobilization candidate and
     * {@link #countSentimentByAuthorsForEntity} already rely on.
     * <p>The per-author top-10 cut happens in the database via {@code ROW_NUMBER() OVER (PARTITION BY
     * author ...)}, not after fetching every matching post into the JVM - a spreader with hundreds of
     * posts about one movie no longer means hundreds of rows pulled just to keep 10. Ranking uses the
     * same per-platform view proxy {@code TopSpreaderContentService} resolves separately for display (X's
     * real view count; Instagram's {@code views} column with a like+comment fallback; Reddit's
     * {@code subreddit_subscribers}; YouTube's video {@code view_count}, shared by every comment under
     * that video), same formulas as {@link #findTotalViewsForEntity}. A post with no resolvable view
     * proxy ranks last within its author ({@code NULLS LAST}), matching how the caller treats a null view
     * count when it re-sorts this (already-capped) result for display.
     */
    @Query(value = "SELECT ranked.id, ranked.platform, ranked.post_id, ranked.content, ranked.author, " +
            "ranked.post_date, ranked.sentiment, ranked.permalink, ranked.sentiment_score, " +
            "ranked.review_aspect_category FROM ( " +
            "  SELECT m.id, m.platform, m.post_id, m.content, m.author, m.post_date, m.sentiment, " +
            "         m.permalink, m.sentiment_score, m.review_aspect_category, " +
            "         ROW_NUMBER() OVER (PARTITION BY m.author ORDER BY v.views DESC NULLS LAST) AS rn " +
            "  FROM mentions m " +
            "  JOIN mention_entities me ON me.mention_id = m.id " +
            "  LEFT JOIN ( " +
            "    SELECT x.id AS post_id, 'X' AS platform, x.views_count AS views FROM x_posts x " +
            "    UNION ALL " +
            "    SELECT i.id, 'INSTAGRAM', COALESCE(NULLIF(i.views, 0), COALESCE(i.like_count, 0) + COALESCE(i.comments_count, 0)) " +
            "      FROM instagram_posts i " +
            "    UNION ALL " +
            "    SELECT r.id, 'REDDIT', r.subreddit_subscribers FROM reddit_posts r " +
            "    UNION ALL " +
            "    SELECT yc.id, 'YOUTUBE', yv.view_count FROM youtube_comments yc " +
            "      JOIN (SELECT video_id, MAX(view_count) AS view_count FROM youtube_videos GROUP BY video_id) yv " +
            "      ON yv.video_id = yc.video_id " +
            "  ) v ON v.post_id = m.post_id AND v.platform = m.platform " +
            "  WHERE m.author IN (:authors) AND me.managed_entity_id = :entityId " +
            ") ranked WHERE ranked.rn <= 10",
           nativeQuery = true)
    List<Mention> findByManagedEntityIdAndAuthorIn(
            @Param("entityId") Long entityId, @Param("authors") Collection<String> authors);

    // ---- Per-post view proxies, used alongside findXPostViewsCounts to rank a spreader's own posts.
    // Same per-platform formulas as findTotalViewsForEntity, returned per row instead of summed.

    /** Instagram's views column, falling back to like_count + comments_count when NULL/0 (photo posts). */
    @Query(value = "SELECT i.id, COALESCE(NULLIF(i.views, 0), COALESCE(i.like_count, 0) + COALESCE(i.comments_count, 0)) " +
            "FROM instagram_posts i WHERE i.id IN (:postIds)", nativeQuery = true)
    List<Object[]> findInstagramPostViews(@Param("postIds") Collection<String> postIds);

    /**
     * Reddit exposes no per-post view count; subreddit_subscribers (the community's reach) is reused as
     * the closest available proxy, same as findTotalViewsForEntity - every post in the same subreddit
     * shows the same figure.
     */
    @Query(value = "SELECT r.id, r.subreddit_subscribers FROM reddit_posts r WHERE r.id IN (:postIds)",
           nativeQuery = true)
    List<Object[]> findRedditPostViews(@Param("postIds") Collection<String> postIds);

    /**
     * {@code mentions.post_id} for platform YOUTUBE points at a comment row, not the video itself, so
     * every comment under the same video shows that video's view_count - same per-video (not
     * per-comment) attribution findTotalViewsForEntity uses.
     */
    @Query(value = "SELECT yc.id, yv.view_count FROM youtube_comments yc " +
            "JOIN (SELECT video_id, MAX(view_count) AS view_count FROM youtube_videos GROUP BY video_id) yv " +
            "ON yv.video_id = yc.video_id WHERE yc.id IN (:postIds)", nativeQuery = true)
    List<Object[]> findYoutubePostViews(@Param("postIds") Collection<String> postIds);

    /**
     * Each branch's value is the latest {@code topic_category_overrides} row for that mention (by
     * {@code created_at}) when one exists, else the raw upstream column — same overlay convention as
     * {@link #findAuthorTypeBreakdownForEntity}; see {@link com.aura.service.entity.TopicCategoryOverride}.
     */
    @Query(value = "SELECT topic_category, COUNT(*) AS cnt FROM ( " +
            "  SELECT COALESCE((SELECT tco.new_category FROM topic_category_overrides tco " +
            "    WHERE tco.mention_id = m.id ORDER BY tco.created_at DESC LIMIT 1), x.topic_category) AS topic_category " +
            "    FROM x_posts x " +
            "    JOIN mentions m ON m.post_id = x.id AND m.platform = 'X' " +
            "    JOIN mention_entities me ON me.mention_id = m.id " +
            "    WHERE me.managed_entity_id = :entityId " +
            "  UNION ALL " +
            "  SELECT COALESCE((SELECT tco.new_category FROM topic_category_overrides tco " +
            "    WHERE tco.mention_id = m.id ORDER BY tco.created_at DESC LIMIT 1), y.topic_category) AS topic_category " +
            "    FROM youtube_comments y " +
            "    JOIN mentions m ON m.post_id = y.id AND m.platform = 'YOUTUBE' " +
            "    JOIN mention_entities me ON me.mention_id = m.id " +
            "    WHERE me.managed_entity_id = :entityId " +
            "  UNION ALL " +
            "  SELECT COALESCE((SELECT tco.new_category FROM topic_category_overrides tco " +
            "    WHERE tco.mention_id = m.id ORDER BY tco.created_at DESC LIMIT 1), r.topic_category) AS topic_category " +
            "    FROM reddit_posts r " +
            "    JOIN mentions m ON m.post_id = r.id AND m.platform = 'REDDIT' " +
            "    JOIN mention_entities me ON me.mention_id = m.id " +
            "    WHERE me.managed_entity_id = :entityId " +
            "  UNION ALL " +
            "  SELECT COALESCE((SELECT tco.new_category FROM topic_category_overrides tco " +
            "    WHERE tco.mention_id = m.id ORDER BY tco.created_at DESC LIMIT 1), i.topic_category) AS topic_category " +
            "    FROM instagram_posts i " +
            "    JOIN mentions m ON m.post_id = i.id AND m.platform = 'INSTAGRAM' " +
            "    JOIN mention_entities me ON me.mention_id = m.id " +
            "    WHERE me.managed_entity_id = :entityId " +
            ") topics " +
            "WHERE topic_category IS NOT NULL AND LOWER(topic_category) <> 'irrelevant' " +
            "GROUP BY topic_category " +
            "ORDER BY cnt DESC",
            nativeQuery = true)
    List<Object[]> findTopicCategoryBreakdownForEntity(@Param("entityId") Long entityId);

    /**
     * Current effective {@code topic_category} for one mention — the latest override if one exists,
     * else the raw upstream value. Same purpose as {@link #findCurrentAuthorType}.
     */
    @Query(value = "SELECT COALESCE(" +
            "(SELECT tco.new_category FROM topic_category_overrides tco WHERE tco.mention_id = :mentionId " +
            "  ORDER BY tco.created_at DESC LIMIT 1), " +
            "x.topic_category, y.topic_category, r.topic_category, i.topic_category) " +
            "FROM mentions m " +
            "LEFT JOIN x_posts x ON x.id = m.post_id AND m.platform = 'X' " +
            "LEFT JOIN youtube_comments y ON y.id = m.post_id AND m.platform = 'YOUTUBE' " +
            "LEFT JOIN reddit_posts r ON r.id = m.post_id AND m.platform = 'REDDIT' " +
            "LEFT JOIN instagram_posts i ON i.id = m.post_id AND m.platform = 'INSTAGRAM' " +
            "WHERE m.id = :mentionId",
            nativeQuery = true)
    String findCurrentTopicCategory(@Param("mentionId") Long mentionId);

    /**
     * A bounded, oldest-first batch of this entity's posts not yet classified into
     * {@link com.aura.service.enums.ReviewAspectCategory} — the backlog
     * {@code ReviewAspectBreakdownService} works through per LLM call. Bounded by {@code pageable} so
     * one classification pass never sends an unbounded number of posts to the LLM in a single request.
     */
    @Query("SELECT m FROM Mention m WHERE m.reviewAspectCategory IS NULL AND EXISTS " +
            "(SELECT e FROM m.managedEntities e WHERE e.id = :entityId) " +
            "ORDER BY m.postDate ASC")
    List<Mention> findUnclassifiedReviewAspectMentions(@Param("entityId") Long entityId, Pageable pageable);

    /**
     * Post count and average {@code sentiment_score} per {@link com.aura.service.enums.ReviewAspectCategory}
     * for the "Aspect Sentiment" breakdown panel. Unlike {@link #findTopicCategoryBreakdownForEntity},
     * this reads the already-classified {@code mentions.review_aspect_category} column directly (no
     * per-platform union needed — classification is assigned once per {@link Mention} row regardless of
     * platform) and excludes posts not yet classified, same NULL-exclusion convention as the topic-category
     * query.
     */
    @Query("SELECT m.reviewAspectCategory, COUNT(m), AVG(m.sentimentScore) FROM Mention m " +
            "WHERE m.reviewAspectCategory IS NOT NULL AND EXISTS " +
            "(SELECT e FROM m.managedEntities e WHERE e.id = :entityId) " +
            "GROUP BY m.reviewAspectCategory")
    List<Object[]> findReviewAspectBreakdownForEntity(@Param("entityId") Long entityId);

    /**
     * Majority {@code sentiment} label and post-date span per {@link com.aura.service.enums.ReviewAspectCategory},
     * read straight off {@code mentions} (no per-platform join needed, same as
     * {@link #findReviewAspectBreakdownForEntity}). {@code MODE() WITHIN GROUP} is Postgres's built-in
     * "most frequent value" ordered-set aggregate; the date span feeds
     * {@code ReviewAspectBreakdownService}'s posts-per-day figure for each aspect.
     */
    @Query(value = "SELECT m.review_aspect_category, MODE() WITHIN GROUP (ORDER BY m.sentiment), " +
            "MIN(m.post_date), MAX(m.post_date) FROM mentions m " +
            "JOIN mention_entities me ON me.mention_id = m.id " +
            "WHERE me.managed_entity_id = :entityId AND m.review_aspect_category IS NOT NULL " +
            "GROUP BY m.review_aspect_category",
            nativeQuery = true)
    List<Object[]> findReviewAspectSentimentAndDateRangeForEntity(@Param("entityId") Long entityId);

    /**
     * Total views/likes/comments per {@link com.aura.service.enums.ReviewAspectCategory}, for the
     * "Aspect Sentiment" panel's views/engagement figures. {@code Mention} itself carries none of these
     * (see {@link #findReviewAspectBreakdownForEntity}'s Javadoc), so this joins the four raw platform
     * tables the same way {@link #findTotalViewsForEntity} does, reusing its per-platform view proxy and
     * {@code author_type <> 'irrelevant'} exclusion: X's real {@code views_count}; Reddit's
     * {@code subreddit_subscribers} counted once per (category, subreddit) rather than once per post, to
     * avoid inflating a subreddit's reach every time another post in it lands in the same aspect; Instagram's
     * {@code views} falling back to {@code like_count + comments_count}; YouTube's video {@code view_count}
     * counted once per (category, video) rather than once per comment. A video whose comments span more than
     * one aspect has its views counted once per aspect it appears in, not split between them - the same
     * "no clean per-comment attribution" trade-off {@code findTotalViewsForEntity} accepts, just applied per
     * category too. Likes use each platform's like-equivalent ({@code likes_count}/{@code score}); comments
     * use {@code comment_count}/{@code num_comments}/{@code comments_count}/{@code reply_count} - same
     * columns {@link #findXPostEngagement} and its per-platform siblings read for the spreader-content
     * engagement rate.
     */
    @Query(value = "SELECT category, SUM(views) AS total_views, SUM(likes) AS total_likes, " +
            "SUM(comments) AS total_comments FROM ( " +
            "  SELECT m.review_aspect_category AS category, x.views_count AS views, " +
            "    x.likes_count AS likes, x.comment_count AS comments FROM x_posts x " +
            "    JOIN mentions m ON m.post_id = x.id AND m.platform = 'X' " +
            "    JOIN mention_entities me ON me.mention_id = m.id " +
            "    WHERE me.managed_entity_id = :entityId AND m.review_aspect_category IS NOT NULL " +
            "      AND x.author_type IS DISTINCT FROM 'irrelevant' " +
            "  UNION ALL " +
            "  SELECT m.review_aspect_category AS category, MAX(r.subreddit_subscribers) AS views, " +
            "    SUM(r.score) AS likes, SUM(r.num_comments) AS comments FROM reddit_posts r " +
            "    JOIN mentions m ON m.post_id = r.id AND m.platform = 'REDDIT' " +
            "    JOIN mention_entities me ON me.mention_id = m.id " +
            "    WHERE me.managed_entity_id = :entityId AND m.review_aspect_category IS NOT NULL " +
            "      AND r.author_type IS DISTINCT FROM 'irrelevant' " +
            "    GROUP BY m.review_aspect_category, r.community_name " +
            "  UNION ALL " +
            "  SELECT m.review_aspect_category AS category, " +
            "    COALESCE(NULLIF(i.views, 0), COALESCE(i.like_count, 0) + COALESCE(i.comments_count, 0)) AS views, " +
            "    i.like_count AS likes, i.comments_count AS comments FROM instagram_posts i " +
            "    JOIN mentions m ON m.post_id = i.id AND m.platform = 'INSTAGRAM' " +
            "    JOIN mention_entities me ON me.mention_id = m.id " +
            "    WHERE me.managed_entity_id = :entityId AND m.review_aspect_category IS NOT NULL " +
            "      AND i.author_type IS DISTINCT FROM 'irrelevant' " +
            "  UNION ALL " +
            "  SELECT ev.category AS category, yv.view_count AS views, ev.likes AS likes, ev.comments AS comments FROM ( " +
            "    SELECT m.review_aspect_category AS category, yc.video_id AS video_id, " +
            "      SUM(yc.likes_count) AS likes, SUM(yc.reply_count) AS comments FROM youtube_comments yc " +
            "      JOIN mentions m ON m.post_id = yc.id AND m.platform = 'YOUTUBE' " +
            "      JOIN mention_entities me ON me.mention_id = m.id " +
            "      WHERE me.managed_entity_id = :entityId AND m.review_aspect_category IS NOT NULL " +
            "        AND yc.author_type IS DISTINCT FROM 'irrelevant' " +
            "      GROUP BY m.review_aspect_category, yc.video_id " +
            "  ) ev " +
            "  JOIN (SELECT video_id, MAX(view_count) AS view_count FROM youtube_videos GROUP BY video_id) yv " +
            "    ON yv.video_id = ev.video_id " +
            ") metrics " +
            "GROUP BY category",
            nativeQuery = true)
    List<Object[]> findReviewAspectViewsAndEngagementForEntity(@Param("entityId") Long entityId);

    /**
     * Oldest-first, global (not entity-scoped) backlog of posts not yet classified into a
     * {@link com.aura.service.enums.ReviewAspectCategory} — used by
     * {@code ReviewAspectBreakdownService#classifyPendingMentions}'s scheduled sweep. Global rather than
     * per-entity since classification is a property of the post's own content, independent of which
     * entity(ies) it's linked to, so a post shared by two entities is never classified twice.
     */
    @Query("SELECT m FROM Mention m WHERE m.reviewAspectCategory IS NULL ORDER BY m.postDate ASC")
    List<Mention> findUnclassifiedReviewAspectMentions(Pageable pageable);
}
