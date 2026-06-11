package com.aura.service.service;

import com.aura.service.dto.CheckpointImpact;
import com.aura.service.dto.CheckpointImpactResponse;
import com.aura.service.dto.CompetitorSnapshot;
import com.aura.service.dto.EntityDetailResponse;
import com.aura.service.dto.EntityMarketingReportResponse;
import com.aura.service.dto.EntityMarketingReportResponse.CompetitivePositioning;
import com.aura.service.dto.EntityMarketingReportResponse.HeadlineMetrics;
import com.aura.service.dto.EntitySentimentData;
import com.aura.service.dto.SentimentOverTimeResponse;
import com.aura.service.dto.TimeSeriesData;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the real OpenPDF rendering path: a fully-populated report and a degraded report must both
 * produce a well-formed PDF document (magic header + EOF trailer), and the download filename must be
 * slugified safely.
 */
class EntityMarketingReportPdfServiceTest {

    private final EntityMarketingReportPdfService service = new EntityMarketingReportPdfService();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void rendersWellFormedPdf_forFullReport() throws Exception {
        byte[] pdf = service.render(fullReport());

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).startsWith("%PDF-");
        // A complete PDF ends with the EOF marker.
        String tail = new String(pdf, Math.max(0, pdf.length - 16), Math.min(16, pdf.length), StandardCharsets.ISO_8859_1);
        assertThat(tail).contains("%%EOF");
    }

    @Test
    void rendersWellFormedPdf_forDegradedReport() {
        EntityDetailResponse entity = new EntityDetailResponse();
        entity.setName("Solo Title");
        entity.setType("MOVIE");

        EntityMarketingReportResponse degraded = EntityMarketingReportResponse.builder()
                .generatedAt(Instant.parse("2026-06-11T00:00:00Z"))
                .period("DAY30")
                .entity(entity)
                .headlineMetrics(HeadlineMetrics.builder().totalMentions(12).positivityRatio(0.5).build())
                .auraMathStatus("unavailable")          // AuraMath section renders an "unavailable" note
                .highlights(List.of())                  // empty highlights → section skipped
                .build();

        byte[] pdf = service.render(degraded);

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).startsWith("%PDF-");
    }

    @Test
    void rendersWellFormedPdf_whenAuraMathPayloadIsFlatUnknownShape() throws Exception {
        EntityDetailResponse entity = new EntityDetailResponse();
        entity.setName("Solo Title");

        EntityMarketingReportResponse report = EntityMarketingReportResponse.builder()
                .generatedAt(Instant.parse("2026-06-11T00:00:00Z"))
                .entity(entity)
                .auraMathStatus("ok")
                // Unknown fields (incl. nested ones) fall back to the humanized key/value table.
                .auraMathIntelligence(mapper.readTree(
                        "{\"score\":91,\"verdict\":\"blockbuster\",\"extra\":{\"nested\":[1,2]}}"))
                .build();

        byte[] pdf = service.render(report);

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).startsWith("%PDF-");
    }

    @Test
    void rendersWellFormedPdf_whenAuraMathPayloadIsDegradedMessage() throws Exception {
        EntityDetailResponse entity = new EntityDetailResponse();
        entity.setName("Solo Title");

        EntityMarketingReportResponse report = EntityMarketingReportResponse.builder()
                .generatedAt(Instant.parse("2026-06-11T00:00:00Z"))
                .entity(entity)
                .auraMathStatus("ok")
                .auraMathIntelligence(mapper.readTree(
                        "{\"entityId\":\"7\",\"message\":\"No scored post history found for this entity\"}"))
                .build();

        byte[] pdf = service.render(report);

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).startsWith("%PDF-");
    }

    @Test
    void fileNameIsSlugified() {
        EntityDetailResponse entity = new EntityDetailResponse();
        entity.setName("The Quantum Paradox!");
        EntityMarketingReportResponse report = EntityMarketingReportResponse.builder().entity(entity).build();

        assertThat(service.fileName(report)).isEqualTo("marketing-report-the-quantum-paradox.pdf");
    }

    @Test
    void fileNameFallsBackWhenNameMissing() {
        EntityMarketingReportResponse report = EntityMarketingReportResponse.builder().build();
        assertThat(service.fileName(report)).isEqualTo("marketing-report-entity.pdf");
    }

    /**
     * A realistic upstream AuraMath entity-report payload covering every structured subsection the
     * PDF renderer understands, plus one unknown field to exercise the forward-compat fallback.
     */
    private static final String AURAMATH_ENTITY_REPORT = """
            {
              "generatedAt": "2026-06-11T08:25:00 IST",
              "entityProfile": {
                "entityId": "1",
                "name": "The Quantum Paradox",
                "type": "movie",
                "trackedKeywords": ["quantum paradox", "nolan"],
                "activePlatforms": ["x", "youtube"],
                "totalPosts": 8000,
                "audienceSize": 642,
                "firstSeen": "2026-05-01T10:00:00 IST",
                "lastSeen": "2026-06-10T22:15:00 IST",
                "observationSpanDays": 40.5,
                "averagePostsPerDay": 197.5,
                "viralityTier": "Trending",
                "viralityTierExplained": "Branching ratio 0.71 — a reliably amplifying topic."
              },
              "conversationProfile": {
                "branchingRatio": 0.71,
                "amplificationExplained": "Each post triggers ~0.71 organic follow-up posts on average.",
                "distinctBurstEvents": 6,
                "peakActivityWindows": ["20:00–21:00 IST (412 posts)", "13:00–14:00 IST (300 posts)"],
                "mostActiveDayOfWeek": "FRIDAY (1820 posts)",
                "longestBurst": {
                  "keyword": "quantum paradox",
                  "startTime": "2026-05-20T20:10:00 IST",
                  "durationMinutes": 42.0,
                  "postCount": 310,
                  "peakExcitationSpike": 0.84,
                  "readableDescription": "310 posts about 'quantum paradox' in 42.0 minutes — peak excitation 0.84"
                }
              },
              "topicIntelligence": [
                {
                  "keyword": "quantum paradox",
                  "totalMentions": 5200,
                  "burstsTriggered": 4,
                  "dominantTone": "positive",
                  "excitationProfile": "HIGH — keyword consistently triggers burst activity (4 burst(s))."
                },
                {
                  "keyword": "nolan",
                  "totalMentions": 2800,
                  "burstsTriggered": 2,
                  "dominantTone": "neutral",
                  "excitationProfile": "MEDIUM — keyword raises activity but rarely produces full bursts."
                }
              ],
              "audienceSentiment": {
                "toneBreakdown": {"positive": 5600, "neutral": 1280, "negative": 1120},
                "dominantTone": "positive",
                "netSentiment": 0.56,
                "sentimentLabel": "Predominantly Positive"
              },
              "channelStrategy": {
                "topChannel": "X (Twitter)",
                "headline": "Conversation is led by X (Twitter) — focus campaign spend there first.",
                "channels": [
                  {"platform": "X (Twitter)", "postCount": 6100, "share": 0.763},
                  {"platform": "YouTube", "postCount": 1900, "share": 0.237}
                ]
              },
              "topAdvocates": [
                {
                  "global_user_id": "u-101",
                  "tribe_label": "Film critics",
                  "post_count": 84,
                  "total_engagement": 51200,
                  "hawkes_alpha": 1.42,
                  "platform_handles": {
                    "primary_platform": "x",
                    "by_platform": {"x": {"profile_url": "https://twitter.com/cinemaSage"}}
                  }
                },
                {
                  "global_user_id": "u-102",
                  "tribe_label": "Fan community",
                  "post_count": 61,
                  "total_engagement": 18400,
                  "hawkes_alpha": 0.97
                }
              ],
              "marketingRecommendations": {
                "primaryChannel": "X (Twitter)",
                "bestTimeToEngage": "20:00–21:00 IST — peak conversation window",
                "campaignType": "Reactive Amplification Campaign — time a seeding post to burst windows.",
                "amplificationPotential": "MEDIUM — reliable amplifier",
                "estimatedReachMultiplier": "~0.7x per seeded post (branching ratio 0.71)",
                "addressableAudience": "642 distinct authors are already talking about this entity",
                "contentTriggers": ["quantum paradox"],
                "contentStrategy": "Audiences champion 'quantum paradox' enthusiastically.",
                "actionableAdvice": "1. TIMING: Publish during the 20:00–21:00 IST window. 2. PLATFORM: Start on X."
              },
              "redFlags": [
                {"flag": "Thin Data Window", "severity": "LOW", "detail": "Only 5 day(s) of history."},
                {"flag": "Negative Spike Risk", "severity": "HIGH", "detail": "Watch the review cycle."}
              ],
              "opportunityFlags": [
                {"opportunity": "High-Velocity Topic", "detail": "Near a critical spreading threshold."}
              ],
              "futureSection": {"newMetric": 42, "items": ["a", "b"]}
            }
            """;

    private EntityMarketingReportResponse fullReport() throws Exception {
        EntityDetailResponse entity = new EntityDetailResponse();
        entity.setId(1L);
        entity.setName("The Quantum Paradox");
        entity.setType("MOVIE");
        entity.setDirector("Christopher Nolan");
        entity.setActors(List.of("Leonardo DiCaprio", "Emma Stone"));
        entity.setReleaseDate(LocalDate.of(2026, 7, 1));

        HeadlineMetrics metrics = HeadlineMetrics.builder()
                .totalMentions(8000).overallSentiment(0.62).positivityRatio(0.70)
                .positiveSentiment(0.70).negativeSentiment(0.14).neutralSentiment(0.16)
                .netSentimentScore(5.0).platformsCovered(2).build();

        CompetitivePositioning positioning = CompetitivePositioning.builder()
                .snapshot(List.of(
                        new CompetitorSnapshot("The Quantum Paradox", 8000L, 0.62, 0.70, 5.0),
                        new CompetitorSnapshot("Inception 2", 5000L, 0.40, 0.50, 2.0)))
                .totalTracked(2).rank(1).leadsCategory(true).leaderName("The Quantum Paradox").build();

        SentimentOverTimeResponse trend = new SentimentOverTimeResponse(List.of(
                new EntitySentimentData("The Quantum Paradox",
                        List.of(new TimeSeriesData("2026-05-13", 220, 40, 30, 290)),
                        List.of())));

        CheckpointImpactResponse impact = CheckpointImpactResponse.builder()
                .entityId(1L).entityName("The Quantum Paradox").windowDays(7)
                .impacts(List.of(CheckpointImpact.builder()
                        .checkpointId(10L).checkpointDate(LocalDate.of(2026, 5, 20))
                        .description("Trailer Launch").positiveRatioChange(0.12)
                        .netSentimentChange(1.95).impactDirection("IMPROVED").build()))
                .build();

        return EntityMarketingReportResponse.builder()
                .generatedAt(Instant.parse("2026-06-11T08:30:00Z"))
                .period("DAY30")
                .entity(entity)
                .headlineMetrics(metrics)
                .competitivePositioning(positioning)
                .sentimentTrend(trend)
                .platformReach(Map.of(
                        "YOUTUBE", Map.of("POSITIVE", 212L, "NEGATIVE", 53L, "NEUTRAL", 13L),
                        "INSTAGRAM", Map.of("POSITIVE", 37L, "NEGATIVE", 1L, "NEUTRAL", 3L)))
                .definingMoments(impact)
                .auraMathIntelligence(mapper.readTree(AURAMATH_ENTITY_REPORT))
                .auraMathStatus("ok")
                .highlights(List.of(
                        "8.0K mentions analysed across 2 platforms of audience conversation",
                        "70% of all mentions are positive",
                        "Leads its category — #1 of 2 tracked titles on net sentiment"))
                .build();
    }
}
