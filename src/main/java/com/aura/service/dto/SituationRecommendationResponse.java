package com.aura.service.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * "Social Buzz Situation" panel: a snapshot of this movie's own last-7-days/last-24h post activity
 * (see {@link com.aura.service.service.SituationRecommendationService}) plus one LLM-written
 * recommended action that references how a real movie in the past handled a comparable situation.
 * Every count/theme/excerpt/view-count field here is computed straight from this platform's own data;
 * only {@code recommendedAction}, {@code referencedMovie}, {@code whatThatMovieDid}, and
 * {@code rationale} are LLM-authored prose - see the service's own javadoc for why a real historical
 * precedent can't be grounded in this platform's own database the way every number on this response is.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SituationRecommendationResponse {

    private Long entityId;
    private String entityName;
    private String genre;
    private String language;
    private String industry;

    /** Signed day-offset of "today" from the movie's release date (negative = before release, positive
     *  = after) - same convention as {@link RecommendedActionsResponse#getDaysToRelease()}. Null when
     *  the entity has no release date on file. */
    private Integer daysToRelease;

    /** True as soon as this movie has at least one tracked post in the last 7 days. */
    private boolean hasSocialActivity;

    private long postsLast7Days;
    private long positiveCountLast7Days;
    private long negativeCountLast7Days;
    private long neutralCountLast7Days;

    private long postsLast24Hours;
    private long positiveCountLast24Hours;
    private long negativeCountLast24Hours;

    /**
     * True when the last-24h negative volume is disproportionate to the last-7-days daily average -
     * see {@code SituationRecommendationService#BURST_MULTIPLIER}/{@code BURST_MIN_ABSOLUTE_NEGATIVE_POSTS}.
     */
    private boolean negativeBurstDetected;

    /** What the negativity is about, e.g. "STORY (8 posts)" - built from real, already-classified
     *  {@code Mention#getReviewAspectCategory()} counts among this window's negative posts; empty when
     *  none of those posts have been classified yet. */
    private List<String> negativityThemes;

    /** Up to a few real negative post excerpts from the last 7 days, most recent first. */
    private List<String> keyNegativePoints;

    /** Up to a few real positive post excerpts from the last 7 days, most recent first. */
    private List<String> keyPositivePoints;

    /** This movie's own cumulative tracked view count across platforms - see MentionRepository#findTotalViewsForEntity. */
    private long ownTotalViews;

    /** Whether {@code comparableMovies}/{@code comparableAvgRevenue} are budget-scoped (+/-50%) or a
     *  genre+language fallback because this movie has no real budget on file. */
    private boolean comparableMoviesBudgetScoped;

    /** A few real, named comparable movies (same budget tier, or genre+language when no budget is on
     *  file) and their own tracked view counts, ranked highest-viewed first. */
    private List<ComparableMovieView> comparableMovies;

    /** Real average revenue across genre+language(+budget) comps on file, when available - see
     *  MoviesDataCollectionQueryService#findGenreLanguageBudgetComps. Null when no comps matched. */
    private Double comparableAvgRevenue;

    private Long comparableSampleCount;

    private String recommendedAction;
    private String referencedMovie;
    private String whatThatMovieDid;
    private String rationale;

    private Instant generatedAt;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ComparableMovieView {
        private String name;
        private long totalViews;
        private Double budget;
    }
}
