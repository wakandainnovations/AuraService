package com.aura.service.service;

import java.util.Collection;
import java.util.List;

/**
 * Per-movie, per-platform post volume, views and engagement from the mention tables joined to the
 * per-platform ingestion tables. Deliberately has no author/unique-user count. An interface so the
 * rights-valuation report can be tested with a lambda.
 */
public interface SocialReachLookup {

    /**
     * @param views    X {@code views_count} / Instagram {@code views}; 0 on platforms without views
     * @param likes    likes; Reddit's score (floored at 0) stands in for likes
     * @param comments comments or replies
     * @param shares   X {@code shares_count} / Instagram {@code reshare_count}
     */
    record PlatformTotals(Long entityId, String platform, long posts, long positivePosts, long negativePosts,
                          long views, long likes, long comments, long shares) {
    }

    /**
     * @param postsBeforeReleaseOffsetDays null for every post; otherwise only posts made before each
     *                                     movie's own release date minus this many days, so movies
     *                                     can be compared at the same stage before release
     */
    List<PlatformTotals> totalsByPlatform(Collection<Long> entityIds, Integer postsBeforeReleaseOffsetDays);
}
