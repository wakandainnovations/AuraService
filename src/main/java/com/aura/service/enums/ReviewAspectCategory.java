package com.aura.service.enums;

/**
 * Fixed taxonomy for {@code Mention#getReviewAspectCategory()} — which aspect of a movie a post is
 * mainly talking about, used by the "Aspect Sentiment" breakdown panel. Unlike {@code topic_category}
 * (populated upstream of this service), no existing data source carries this categorization, so it is
 * assigned per post by {@code ReviewAspectBreakdownService}'s LLM classification pass. {@code OTHER}
 * is the catch-all for posts that don't clearly fit any other category.
 */
public enum ReviewAspectCategory {
    MUSIC_SONGS,
    DIRECTION,
    ACTING_CAST_PERFORMANCE,
    STORY,
    SCREENPLAY,
    LEAD_PAIR,
    RUNTIME,
    FIRST_HALF,
    SECOND_HALF,
    CLIMAX,
    VFX,
    OTHER
}
