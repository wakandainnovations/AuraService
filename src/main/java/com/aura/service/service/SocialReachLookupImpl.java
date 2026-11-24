package com.aura.service.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;

/**
 * One native aggregate over {@code mention_entities} → {@code mentions} → the four ingestion tables,
 * joined on {@code mentions.post_id} and disambiguated by platform (each table names its engagement
 * columns differently - see {@code MentionRepository}'s find*Engagement queries).
 */
@Service
public class SocialReachLookupImpl implements SocialReachLookup {

    private static final String TOTALS_SQL =
            "SELECT me.managed_entity_id, m.platform, COUNT(*)," +
            "    COUNT(*) FILTER (WHERE m.sentiment = 'POSITIVE')," +
            "    COUNT(*) FILTER (WHERE m.sentiment = 'NEGATIVE')," +
            "    COALESCE(SUM(COALESCE(x.views_count, i.views)), 0)," +
            "    COALESCE(SUM(COALESCE(x.likes_count, y.likes_count, GREATEST(r.score, 0), i.like_count)), 0)," +
            "    COALESCE(SUM(COALESCE(x.comment_count, y.reply_count, r.num_comments, i.comments_count)), 0)," +
            "    COALESCE(SUM(COALESCE(x.shares_count, i.reshare_count)), 0)" +
            " FROM mention_entities me" +
            " JOIN mentions m ON m.id = me.mention_id" +
            " LEFT JOIN x_posts x ON m.platform = 'X' AND x.id = m.post_id" +
            " LEFT JOIN youtube_comments y ON m.platform = 'YOUTUBE' AND y.id = m.post_id" +
            " LEFT JOIN reddit_posts r ON m.platform = 'REDDIT' AND r.id = m.post_id" +
            " LEFT JOIN instagram_posts i ON m.platform = 'INSTAGRAM' AND i.id = m.post_id" +
            " WHERE me.managed_entity_id IN (:entityIds)%s" +
            " GROUP BY me.managed_entity_id, m.platform";

    // Same stage before release: a post counts only if it was made before release_date - :days.
    private static final String BEFORE_RELEASE_FILTER =
            " AND m.post_date < (SELECT CAST(e.release_date AS timestamp) - make_interval(days => :days)" +
            "     FROM managed_entities e WHERE e.id = me.managed_entity_id)";

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @SuppressWarnings("unchecked")
    public List<PlatformTotals> totalsByPlatform(Collection<Long> entityIds, Integer postsBeforeReleaseOffsetDays) {
        if (entityIds.isEmpty()) {
            return List.of();
        }
        boolean staged = postsBeforeReleaseOffsetDays != null;
        Query query = entityManager.createNativeQuery(TOTALS_SQL.formatted(staged ? BEFORE_RELEASE_FILTER : ""))
                .setParameter("entityIds", entityIds);
        if (staged) {
            query.setParameter("days", postsBeforeReleaseOffsetDays);
        }
        List<Object[]> rows = query.getResultList();
        return rows.stream()
                .map(row -> new PlatformTotals(((Number) row[0]).longValue(), (String) row[1],
                        num(row[2]), num(row[3]), num(row[4]), num(row[5]), num(row[6]), num(row[7]), num(row[8])))
                .toList();
    }

    private static long num(Object value) {
        return value == null ? 0 : ((Number) value).longValue();
    }
}
