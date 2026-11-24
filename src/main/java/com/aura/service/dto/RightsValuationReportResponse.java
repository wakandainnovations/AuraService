package com.aura.service.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Rights-valuation report for one tracked movie: how it performed against comparable films (similar
 * budget, similar audience rating, same genre and scale, the lead star's other films), meant for a
 * production house negotiating OTT or satellite rights.
 *
 * <p>Every number is computed in Java from {@code india_movies_data_collection} and AuraService's own
 * mention/engagement tables; nothing is LLM-generated. By design the report never carries a count of
 * unique users/authors talking about the movie - only post volume, views, engagement and sentiment.
 * Money is in {@link #currency}.
 */
public record RightsValuationReportResponse(
        MovieProfile movie,
        String currency,
        /** Rupees per dollar used for conversion; null when the report is in USD. */
        Double usdToInrRate,
        LocalDate exchangeRateDate,
        /** Peer comparisons where the movie is in the top 40% of its cohort, strongest first. */
        List<String> sellingPoints,
        /**
         * Peer comparisons where the movie is in the bottom 40% - internal prep for the points a
         * platform's buyer is likely to raise. Not rendered in the PDF handed to the buyer.
         */
        List<String> negotiationWatchouts,
        List<PeerCohort> peerCohorts,
        SocialReach socialReach,
        List<String> methodologyNotes,
        Instant generatedAt) {

    /** The movie's own facts and performance numbers. Any field is null when no data source has it. */
    public record MovieProfile(
            Long entityId,
            String name,
            String language,
            String industry,
            String genre,
            LocalDate releaseDate,
            /** RELEASED, or UPCOMING when the release date is still ahead (most box-office fields are then null). */
            String releaseStatus,
            /** Days until release for an UPCOMING movie with a known date; null otherwise. */
            Integer daysToRelease,
            Integer runtimeMinutes,
            String cbfcRating,
            /** Every language the film was released in (dubbed versions included). */
            List<String> releaseLanguages,
            Integer screens,
            String productionCompanies,
            String leadActor,
            String director,
            String musicDirector,
            Double budget,
            Double worldwideGross,
            /** Worldwide gross / budget. */
            Double returnOnBudget,
            /** Audience rating out of 10. */
            Double audienceRating,
            Long trailerViews,
            Long teaserViews,
            Long songViews,
            /** Trailer + teaser + first-song YouTube views. */
            Long totalVideoViews,
            Long trailerComments,
            Long trailerViewsWeekBeforeRelease,
            /** Whether the movie was found in the box-office dataset; false leaves most fields null. */
            boolean boxOfficeDataAvailable) {
    }

    /** One group of comparable films and where the movie ranks within it. */
    public record PeerCohort(
            /** SIMILAR_BUDGET, SIMILAR_RATING, SIMILAR_GENRE_AND_SCALE or SAME_LEAD_ACTOR. */
            String kind,
            String label,
            /** How the peers were chosen, in plain words. */
            String criteria,
            int peerCount,
            /** What the peers themselves earned - the benchmark that matters most before release. */
            CohortOutcomes outcomes,
            /**
             * The movie ranked against the peers. Before release only pre-release metrics (video views)
             * the movie already has are ranked.
             */
            List<MetricComparison> metrics,
            /** The peers closest to the movie on the cohort's defining dimension. */
            List<ComparableFilm> closestPeers) {
    }

    /**
     * Box-office results of a cohort's peers.
     *
     * @param hits       peers the dataset classifies as a hit
     * @param classified peers with a hit / average / flop classification
     * @param hitRate    hits / classified as 0-100; null when fewer than 3 are classified
     */
    public record CohortOutcomes(int filmsWithGross, Double medianGross, Double topQuartileGross,
                                 Double medianReturnOnBudget, int hits, int classified, Integer hitRate) {
    }

    /** The movie's value for one metric against the cohort's values for it. */
    public record MetricComparison(
            String metric,
            String label,
            /** MONEY, RATIO, RATING, COUNT or PERCENT. */
            String unit,
            Double value,
            Double peerMedian,
            Double peerTopQuartile,
            /** Share of peers (0-100) the movie is ahead of, ties counting half. Null without enough peers. */
            Integer percentile,
            /** 1 = best. Null without enough peers. */
            Integer rank,
            /** Peers that have this metric. Ranking needs at least 3. */
            int peersWithData,
            /** value / peerMedian. */
            Double multipleOfMedian) {
    }

    public record ComparableFilm(
            String title,
            LocalDate releaseDate,
            String language,
            Double budget,
            Double worldwideGross,
            Double returnOnBudget,
            Double audienceRating,
            Long totalVideoViews,
            /** The dataset's hit / avg / flop classification, when known. */
            String verdict,
            /** Whether our movie grossed more than this film; null when either gross is unknown. */
            Boolean outgrossedByThisMovie) {
    }

    /**
     * Live social reach from AuraService's tracked posts, ranked against other tracked movies in the
     * same language. For an upcoming movie the peers are compared at the same stage: only their posts
     * up to the same number of days before their own release count. Peer movies are never named (they may belong to other customers).
     */
    public record SocialReach(
            long posts,
            /** X + Instagram views; the other platforms expose no view count. */
            long views,
            long likes,
            long comments,
            long shares,
            /** Likes + comments + shares. */
            long engagement,
            Double engagementPerPost,
            /** Positive posts as a share (0-100) of all posts. */
            Double positiveShare,
            /** (positive - negative) / posts, as -100..100. */
            Double netSentiment,
            List<PlatformReach> platforms,
            int trackedPeerCount,
            /** What the peer comparison covers, e.g. all tracked posts, or posts up to N days before release. */
            String comparisonBasis,
            List<MetricComparison> peerComparison) {
    }

    public record PlatformReach(String platform, long posts, long views, long engagement) {
    }
}
