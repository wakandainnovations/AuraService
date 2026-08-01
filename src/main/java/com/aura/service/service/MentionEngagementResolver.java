package com.aura.service.service;

import com.aura.service.enums.Platform;

import java.util.List;
import java.util.Map;

/**
 * Batches postId -&gt; [likes, comments] lookups against the per-platform ingestion tables
 * (x_posts/youtube_comments/reddit_posts/instagram_posts), grouping by platform first since each
 * table names its engagement columns differently (see {@code MentionRepository}'s find*Engagement
 * queries). Extracted so every feature that needs real engagement numbers - not just post/author
 * counts - shares one implementation of the per-platform dispatch, the same one
 * {@link GraphSyncService}'s USER-node weighting already relies on.
 *
 * <p>Defined as an interface (mirroring {@link EntityAccessService} / {@link MovieAudienceService})
 * so callers can mock it with an interface rather than a concrete class in unit tests.
 */
public interface MentionEngagementResolver {

    /** postId -&gt; [likes, comments]. A postId absent from its platform table maps to [0, 0]. */
    Map<String, long[]> resolve(Map<Platform, List<String>> postIdsByPlatform);
}
