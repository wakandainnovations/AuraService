package com.aura.service.service;

import com.aura.service.dto.RecommendedActionCandidate;
import com.aura.service.dto.RecommendedActionItem;
import com.aura.service.dto.RecommendedActionsResponse;
import com.aura.service.entity.ManagedEntity;
import com.aura.service.entity.RecommendedActionsCache;
import com.aura.service.enums.RecommendedActionCategory;
import com.aura.service.repository.ManagedEntityRepository;
import com.aura.service.repository.RecommendedActionsCacheRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers {@link RecommendedActionsService}: the Phase 1 candidate list is treated as a black box
 * ({@link RecommendedActionCandidateService} is mocked, never re-implemented here); this test only
 * checks the Phase 2 concerns layered on top - cache hit/miss plumbing, the LLM select-and-phrase
 * merge (including dropping an unrecognized candidateId), the fallback path when the LLM call fails,
 * and the day-offset window filtering (including its boundary days). Collaborators are mocked as
 * interfaces per this project's Java 25 / Mockito constraint (see
 * RecommendedActionCandidateServiceImplTest for the same convention).
 */
class RecommendedActionsServiceTest {

    private static final Long ENTITY_ID = 42L;
    private static final String PROMPT_TEMPLATE = "[Candidate Actions Data]";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ManagedEntityRepository entityRepository;
    private RecommendedActionsCacheRepository cacheRepository;
    private RecommendedActionCandidateService candidateService;
    private LLMService llmService;
    private TaskScheduler taskScheduler;
    private Clock clock;
    private RecommendedActionsService service;

    @BeforeEach
    void setUp() {
        entityRepository = mock(ManagedEntityRepository.class);
        cacheRepository = mock(RecommendedActionsCacheRepository.class);
        candidateService = mock(RecommendedActionCandidateService.class);
        llmService = mock(LLMService.class);
        taskScheduler = mock(TaskScheduler.class);
        clock = Clock.fixed(Instant.parse("2026-08-10T10:00:00Z"), ZoneOffset.UTC);

        service = new RecommendedActionsService(
                entityRepository, cacheRepository, candidateService, llmService, clock, taskScheduler);
        ReflectionTestUtils.setField(service, "llmPrompt", PROMPT_TEMPLATE);
        // No Spring context in this test, so self-invocation through the proxy (see the `self` field's
        // doc comment on the service) isn't exercised here - wiring it to the instance itself keeps
        // refreshOneEntity's business logic reachable and testable without asserting on @Transactional
        // itself, which is framework behavior, not this class's logic.
        ReflectionTestUtils.setField(service, "self", service);
    }

    private static ManagedEntity entity(LocalDate releaseDate) {
        ManagedEntity e = new ManagedEntity();
        e.setId(ENTITY_ID);
        e.setName("Test Movie");
        e.setReleaseDate(releaseDate);
        return e;
    }

    private static ManagedEntity entityWithIdAndName(Long id, String name) {
        ManagedEntity e = new ManagedEntity();
        e.setId(id);
        e.setName(name);
        return e;
    }

    private static RecommendedActionCandidate candidate(
            String id, String factor, int confidence, int start, int end, String label, String... facts) {
        return new RecommendedActionCandidate(
                id, factor, RecommendedActionCategory.HIGH_IMPACT, confidence, start, end, label, List.of(facts),
                List.of());
    }

    private static RecommendedActionCandidate candidateWithHandles(
            String id, String factor, int confidence, int start, int end, String label,
            List<String> exampleHandles, String... facts) {
        return new RecommendedActionCandidate(
                id, factor, RecommendedActionCategory.HIGH_IMPACT, confidence, start, end, label, List.of(facts),
                exampleHandles);
    }

    // ==================== Cache hit ====================

    @Test
    void cacheHit_returnsWithoutCallingLlmOrCandidateService() throws Exception {
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity(null)));
        List<RecommendedActionItem> cachedActions = List.of(new RecommendedActionItem(
                RecommendedActionCategory.HIGH_IMPACT, "Cached Title", "Cached reason", 85, "Factor A", -10, 10,
                "label", List.of()));
        RecommendedActionsCache row = new RecommendedActionsCache(
                1L, ENTITY_ID, "Test Movie", MAPPER.writeValueAsString(cachedActions), 0,
                Instant.parse("2026-08-01T00:00:00Z"));
        when(cacheRepository.findByEntityId(ENTITY_ID)).thenReturn(Optional.of(row));

        RecommendedActionsResponse response = service.getRecommendedActions(ENTITY_ID, false, true);

        assertThat(response.getActions()).hasSize(1);
        assertThat(response.getActions().get(0).getTitle()).isEqualTo("Cached Title");
        verify(candidateService, never()).buildCandidateActions(any());
        verify(llmService, never()).generateReply(any());
    }

    // ==================== Cache miss: generate, merge, persist ====================

    @Test
    void cacheMiss_callsCandidateServiceThenLlm_mergesAndPersists() throws Exception {
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity(null)));
        when(cacheRepository.findByEntityId(ENTITY_ID)).thenReturn(Optional.empty());

        RecommendedActionCandidate c1 = candidate(
                "factor-46-teaser", "Teaser/Trailer Timing", 90, -45, -30, "4-6 weeks before release",
                "This platform's timing model calibrates a 30-45 day pre-release window as a +25% bonus.");
        when(candidateService.buildCandidateActions(ENTITY_ID)).thenReturn(List.of(c1));

        when(llmService.generateReply(any())).thenReturn(
                "[{\"candidateId\": \"factor-46-teaser\", \"title\": \"Kick Off Teaser Push\", " +
                        "\"reason\": \"The 30-45 day pre-release window is calibrated as a +25% bonus.\"}]");

        RecommendedActionsResponse response = service.getRecommendedActions(ENTITY_ID, false, true);

        assertThat(response.getActions()).hasSize(1);
        RecommendedActionItem item = response.getActions().get(0);
        assertThat(item.getTitle()).isEqualTo("Kick Off Teaser Push");
        assertThat(item.getReason()).contains("30-45 day");
        assertThat(item.getCategory()).isEqualTo(RecommendedActionCategory.HIGH_IMPACT);
        assertThat(item.getConfidencePct()).isEqualTo(90);
        assertThat(item.getRelatedFactor()).isEqualTo("Teaser/Trailer Timing");
        assertThat(item.getWindowStartDaysFromRelease()).isEqualTo(-45);
        assertThat(item.getWindowEndDaysFromRelease()).isEqualTo(-30);
        assertThat(item.getWindowLabel()).isEqualTo("4-6 weeks before release");

        verify(cacheRepository).save(any(RecommendedActionsCache.class));
    }

    @Test
    void merge_dropsCandidateIdNotInOriginalCandidateList() {
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity(null)));
        when(cacheRepository.findByEntityId(ENTITY_ID)).thenReturn(Optional.empty());

        RecommendedActionCandidate c1 = candidate("known-id", "Factor A", 80, -10, 10, "label", "fact");
        when(candidateService.buildCandidateActions(ENTITY_ID)).thenReturn(List.of(c1));

        when(llmService.generateReply(any())).thenReturn(
                "[{\"candidateId\": \"known-id\", \"reason\": \"Grounded reason.\"}, " +
                        "{\"candidateId\": \"unknown-id-not-in-list\", \"reason\": \"Should be dropped.\"}]");

        RecommendedActionsResponse response = service.getRecommendedActions(ENTITY_ID, false, true);

        assertThat(response.getActions()).hasSize(1);
        assertThat(response.getActions().get(0).getReason()).isEqualTo("Grounded reason.");
    }

    // ==================== Example handles guaranteed in reason text ====================

    // Regression coverage: observed live against a weaker/local LLM, which - despite the prompt
    // instructing it to name real example handles verbatim - still wrote a generic reason ("top
    // positive-sentiment account(s) should be mobilized") without naming any of them. Handles are
    // exactly the concrete, actionable detail this feature exists to surface, so this can't be left to
    // LLM instruction-following alone: the merge step must append them itself when the LLM omits them.
    @Test
    void merge_appendsExampleHandlesWhenLlmReasonOmitsThem() {
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity(null)));
        when(cacheRepository.findByEntityId(ENTITY_ID)).thenReturn(Optional.empty());

        RecommendedActionCandidate c1 = candidateWithHandles(
                "factor-17-evangelist-mobilization", "Core Fanbase Mobilization Value", 80, -21, -7, "label",
                List.of("Nepal Yash Army 🇳🇵", "God of Thunder"),
                "41 positive-sentiment accounts identified across 2 tracked keyword(s).");
        when(candidateService.buildCandidateActions(ENTITY_ID)).thenReturn(List.of(c1));

        when(llmService.generateReply(any())).thenReturn(
                "[{\"candidateId\": \"factor-17-evangelist-mobilization\", \"reason\": \"Positive-sentiment " +
                        "accounts for this movie have been identified and should be mobilized.\"}]");

        RecommendedActionsResponse response = service.getRecommendedActions(ENTITY_ID, false, true);

        assertThat(response.getActions()).hasSize(1);
        RecommendedActionItem item = response.getActions().get(0);
        assertThat(item.getReason())
                .contains("Positive-sentiment accounts for this movie have been identified and should be mobilized.")
                .contains("Nepal Yash Army 🇳🇵")
                .contains("God of Thunder");
        assertThat(item.getExampleHandles()).containsExactly("Nepal Yash Army 🇳🇵", "God of Thunder");
    }

    @Test
    void merge_doesNotDuplicateExampleHandlesWhenLlmAlreadyNamedThem() {
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity(null)));
        when(cacheRepository.findByEntityId(ENTITY_ID)).thenReturn(Optional.empty());

        RecommendedActionCandidate c1 = candidateWithHandles(
                "factor-53-viral-seed-outreach", "Influencer-Driven Promotions", 65, -30, -7, "label",
                List.of("Honest Review", "Nikhil"),
                "2 viral-seed account(s) identified.");
        when(candidateService.buildCandidateActions(ENTITY_ID)).thenReturn(List.of(c1));

        when(llmService.generateReply(any())).thenReturn(
                "[{\"candidateId\": \"factor-53-viral-seed-outreach\", \"reason\": \"Reach out to Honest Review " +
                        "and Nikhil to seed the teaser.\"}]");

        RecommendedActionsResponse response = service.getRecommendedActions(ENTITY_ID, false, true);

        assertThat(response.getActions()).hasSize(1);
        assertThat(response.getActions().get(0).getReason())
                .isEqualTo("Reach out to Honest Review and Nikhil to seed the teaser.");
    }

    // Regression coverage: observed live against a weaker/local LLM, which literally echoed the
    // bracketed example from the prompt ("[Movie X] (a real movie example)") instead of substituting
    // a real movie title. That reason is unusable as-is, so it must fall back to this candidate's own
    // generic (supportingFacts-only) reason rather than surface the placeholder verbatim in the UI.
    @Test
    void merge_fallsBackToGenericReasonWhenLlmEchoesLiteralBracketPlaceholder() {
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity(null)));
        when(cacheRepository.findByEntityId(ENTITY_ID)).thenReturn(Optional.empty());

        RecommendedActionCandidate c1 = candidate(
                "factor-52-low-online-presence", "Micro-Video Social Media Campaigns", 65, -365, -14, "label",
                "Only 3 mention(s) tracked online to date.");
        when(candidateService.buildCandidateActions(ENTITY_ID)).thenReturn(List.of(c1));

        when(llmService.generateReply(any())).thenReturn(
                "[{\"candidateId\": \"factor-52-low-online-presence\", \"reason\": \"Similar to how [Movie X] " +
                        "(a real movie example) built early buzz through short-form video teasers.\"}]");

        RecommendedActionsResponse response = service.getRecommendedActions(ENTITY_ID, false, true);

        assertThat(response.getActions()).hasSize(1);
        RecommendedActionItem item = response.getActions().get(0);
        assertThat(item.getReason()).doesNotContain("[Movie X]").contains("Only 3 mention(s)");
        assertThat(item.getTitle()).isEqualTo("Micro-Video Social Media Campaigns");
    }

    // ==================== LLM failure fallback ====================

    @Test
    void llmFailure_stillReturnsUsableFallbackResponse() {
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity(null)));
        when(cacheRepository.findByEntityId(ENTITY_ID)).thenReturn(Optional.empty());

        RecommendedActionCandidate c1 = candidate(
                "factor-x", "Factor X", 70, -20, -5, "label", "18 comparable releases averaged $1,000,000.");
        when(candidateService.buildCandidateActions(ENTITY_ID)).thenReturn(List.of(c1));

        when(llmService.generateReply(any())).thenThrow(new RuntimeException("LLM unavailable"));

        RecommendedActionsResponse response = service.getRecommendedActions(ENTITY_ID, false, true);

        assertThat(response.getActions()).hasSize(1);
        RecommendedActionItem item = response.getActions().get(0);
        assertThat(item.getTitle()).isEqualTo("Factor X");
        assertThat(item.getReason()).contains("18 comparable releases averaged $1,000,000.");
        assertThat(item.getConfidencePct()).isEqualTo(70);
    }

    // ==================== Day-offset window filtering ====================

    private void stubCachedActions(List<RecommendedActionItem> actions, LocalDate releaseDate) throws Exception {
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity(releaseDate)));
        RecommendedActionsCache row = new RecommendedActionsCache(
                1L, ENTITY_ID, "Test Movie", MAPPER.writeValueAsString(actions), 0,
                Instant.parse("2026-08-01T00:00:00Z"));
        when(cacheRepository.findByEntityId(ENTITY_ID)).thenReturn(Optional.of(row));
    }

    @Test
    void windowFiltering_includesActionAtStartBoundary() throws Exception {
        // clock is fixed at 2026-08-10; releaseDate 10 days later means today's offset is -10.
        LocalDate releaseDate = LocalDate.of(2026, 8, 20);
        RecommendedActionItem inWindow = new RecommendedActionItem(
                RecommendedActionCategory.HIGH_IMPACT, "T", "R", 90, "Factor", -10, -5, "label", List.of());
        stubCachedActions(List.of(inWindow), releaseDate);

        RecommendedActionsResponse response = service.getRecommendedActions(ENTITY_ID, false, false);

        assertThat(response.getDaysToRelease()).isEqualTo(-10);
        assertThat(response.getActions()).hasSize(1);
    }

    @Test
    void windowFiltering_includesActionAtEndBoundary() throws Exception {
        LocalDate releaseDate = LocalDate.of(2026, 8, 20);
        RecommendedActionItem inWindow = new RecommendedActionItem(
                RecommendedActionCategory.HIGH_IMPACT, "T", "R", 90, "Factor", -20, -10, "label", List.of());
        stubCachedActions(List.of(inWindow), releaseDate);

        RecommendedActionsResponse response = service.getRecommendedActions(ENTITY_ID, false, false);

        assertThat(response.getDaysToRelease()).isEqualTo(-10);
        assertThat(response.getActions()).hasSize(1);
    }

    @Test
    void windowFiltering_excludesOneDayBeforeStart_butOtherInWindowActionStillSurvives() throws Exception {
        LocalDate releaseDate = LocalDate.of(2026, 8, 20);
        RecommendedActionItem outOfWindow = new RecommendedActionItem(
                RecommendedActionCategory.HIGH_IMPACT, "T", "R", 90, "Factor", -9, -1, "label", List.of());
        RecommendedActionItem inWindow = new RecommendedActionItem(
                RecommendedActionCategory.HIGH_IMPACT, "T2", "R2", 90, "Factor2", -10, -5, "label2", List.of());
        stubCachedActions(List.of(outOfWindow, inWindow), releaseDate);

        RecommendedActionsResponse response = service.getRecommendedActions(ENTITY_ID, false, false);

        assertThat(response.getDaysToRelease()).isEqualTo(-10);
        assertThat(response.getActions()).extracting(RecommendedActionItem::getTitle).containsExactly("T2");
    }

    @Test
    void windowFiltering_excludesOneDayAfterEnd_butOtherInWindowActionStillSurvives() throws Exception {
        LocalDate releaseDate = LocalDate.of(2026, 8, 20);
        RecommendedActionItem outOfWindow = new RecommendedActionItem(
                RecommendedActionCategory.HIGH_IMPACT, "T", "R", 90, "Factor", -20, -11, "label", List.of());
        RecommendedActionItem inWindow = new RecommendedActionItem(
                RecommendedActionCategory.HIGH_IMPACT, "T2", "R2", 90, "Factor2", -10, -5, "label2", List.of());
        stubCachedActions(List.of(outOfWindow, inWindow), releaseDate);

        RecommendedActionsResponse response = service.getRecommendedActions(ENTITY_ID, false, false);

        assertThat(response.getDaysToRelease()).isEqualTo(-10);
        assertThat(response.getActions()).extracting(RecommendedActionItem::getTitle).containsExactly("T2");
    }

    // A movie whose plan only has actions timed around a marketing calendar the current day doesn't
    // fall inside (e.g. a movie many weeks further from release than any curated factor window
    // reaches) must still return its real, grounded plan rather than an empty panel - the plan
    // existing at all is the signal, not which narrow window happens to contain today. Regression
    // test for the "Lord Gaaga" bug: a real cached plan existed but every action's window fell
    // outside today's actual days-to-release, so the filtered response was empty.
    @Test
    void windowFiltering_allActionsOutOfWindow_fallsBackToFullPlanInsteadOfEmpty() throws Exception {
        LocalDate releaseDate = LocalDate.of(2026, 8, 20);
        RecommendedActionItem outOfWindow1 = new RecommendedActionItem(
                RecommendedActionCategory.HIGH_IMPACT, "T", "R", 90, "Factor", -9, -1, "label", List.of());
        RecommendedActionItem outOfWindow2 = new RecommendedActionItem(
                RecommendedActionCategory.HIGH_IMPACT, "T2", "R2", 90, "Factor2", -20, -11, "label2", List.of());
        stubCachedActions(List.of(outOfWindow1, outOfWindow2), releaseDate);

        RecommendedActionsResponse response = service.getRecommendedActions(ENTITY_ID, false, false);

        assertThat(response.getDaysToRelease()).isEqualTo(-10);
        assertThat(response.getActions()).extracting(RecommendedActionItem::getTitle).containsExactly("T", "T2");
    }

    // ==================== Post-release fallback excludes pre-release-only actions ====================

    // Regression test for the bug where an already-released movie's action panel resurrected
    // pre-release-only beats (e.g. "Releasing Teasers and Trailers at Optimal Timing", "Releasing the
    // First Single at an Optimal Time") once today's offset stopped falling inside any curated window.
    // Once released, the fallback should prefer actions whose window reaches release day or later
    // over ones that are entirely pre-release and thus no longer actionable.
    @Test
    void windowFiltering_postRelease_fallbackExcludesPreReleaseOnlyActions() throws Exception {
        // clock fixed at 2026-08-10; releaseDate 30 days earlier means today's offset is +30, past
        // every window below.
        LocalDate releaseDate = LocalDate.of(2026, 7, 11);
        RecommendedActionItem teaserTrailer = new RecommendedActionItem(
                RecommendedActionCategory.HIGH_IMPACT, "Releasing Teasers and Trailers at Optimal Timing",
                "R", 90, "Teaser/Trailer Timing", -45, -30, "label", List.of());
        RecommendedActionItem firstSingle = new RecommendedActionItem(
                RecommendedActionCategory.HIGH_IMPACT, "Releasing the First Single at an Optimal Time",
                "R", 90, "First Single Timing", -56, -42, "label", List.of());
        RecommendedActionItem criticalReviews = new RecommendedActionItem(
                RecommendedActionCategory.HIGH_IMPACT, "Critical Review Ratings on Aggregators",
                "R", 80, "Critical Reviews", 0, 7, "label", List.of());
        stubCachedActions(List.of(teaserTrailer, firstSingle, criticalReviews), releaseDate);

        RecommendedActionsResponse response = service.getRecommendedActions(ENTITY_ID, false, false);

        assertThat(response.getDaysToRelease()).isEqualTo(30);
        assertThat(response.getActions())
                .extracting(RecommendedActionItem::getTitle)
                .containsExactly("Critical Review Ratings on Aggregators");
    }

    // If the cached plan has nothing post-release-relevant at all, the panel must still not render
    // empty - falls all the way back to the full plan rather than the (now-empty) filtered set.
    @Test
    void windowFiltering_postRelease_fallsBackToFullPlanWhenNoPostReleaseActionsExist() throws Exception {
        LocalDate releaseDate = LocalDate.of(2026, 7, 11); // daysToRelease = +30
        RecommendedActionItem teaserTrailer = new RecommendedActionItem(
                RecommendedActionCategory.HIGH_IMPACT, "Releasing Teasers and Trailers at Optimal Timing",
                "R", 90, "Teaser/Trailer Timing", -45, -30, "label", List.of());
        RecommendedActionItem firstSingle = new RecommendedActionItem(
                RecommendedActionCategory.HIGH_IMPACT, "Releasing the First Single at an Optimal Time",
                "R", 90, "First Single Timing", -56, -42, "label", List.of());
        stubCachedActions(List.of(teaserTrailer, firstSingle), releaseDate);

        RecommendedActionsResponse response = service.getRecommendedActions(ENTITY_ID, false, false);

        assertThat(response.getDaysToRelease()).isEqualTo(30);
        assertThat(response.getActions())
                .extracting(RecommendedActionItem::getTitle)
                .containsExactlyInAnyOrder(
                        "Releasing Teasers and Trailers at Optimal Timing",
                        "Releasing the First Single at an Optimal Time");
    }

    // windowEndDaysFromRelease == 0 (a release-day action) must survive the post-release fallback
    // filter, while a purely pre-release window (ending the day before release) must not.
    @Test
    void windowFiltering_postRelease_fallbackRetainsActionEndingOnReleaseDayBoundary() throws Exception {
        LocalDate releaseDate = LocalDate.of(2026, 7, 11); // daysToRelease = +30
        RecommendedActionItem preReleaseOnly = new RecommendedActionItem(
                RecommendedActionCategory.HIGH_IMPACT, "T", "R", 90, "Factor", -14, -1, "label", List.of());
        RecommendedActionItem releaseDayBoundary = new RecommendedActionItem(
                RecommendedActionCategory.HIGH_IMPACT, "T2", "R2", 90, "Factor2", 0, 0, "label2", List.of());
        stubCachedActions(List.of(preReleaseOnly, releaseDayBoundary), releaseDate);

        RecommendedActionsResponse response = service.getRecommendedActions(ENTITY_ID, false, false);

        assertThat(response.getActions()).extracting(RecommendedActionItem::getTitle).containsExactly("T2");
    }

    // ==================== No-releaseDate fallback ====================

    @Test
    void noReleaseDate_returnsFullUnfilteredPlan() throws Exception {
        RecommendedActionItem action = new RecommendedActionItem(
                RecommendedActionCategory.HIGH_IMPACT, "T", "R", 90, "Factor", -100, -90, "label", List.of());
        stubCachedActions(List.of(action), null);

        RecommendedActionsResponse response = service.getRecommendedActions(ENTITY_ID, false, false);

        assertThat(response.getDaysToRelease()).isNull();
        assertThat(response.getActions()).hasSize(1);
    }

    // ==================== allPhases bypasses window filter ====================

    @Test
    void allPhasesTrue_bypassesWindowFilter() throws Exception {
        LocalDate releaseDate = LocalDate.of(2026, 8, 20);
        RecommendedActionItem outOfWindow = new RecommendedActionItem(
                RecommendedActionCategory.HIGH_IMPACT, "T", "R", 90, "Factor", 50, 60, "label", List.of());
        stubCachedActions(List.of(outOfWindow), releaseDate);

        RecommendedActionsResponse response = service.getRecommendedActions(ENTITY_ID, false, true);

        assertThat(response.getActions()).hasSize(1);
    }

    // ==================== Startup: priority movies only ====================

    @Test
    void onApplicationReady_refreshesOnlyPriorityMoviesAndSchedulesFirstFullCycle() {
        ManagedEntity toxic = entityWithIdAndName(1L, "Toxic");
        ManagedEntity gdNaidu = entityWithIdAndName(2L, "GD Naidu");
        ManagedEntity lordGaaga = entityWithIdAndName(3L, "Lord Gaaga");
        ManagedEntity other = entityWithIdAndName(4L, "Some Other Movie");
        List<ManagedEntity> all = List.of(toxic, gdNaidu, lordGaaga, other);
        when(entityRepository.findAll()).thenReturn(all);
        when(entityRepository.findById(any())).thenAnswer(inv ->
                all.stream().filter(e -> e.getId().equals(inv.getArgument(0))).findFirst());
        when(candidateService.buildCandidateActions(any())).thenReturn(List.of());

        service.onApplicationReady();

        verify(candidateService).buildCandidateActions(1L);
        verify(candidateService).buildCandidateActions(2L);
        verify(candidateService).buildCandidateActions(3L);
        verify(candidateService, never()).buildCandidateActions(4L);
        verify(cacheRepository, times(3)).save(any());

        ArgumentCaptor<Instant> instantCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(taskScheduler, times(1)).schedule(any(Runnable.class), instantCaptor.capture());
        assertThat(instantCaptor.getValue()).isEqualTo(Instant.parse("2026-08-11T10:00:00Z"));
    }

    // ==================== Steady-state full cycle: spacing + chaining ====================

    // Regression coverage for the "if there are 36 movies, each run will extend 24 hrs, in which case
    // it should be triggered after the previous runs complete" requirement: a full cycle spaces every
    // entity 1h apart, and schedules its successor 24h after *this* cycle's own start (not after it
    // finishes) - so a short cycle waits out the remainder of the 24h, while a cycle whose spacing
    // alone already exceeds 24h chains straight into the next one, since that computed instant is
    // already in the past by the time the last entity's task runs.
    @Test
    void fullCycle_spacesEachEntityOneHourApart_andChainsNextCycleAfterLastEntityCompletes() {
        ManagedEntity e1 = entityWithIdAndName(10L, "Movie A");
        ManagedEntity e2 = entityWithIdAndName(11L, "Movie B");
        ManagedEntity e3 = entityWithIdAndName(12L, "Movie C");
        List<ManagedEntity> all = List.of(e1, e2, e3);
        when(entityRepository.findAll()).thenReturn(all);
        when(entityRepository.findById(any())).thenAnswer(inv ->
                all.stream().filter(e -> e.getId().equals(inv.getArgument(0))).findFirst());
        when(candidateService.buildCandidateActions(any())).thenReturn(List.of());

        // None of these entities match a startup-priority name, so this only exercises the first
        // full-cycle scheduling call, with no priority-movie refresh noise.
        service.onApplicationReady();

        ArgumentCaptor<Runnable> cycleCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler, times(1)).schedule(cycleCaptor.capture(), eq(Instant.parse("2026-08-11T10:00:00Z")));
        Runnable firstCycle = cycleCaptor.getValue();

        // Fire the captured cycle to simulate it running "24h later" - the fixed test Clock still
        // reads the original startup instant, so cycleStart below is numerically identical to it; only
        // the *relative* spacing between entities, and to the next cycle, is under test here.
        firstCycle.run();

        ArgumentCaptor<Runnable> afterCycleTaskCaptor = ArgumentCaptor.forClass(Runnable.class);
        ArgumentCaptor<Instant> afterCycleInstantCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(taskScheduler, times(4)).schedule(afterCycleTaskCaptor.capture(), afterCycleInstantCaptor.capture());
        // Index 0 is the outer cycle-scheduling call captured above; indices 1-3 are this cycle's 3
        // entities, spaced 1h apart from the cycle's own start.
        assertThat(afterCycleInstantCaptor.getAllValues().get(1)).isEqualTo(Instant.parse("2026-08-10T10:00:00Z"));
        assertThat(afterCycleInstantCaptor.getAllValues().get(2)).isEqualTo(Instant.parse("2026-08-10T11:00:00Z"));
        assertThat(afterCycleInstantCaptor.getAllValues().get(3)).isEqualTo(Instant.parse("2026-08-10T12:00:00Z"));
        Runnable firstEntityTask = afterCycleTaskCaptor.getAllValues().get(1);
        Runnable lastEntityTask = afterCycleTaskCaptor.getAllValues().get(3);

        // Running a non-last entity's task refreshes it but must not chain a next cycle yet.
        firstEntityTask.run();
        verify(candidateService).buildCandidateActions(10L);
        verify(candidateService, never()).buildCandidateActions(12L);
        verify(taskScheduler, times(4)).schedule(any(), any(Instant.class));

        // Running the last entity's task refreshes it AND chains the next cycle 24h after this cycle's
        // own start (2026-08-10T10:00:00Z + 24h), not after this cycle finishes.
        lastEntityTask.run();
        verify(candidateService).buildCandidateActions(12L);
        ArgumentCaptor<Instant> finalInstantCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(taskScheduler, times(5)).schedule(any(), finalInstantCaptor.capture());
        assertThat(finalInstantCaptor.getAllValues().get(4)).isEqualTo(Instant.parse("2026-08-11T10:00:00Z"));
    }
}
