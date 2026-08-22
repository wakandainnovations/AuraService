package com.aura.service.dto;

import com.aura.service.enums.Platform;
import com.aura.service.enums.Sentiment;

import java.time.Instant;

/**
 * One post from a top spreader's content, with the metrics the caller asked for: view count,
 * engagement rate, and sentiment. {@code views} is a per-platform proxy (see
 * {@code MentionRepository#findInstagramPostViews}/{@code findRedditPostViews}/
 * {@code findYoutubePostViews}/{@code findXPostViewsCounts}) rather than a uniform metric across
 * platforms - Reddit's figure is its subreddit's subscriber count, and YouTube's is the video's view
 * count shared by every comment under it. {@code engagementRate} is {@code (likes + comments) / views}
 * and is null when {@code views} is null or zero (no denominator to divide by).
 */
public record SpreaderPostContent(
        Long mentionId,
        Platform platform,
        String postId,
        String content,
        String permalink,
        Instant postDate,
        Long views,
        long likes,
        long comments,
        Double engagementRate,
        Sentiment sentiment,
        Short sentimentScore) {
}
