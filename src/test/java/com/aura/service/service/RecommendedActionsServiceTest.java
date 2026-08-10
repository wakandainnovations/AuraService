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
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
    private Clock clock;
    private RecommendedActionsService service;

    @BeforeEach
    void setUp() {
        entityRepository = mock(ManagedEntityRepository.class);
        cacheRepository = mock(RecommendedActionsCacheRepository.class);
        candidateService = mock(RecommendedActionCandidateService.class);
        llmService = mock(LLMService.class);
        clock = Clock.fixed(Instant.parse("2026-08-10T10:00:00Z"), ZoneOffset.UTC);

        service = new RecommendedActionsService(entityRepository, cacheRepository, candidateService, llmService, clock);
        ReflectionTestUtils.setField(service, "llmPrompt", PROMPT_TEMPLATE);
    }

    private static ManagedEntity entity(LocalDate releaseDate) {
        ManagedEntity e = new ManagedEntity();
        e.setId(ENTITY_ID);
        e.setName("Test Movie");
        e.setReleaseDate(releaseDate);
        return e;
    }

    private static RecommendedActionCandidate candidate(
            String id, String factor, int confidence, int start, int end, String label, String... facts) {
        return new RecommendedActionCandidate(
                id, factor, RecommendedActionCategory.HIGH_IMPACT, confidence, start, end, label, List.of(facts));
    }

    // ==================== Cache hit ====================

    @Test
    void cacheHit_returnsWithoutCallingLlmOrCandidateService() throws Exception {
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity(null)));
        List<RecommendedActionItem> cachedActions = List.of(new RecommendedActionItem(
                RecommendedActionCategory.HIGH_IMPACT, "Cached Title", "Cached reason", 85, "Factor A", -10, 10, "label"));
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
                RecommendedActionCategory.HIGH_IMPACT, "T", "R", 90, "Factor", -10, -5, "label");
        stubCachedActions(List.of(inWindow), releaseDate);

        RecommendedActionsResponse response = service.getRecommendedActions(ENTITY_ID, false, false);

        assertThat(response.getDaysToRelease()).isEqualTo(-10);
        assertThat(response.getActions()).hasSize(1);
    }

    @Test
    void windowFiltering_includesActionAtEndBoundary() throws Exception {
        LocalDate releaseDate = LocalDate.of(2026, 8, 20);
        RecommendedActionItem inWindow = new RecommendedActionItem(
                RecommendedActionCategory.HIGH_IMPACT, "T", "R", 90, "Factor", -20, -10, "label");
        stubCachedActions(List.of(inWindow), releaseDate);

        RecommendedActionsResponse response = service.getRecommendedActions(ENTITY_ID, false, false);

        assertThat(response.getDaysToRelease()).isEqualTo(-10);
        assertThat(response.getActions()).hasSize(1);
    }

    @Test
    void windowFiltering_excludesOneDayBeforeStart_butOtherInWindowActionStillSurvives() throws Exception {
        LocalDate releaseDate = LocalDate.of(2026, 8, 20);
        RecommendedActionItem outOfWindow = new RecommendedActionItem(
                RecommendedActionCategory.HIGH_IMPACT, "T", "R", 90, "Factor", -9, -1, "label");
        RecommendedActionItem inWindow = new RecommendedActionItem(
                RecommendedActionCategory.HIGH_IMPACT, "T2", "R2", 90, "Factor2", -10, -5, "label2");
        stubCachedActions(List.of(outOfWindow, inWindow), releaseDate);

        RecommendedActionsResponse response = service.getRecommendedActions(ENTITY_ID, false, false);

        assertThat(response.getDaysToRelease()).isEqualTo(-10);
        assertThat(response.getActions()).extracting(RecommendedActionItem::getTitle).containsExactly("T2");
    }

    @Test
    void windowFiltering_excludesOneDayAfterEnd_butOtherInWindowActionStillSurvives() throws Exception {
        LocalDate releaseDate = LocalDate.of(2026, 8, 20);
        RecommendedActionItem outOfWindow = new RecommendedActionItem(
                RecommendedActionCategory.HIGH_IMPACT, "T", "R", 90, "Factor", -20, -11, "label");
        RecommendedActionItem inWindow = new RecommendedActionItem(
                RecommendedActionCategory.HIGH_IMPACT, "T2", "R2", 90, "Factor2", -10, -5, "label2");
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
                RecommendedActionCategory.HIGH_IMPACT, "T", "R", 90, "Factor", -9, -1, "label");
        RecommendedActionItem outOfWindow2 = new RecommendedActionItem(
                RecommendedActionCategory.HIGH_IMPACT, "T2", "R2", 90, "Factor2", -20, -11, "label2");
        stubCachedActions(List.of(outOfWindow1, outOfWindow2), releaseDate);

        RecommendedActionsResponse response = service.getRecommendedActions(ENTITY_ID, false, false);

        assertThat(response.getDaysToRelease()).isEqualTo(-10);
        assertThat(response.getActions()).extracting(RecommendedActionItem::getTitle).containsExactly("T", "T2");
    }

    // ==================== No-releaseDate fallback ====================

    @Test
    void noReleaseDate_returnsFullUnfilteredPlan() throws Exception {
        RecommendedActionItem action = new RecommendedActionItem(
                RecommendedActionCategory.HIGH_IMPACT, "T", "R", 90, "Factor", -100, -90, "label");
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
                RecommendedActionCategory.HIGH_IMPACT, "T", "R", 90, "Factor", 50, 60, "label");
        stubCachedActions(List.of(outOfWindow), releaseDate);

        RecommendedActionsResponse response = service.getRecommendedActions(ENTITY_ID, false, true);

        assertThat(response.getActions()).hasSize(1);
    }
}
