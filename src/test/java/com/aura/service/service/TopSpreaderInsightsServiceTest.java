package com.aura.service.service;

import com.aura.service.dto.SpreaderPostContent;
import com.aura.service.dto.TopSpreaderContent;
import com.aura.service.dto.TopSpreaderContentResponse;
import com.aura.service.enums.Platform;
import com.aura.service.enums.RecommendedActionCategory;
import com.aura.service.enums.Sentiment;
import com.aura.service.repository.ManagedEntityRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;

/**
 * Covers {@link TopSpreaderInsightsService#buildCandidates} - the Phase 1, server-computed impact-tier
 * bucketing that ranks spreaders by total views and splits them into thirds
 * ({@link RecommendedActionCategory#HIGH_IMPACT}/{@code MEDIUM_IMPACT}/{@code LOW_IMPACT}), never the
 * LLM. Exercised directly against the package-private method (no mock of the concrete
 * {@link TopSpreaderContentService} needed - see that field's constructor arg below, which is never
 * touched by buildCandidates) per this project's Java 25 / Mockito concrete-class-mock constraint.
 */
class TopSpreaderInsightsServiceTest {

    private TopSpreaderInsightsService service;

    @BeforeEach
    void setUp() {
        service = new TopSpreaderInsightsService(
                null, // TopSpreaderContentService: concrete class, unused by buildCandidates
                mock(ManagedEntityRepository.class),
                mock(LLMService.class),
                new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-08-10T10:00:00Z"), ZoneOffset.UTC));
    }

    private static SpreaderPostContent post(String content, Double engagementRate, Sentiment sentiment) {
        return new SpreaderPostContent(
                1L, Platform.X, "post-1", content, "https://example.com/1", Instant.now(),
                100L, 5, 2, engagementRate, sentiment, (short) 50);
    }

    private static TopSpreaderContent spreader(String id, long totalViews) {
        return new TopSpreaderContent(id, "https://example.com/" + id, totalViews,
                List.of(post("some post content", 0.1, Sentiment.POSITIVE)));
    }

    private static TopSpreaderContent spreaderWithNoContent(String id, long totalViews) {
        return new TopSpreaderContent(id, "https://example.com/" + id, totalViews, List.of());
    }

    // ==================== Thirds bucketing at various group sizes ====================

    @Test
    void singleSpreader_isHighImpact() {
        var response = new TopSpreaderContentResponse(1L, "Tamil", List.of(spreader("solo", 1000)));

        var candidates = service.buildCandidates(response);

        assertThat(candidates).extracting(TopSpreaderInsightsService.SpreaderCandidate::spreaderId,
                        TopSpreaderInsightsService.SpreaderCandidate::impact)
                .containsExactly(tuple("solo", RecommendedActionCategory.HIGH_IMPACT));
    }

    @Test
    void twoSpreaders_splitHighThenMedium() {
        var response = new TopSpreaderContentResponse(1L, "Tamil", List.of(
                spreader("low", 100), spreader("high", 9000)));

        var candidates = service.buildCandidates(response);

        assertThat(candidates).extracting(TopSpreaderInsightsService.SpreaderCandidate::spreaderId,
                        TopSpreaderInsightsService.SpreaderCandidate::impact)
                .containsExactly(
                        tuple("high", RecommendedActionCategory.HIGH_IMPACT),
                        tuple("low", RecommendedActionCategory.MEDIUM_IMPACT));
    }

    @Test
    void threeSpreaders_oneEachTier() {
        var response = new TopSpreaderContentResponse(1L, "Tamil", List.of(
                spreader("mid", 500), spreader("top", 9000), spreader("bottom", 10)));

        var candidates = service.buildCandidates(response);

        assertThat(candidates).extracting(TopSpreaderInsightsService.SpreaderCandidate::spreaderId,
                        TopSpreaderInsightsService.SpreaderCandidate::impact)
                .containsExactly(
                        tuple("top", RecommendedActionCategory.HIGH_IMPACT),
                        tuple("mid", RecommendedActionCategory.MEDIUM_IMPACT),
                        tuple("bottom", RecommendedActionCategory.LOW_IMPACT));
    }

    @Test
    void fourSpreaders_twoHighOneMediumOneLow() {
        var response = new TopSpreaderContentResponse(1L, "Tamil", List.of(
                spreader("s1", 1000), spreader("s2", 900), spreader("s3", 500), spreader("s4", 10)));

        var candidates = service.buildCandidates(response);

        assertThat(candidates).extracting(TopSpreaderInsightsService.SpreaderCandidate::impact)
                .containsExactly(
                        RecommendedActionCategory.HIGH_IMPACT, RecommendedActionCategory.HIGH_IMPACT,
                        RecommendedActionCategory.MEDIUM_IMPACT, RecommendedActionCategory.LOW_IMPACT);
    }

    @Test
    void sixSpreaders_evenThirds() {
        var response = new TopSpreaderContentResponse(1L, "Tamil", List.of(
                spreader("s1", 6000), spreader("s2", 5000), spreader("s3", 4000),
                spreader("s4", 3000), spreader("s5", 2000), spreader("s6", 1000)));

        var candidates = service.buildCandidates(response);

        assertThat(candidates).extracting(TopSpreaderInsightsService.SpreaderCandidate::impact)
                .containsExactly(
                        RecommendedActionCategory.HIGH_IMPACT, RecommendedActionCategory.HIGH_IMPACT,
                        RecommendedActionCategory.MEDIUM_IMPACT, RecommendedActionCategory.MEDIUM_IMPACT,
                        RecommendedActionCategory.LOW_IMPACT, RecommendedActionCategory.LOW_IMPACT);
    }

    // ==================== Ranking + filtering ====================

    @Test
    void ranksByTotalViewsDescendingRegardlessOfInputOrder() {
        var response = new TopSpreaderContentResponse(1L, "Tamil", List.of(
                spreader("low", 200), spreader("high", 8000), spreader("mid", 2000)));

        var candidates = service.buildCandidates(response);

        assertThat(candidates).extracting(TopSpreaderInsightsService.SpreaderCandidate::spreaderId)
                .containsExactly("high", "mid", "low");
    }

    @Test
    void excludesSpreadersWithNoResolvedPostContent_evenWhenHighViews() {
        var response = new TopSpreaderContentResponse(1L, "Tamil", List.of(
                spreaderWithNoContent("no-content-but-huge-views", 999_999),
                spreader("has-content", 10)));

        var candidates = service.buildCandidates(response);

        assertThat(candidates).extracting(TopSpreaderInsightsService.SpreaderCandidate::spreaderId)
                .containsExactly("has-content");
        assertThat(candidates.get(0).impact()).isEqualTo(RecommendedActionCategory.HIGH_IMPACT);
    }

    @Test
    void returnsEmptyListWhenNoSpreaderHasResolvedContent() {
        var response = new TopSpreaderContentResponse(1L, "Tamil", List.of(
                spreaderWithNoContent("a", 100), spreaderWithNoContent("b", 200)));

        assertThat(service.buildCandidates(response)).isEmpty();
    }

    @Test
    void returnsEmptyListWhenNoSpreadersAtAll() {
        var response = new TopSpreaderContentResponse(1L, "Tamil", List.of());

        assertThat(service.buildCandidates(response)).isEmpty();
    }
}
