package com.aura.service.service;

import com.aura.service.dto.SpreaderPostContent;
import com.aura.service.dto.TopSpreaderContent;
import com.aura.service.dto.TopSpreaderContentResponse;
import com.aura.service.dto.TopSpreaderInsightsResponse;
import com.aura.service.entity.EntityLanguageSpreaderSnapshot;
import com.aura.service.entity.ManagedEntity;
import com.aura.service.entity.Mention;
import com.aura.service.entity.TopSpreaderInsightsCache;
import com.aura.service.enums.Platform;
import com.aura.service.enums.RecommendedActionCategory;
import com.aura.service.enums.Sentiment;
import com.aura.service.repository.EntityLanguageSpreaderSnapshotRepository;
import com.aura.service.repository.ManagedEntityRepository;
import com.aura.service.repository.MentionRepository;
import com.aura.service.repository.TopSpreaderInsightsCacheRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers {@link TopSpreaderInsightsService}: the Phase 1, server-computed impact-tier bucketing
 * ({@link TopSpreaderInsightsService#buildCandidates}, ranking spreaders by total views into
 * {@link RecommendedActionCategory#HIGH_IMPACT}/{@code MEDIUM_IMPACT}/{@code LOW_IMPACT} thirds, never
 * the LLM), and the 24h persisted-cache orchestration layered on top of it - no cache row generates
 * synchronously, a fresh row is served without touching the LLM, a stale row is served immediately while
 * a background regeneration is kicked off, and a failed background regeneration never clobbers the
 * previous (still-usable) cached row. {@link #service} wires a real {@link TopSpreaderContentService}
 * (backed by mocked repositories, same technique as {@code TopSpreaderContentServiceTest}) rather than
 * mocking it directly, since Mockito can't mock a concrete class on this project's Java version - see
 * mockito-no-concrete-class-mocks project note. {@code self} is wired to the instance itself so
 * {@code @Async}'s self-invocation indirection runs synchronously and deterministically under test, the
 * same convention {@code RecommendedActionsServiceTest} uses for its own {@code self} field.
 */
class TopSpreaderInsightsServiceTest {

    private static final Long ENTITY_ID = 42L;
    private static final String LANGUAGE = "Tamil";
    private static final int SPREADER_LIMIT = 10;
    private static final int POSTS_PER_SPREADER = 5;
    private static final String PROMPT_TEMPLATE = "[Spreader Insights Data]";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private EntityLanguageSpreaderSnapshotRepository snapshotRepository;
    private MentionRepository mentionRepository;
    private ManagedEntityRepository entityRepository;
    private TopSpreaderInsightsCacheRepository cacheRepository;
    private LLMService llmService;
    private Clock clock;
    private TopSpreaderInsightsService service;

    @BeforeEach
    void setUp() {
        snapshotRepository = mock(EntityLanguageSpreaderSnapshotRepository.class);
        mentionRepository = mock(MentionRepository.class);
        entityRepository = mock(ManagedEntityRepository.class);
        cacheRepository = mock(TopSpreaderInsightsCacheRepository.class);
        llmService = mock(LLMService.class);
        clock = Clock.fixed(Instant.parse("2026-08-10T10:00:00Z"), ZoneOffset.UTC);

        TopSpreaderContentService topSpreaderContentService =
                new TopSpreaderContentService(snapshotRepository, mentionRepository, MAPPER);

        service = new TopSpreaderInsightsService(
                topSpreaderContentService, entityRepository, cacheRepository, llmService, MAPPER, clock);
        ReflectionTestUtils.setField(service, "llmPrompt", PROMPT_TEMPLATE);
        ReflectionTestUtils.setField(service, "self", service);
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

    // ==================== 24h persisted cache orchestration ====================

    private static ManagedEntity entity() {
        ManagedEntity e = new ManagedEntity();
        e.setId(ENTITY_ID);
        e.setName("Test Movie");
        return e;
    }

    // One real spreader with real post content, resolvable through a real TopSpreaderContentService
    // (backed by mocked repositories) so getInsights's live-generation path has something non-empty to
    // build candidates from and send to the LLM.
    private void stubOneSpreaderWithContent() {
        String snapshotJson = "[{\"globalUserId\":\"spreader-1\",\"primaryPlatform\":null," +
                "\"influenceTier\":null,\"totalViews\":500,\"profileUrl\":\"u/spreader-1\"}]";
        EntityLanguageSpreaderSnapshot snapshot = new EntityLanguageSpreaderSnapshot();
        snapshot.setId(1L);
        snapshot.setEntityId(ENTITY_ID);
        snapshot.setLanguage(LANGUAGE);
        snapshot.setSpreadersJson(snapshotJson);
        snapshot.setGeneratedAt(Instant.now());
        when(snapshotRepository.findByEntityIdAndLanguageIgnoreCase(ENTITY_ID, LANGUAGE))
                .thenReturn(Optional.of(snapshot));

        Mention mention = new Mention();
        mention.setId(1L);
        mention.setPlatform(Platform.X);
        mention.setPostId("x-1");
        mention.setAuthor("spreader-1");
        mention.setContent("This BGM is fire!");
        mention.setPostDate(Instant.now());
        mention.setSentiment(Sentiment.POSITIVE);
        mention.setSentimentScore((short) 80);
        mention.setPermalink("https://x.com/spreader-1/status/x-1");
        when(mentionRepository.findByManagedEntityIdAndAuthorIn(eq(ENTITY_ID), anyCollection()))
                .thenReturn(List.of(mention));
        when(mentionRepository.findXPostViewsCounts(anyCollection())).thenReturn(List.of());
        when(mentionRepository.findXPostEngagement(anyCollection())).thenReturn(List.of());
    }

    private TopSpreaderInsightsCache cacheRow(String summary, List<String> actionsJsonSpreaderIds, Instant generatedAt) {
        String actionsJson = "[" + actionsJsonSpreaderIds.stream()
                .map(id -> "{\"spreaderId\":\"" + id + "\",\"action\":\"do something\",\"impact\":\"HIGH_IMPACT\"}")
                .reduce((a, b) -> a + "," + b).orElse("") + "]";
        return new TopSpreaderInsightsCache(
                1L, ENTITY_ID, LANGUAGE.toLowerCase(), SPREADER_LIMIT, POSTS_PER_SPREADER, summary, actionsJson,
                generatedAt);
    }

    @Test
    void noCacheRow_generatesSynchronouslyAndPersists() {
        when(cacheRepository.findByEntityIdAndLanguageAndSpreaderLimitAndPostsPerSpreader(
                ENTITY_ID, LANGUAGE.toLowerCase(), SPREADER_LIMIT, POSTS_PER_SPREADER)).thenReturn(Optional.empty());
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity()));
        stubOneSpreaderWithContent();
        when(llmService.generateReply(any())).thenReturn(
                "{\"summary\": \"Spreader 1 is leading buzz.\", " +
                        "\"actions\": [{\"spreaderId\": \"spreader-1\", \"action\": \"Collaborate on a BGM breakdown.\"}]}");

        TopSpreaderInsightsResponse response = service.getInsights(
                ENTITY_ID, LANGUAGE, SPREADER_LIMIT, POSTS_PER_SPREADER, false);

        assertThat(response.summary()).isEqualTo("Spreader 1 is leading buzz.");
        assertThat(response.actions()).hasSize(1);
        assertThat(response.actions().get(0).spreaderId()).isEqualTo("spreader-1");
        assertThat(response.actions().get(0).impact()).isEqualTo(RecommendedActionCategory.HIGH_IMPACT);

        ArgumentCaptor<TopSpreaderInsightsCache> captor = ArgumentCaptor.forClass(TopSpreaderInsightsCache.class);
        verify(cacheRepository).save(captor.capture());
        assertThat(captor.getValue().getEntityId()).isEqualTo(ENTITY_ID);
        assertThat(captor.getValue().getLanguage()).isEqualTo(LANGUAGE.toLowerCase());
        assertThat(captor.getValue().getSummary()).isEqualTo("Spreader 1 is leading buzz.");
    }

    @Test
    void freshCacheRow_isReturnedWithoutCallingLlmOrRegenerating() {
        TopSpreaderInsightsCache fresh = cacheRow(
                "Cached summary.", List.of("spreader-1"), clock.instant().minusSeconds(3600)); // 1h old
        when(cacheRepository.findByEntityIdAndLanguageAndSpreaderLimitAndPostsPerSpreader(
                ENTITY_ID, LANGUAGE.toLowerCase(), SPREADER_LIMIT, POSTS_PER_SPREADER))
                .thenReturn(Optional.of(fresh));

        TopSpreaderInsightsResponse response = service.getInsights(
                ENTITY_ID, LANGUAGE, SPREADER_LIMIT, POSTS_PER_SPREADER, false);

        assertThat(response.summary()).isEqualTo("Cached summary.");
        assertThat(response.actions()).extracting(a -> a.spreaderId()).containsExactly("spreader-1");
        verify(llmService, never()).generateReply(any());
        verify(cacheRepository, never()).save(any());
        verify(entityRepository, never()).findById(any());
    }

    @Test
    void staleCacheRow_returnsStaleDataImmediately_andRegeneratesInBackground() {
        TopSpreaderInsightsCache stale = cacheRow(
                "Old summary.", List.of("spreader-1"), clock.instant().minus(java.time.Duration.ofHours(25)));
        when(cacheRepository.findByEntityIdAndLanguageAndSpreaderLimitAndPostsPerSpreader(
                ENTITY_ID, LANGUAGE.toLowerCase(), SPREADER_LIMIT, POSTS_PER_SPREADER))
                .thenReturn(Optional.of(stale));
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity()));
        stubOneSpreaderWithContent();
        when(llmService.generateReply(any())).thenReturn(
                "{\"summary\": \"Fresh summary.\", " +
                        "\"actions\": [{\"spreaderId\": \"spreader-1\", \"action\": \"New action.\"}]}");

        TopSpreaderInsightsResponse response = service.getInsights(
                ENTITY_ID, LANGUAGE, SPREADER_LIMIT, POSTS_PER_SPREADER, false);

        // The caller must get the stale-but-immediately-available data, not be blocked on the LLM call
        // this request itself triggered in the background.
        assertThat(response.summary()).isEqualTo("Old summary.");
        // The background regeneration (synchronous here - no real thread pool in a plain unit test, but
        // still routed through the same self.refreshInBackground() indirection @Async relies on) must
        // still have run and persisted fresh data for the next request.
        verify(llmService).generateReply(any());
        verify(cacheRepository).save(any());
    }

    @Test
    void staleCacheRow_backgroundRegenerationFailure_leavesCallerResponseUnaffectedAndCacheUntouched() {
        TopSpreaderInsightsCache stale = cacheRow(
                "Old summary.", List.of("spreader-1"), clock.instant().minus(java.time.Duration.ofHours(25)));
        when(cacheRepository.findByEntityIdAndLanguageAndSpreaderLimitAndPostsPerSpreader(
                ENTITY_ID, LANGUAGE.toLowerCase(), SPREADER_LIMIT, POSTS_PER_SPREADER))
                .thenReturn(Optional.of(stale));
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity()));
        stubOneSpreaderWithContent();
        when(llmService.generateReply(any())).thenThrow(new RuntimeException("LLM unavailable"));

        TopSpreaderInsightsResponse response = service.getInsights(
                ENTITY_ID, LANGUAGE, SPREADER_LIMIT, POSTS_PER_SPREADER, false);

        assertThat(response.summary()).isEqualTo("Old summary.");
        verify(cacheRepository, never()).save(any());
    }

    @Test
    void refreshTrue_bypassesFreshCacheAndRegeneratesSynchronously() {
        TopSpreaderInsightsCache fresh = cacheRow(
                "Cached summary.", List.of("spreader-1"), clock.instant().minusSeconds(60));
        when(cacheRepository.findByEntityIdAndLanguageAndSpreaderLimitAndPostsPerSpreader(
                ENTITY_ID, LANGUAGE.toLowerCase(), SPREADER_LIMIT, POSTS_PER_SPREADER))
                .thenReturn(Optional.of(fresh));
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity()));
        stubOneSpreaderWithContent();
        when(llmService.generateReply(any())).thenReturn(
                "{\"summary\": \"Forced fresh summary.\", " +
                        "\"actions\": [{\"spreaderId\": \"spreader-1\", \"action\": \"Forced action.\"}]}");

        TopSpreaderInsightsResponse response = service.getInsights(
                ENTITY_ID, LANGUAGE, SPREADER_LIMIT, POSTS_PER_SPREADER, true);

        assertThat(response.summary()).isEqualTo("Forced fresh summary.");
        verify(llmService).generateReply(any());
        verify(cacheRepository).save(any());
    }

    // Regression coverage for the dedupe-key cleanup: triggerBackgroundRefresh's inFlightRefreshes guard
    // must release the key even when the background regeneration throws (see refreshInBackground's
    // finally block), or every future request against this cache key would see a "refresh already in
    // flight" false positive and stay stuck on stale data forever.
    @Test
    void afterBackgroundRegenerationFails_subsequentRequestStillRetriesAndCanSucceed() {
        TopSpreaderInsightsCache stale = cacheRow(
                "Old summary.", List.of("spreader-1"), clock.instant().minus(java.time.Duration.ofHours(25)));
        when(cacheRepository.findByEntityIdAndLanguageAndSpreaderLimitAndPostsPerSpreader(
                ENTITY_ID, LANGUAGE.toLowerCase(), SPREADER_LIMIT, POSTS_PER_SPREADER))
                .thenReturn(Optional.of(stale));
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity()));
        stubOneSpreaderWithContent();
        // Throws on the first call (inside the background regeneration), then succeeds on the retry -
        // chained on one stub since re-stubbing a throwing mock via a second when(mock.method()) call
        // would itself throw while Mockito re-invokes the method to register the new stub.
        when(llmService.generateReply(any()))
                .thenThrow(new RuntimeException("LLM unavailable"))
                .thenReturn("{\"summary\": \"Fresh summary.\", " +
                        "\"actions\": [{\"spreaderId\": \"spreader-1\", \"action\": \"New action.\"}]}");

        TopSpreaderInsightsResponse first = service.getInsights(
                ENTITY_ID, LANGUAGE, SPREADER_LIMIT, POSTS_PER_SPREADER, false);
        assertThat(first.summary()).isEqualTo("Old summary.");
        verify(cacheRepository, never()).save(any());

        // The failed attempt never persisted, so the mocked cache lookup still reports the same stale
        // row on this second call - if the dedupe key had been left behind, this call would wrongly
        // find a refresh "already in flight" and skip regenerating entirely.
        TopSpreaderInsightsResponse second = service.getInsights(
                ENTITY_ID, LANGUAGE, SPREADER_LIMIT, POSTS_PER_SPREADER, false);

        assertThat(second.summary()).isEqualTo("Old summary."); // still stale-immediately for the caller
        verify(llmService, times(2)).generateReply(any());
        verify(cacheRepository).save(any()); // the retry succeeded and persisted
    }
}
