package com.aura.service.dto;

import java.util.List;

/**
 * Per-movie audience breakdown: every unique user who posted about the movie (non-zero sentiment
 * score mentions only), with per-user engagement metadata. {@code users} is sorted by
 * {@link UserEngagementStats#postCount()} descending and capped at the requested/default limit -
 * see {@code MovieAudienceServiceImpl#getMovieAudienceDetail}.
 */
public record MovieAudienceDetailResponse(
        String movieName,
        String language,
        long uniqueAudienceCount,
        long totalPosts,
        List<UserEngagementStats> users) {
}
