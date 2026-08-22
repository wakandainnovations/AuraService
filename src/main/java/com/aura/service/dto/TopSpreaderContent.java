package com.aura.service.dto;

import java.util.List;

/**
 * One top spreader (identified by AuraMath's {@code globalUserId}, see
 * {@code TopSpreaderLookupService.SpreaderProfile}) and their top content for the requested entity,
 * ranked by resolved view count. {@code totalViews} is AuraMath's own aggregate reach figure for this
 * author, kept for context even when {@code topContent} is empty - a spreader can be real per AuraMath
 * with no locally-ingested posts yet found under their identity for this entity.
 */
public record TopSpreaderContent(
        String globalUserId,
        String profileUrl,
        long totalViews,
        List<SpreaderPostContent> topContent) {
}
