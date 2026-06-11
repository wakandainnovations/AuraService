package com.aura.service.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A complete, prospect-facing marketing intelligence report for a single managed entity.
 *
 * <p>Aggregates this service's own analytics (headline reach metrics, sentiment over time,
 * competitive positioning, platform reach, defining moments) with the upstream AuraMath
 * entity-report ({@code GET /api/marketing/entity-report/{entityId}}). It is designed to be shown
 * at a high level to potential customers of a production house, so the most flattering, headline
 * numbers are surfaced first and a deterministic {@code highlights} narrative summarises them.
 *
 * <p>Optional sections degrade gracefully: if a downstream source (e.g. AuraMath) is unavailable,
 * its section is {@code null} (see {@link #auraMathStatus}) rather than failing the whole report.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EntityMarketingReportResponse {

    /** Server time the report was assembled. */
    private Instant generatedAt;

    /** The time window the trend / momentum sections were computed over (e.g. DAY30). */
    private String period;

    /** Core entity profile: name, type, director, cast, keywords, competitors, release date. */
    private EntityDetailResponse entity;

    /** The big, prospect-facing numbers shown at the top of the report. */
    private HeadlineMetrics headlineMetrics;

    /** Where this entity sits versus its tracked competitors. */
    private CompetitivePositioning competitivePositioning;

    /** Sentiment time series (plus checkpoint markers) for the selected period. */
    private SentimentOverTimeResponse sentimentTrend;

    /** Per-platform mention counts broken down by sentiment: {platform -> {sentiment -> count}}. */
    private Map<String, Map<String, Long>> platformReach;

    /** Before/after impact of the entity's defining moments (campaign beats, releases, etc.). */
    private CheckpointImpactResponse definingMoments;

    /** The upstream AuraMath entity-report payload, forwarded verbatim, or {@code null} if unavailable. */
    private JsonNode auraMathIntelligence;

    /** {@code "ok"} when the AuraMath report was embedded, otherwise {@code "unavailable"}. */
    private String auraMathStatus;

    /** Deterministic, human-readable highlight bullets derived from the metrics above. */
    private List<String> highlights;

    /**
     * The headline reach and sentiment numbers. Ratios are 0..1 fractions; {@code netSentimentScore}
     * is positive-over-negative mentions (a value of 4.0 means four positive mentions per negative).
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HeadlineMetrics {
        private long totalMentions;
        private double overallSentiment;
        private double positivityRatio;
        private double positiveSentiment;
        private double negativeSentiment;
        private double neutralSentiment;
        private double netSentimentScore;
        private int platformsCovered;
    }

    /**
     * Competitive standing derived from this service's competitor snapshot (the entity plus the
     * competitors configured on it), ranked by net sentiment score.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CompetitivePositioning {
        /** This entity plus every tracked competitor, each with its own reach/sentiment metrics. */
        private List<CompetitorSnapshot> snapshot;
        /** Number of titles compared (this entity + competitors). */
        private int totalTracked;
        /** This entity's 1-based rank by net sentiment among everything tracked. */
        private int rank;
        /** True when this entity has the best net sentiment of everything tracked. */
        private boolean leadsCategory;
        /** Name of the current category leader by net sentiment. */
        private String leaderName;
    }
}
