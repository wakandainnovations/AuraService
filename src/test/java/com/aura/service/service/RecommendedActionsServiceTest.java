package com.aura.service.service;

import com.aura.service.dto.RecommendedActionCandidate;
import com.aura.service.dto.RecommendedActionItem;
import com.aura.service.dto.RecommendedActionsResponse;
import com.aura.service.entity.ManagedEntity;
import com.aura.service.entity.RecommendedActionsCache;
import com.aura.service.enums.RecommendedActionCategory;
import com.aura.service.enums.RecommendedActionStatus;
import com.aura.service.exception.ResourceNotFoundException;
import com.aura.service.repository.ManagedEntityRepository;
import com.aura.service.repository.RecommendedActionsCacheRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
 * and the ACTIVE-action capping/DONE-IRRELEVANT-inclusion logic. Collaborators are mocked as
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
                List.of(), List.of());
    }

    private static RecommendedActionCandidate candidateWithHandles(
            String id, String factor, int confidence, int start, int end, String label,
            List<String> exampleHandles, String... facts) {
        return new RecommendedActionCandidate(
                id, factor, RecommendedActionCategory.HIGH_IMPACT, confidence, start, end, label, List.of(facts),
                exampleHandles, List.of());
    }

    // ==================== Cache hit ====================

    @Test
    void cacheHit_returnsWithoutCallingLlmOrCandidateService() throws Exception {
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity(null)));
        List<RecommendedActionItem> cachedActions = List.of(new RecommendedActionItem("test-candidate-1", RecommendedActionCategory.HIGH_IMPACT, "Cached Title", "Cached reason", 85, "Factor A", -10, 10,
                "label", List.of(), List.of(), RecommendedActionStatus.ACTIVE));
        RecommendedActionsCache row = new RecommendedActionsCache(
                1L, ENTITY_ID, "Test Movie", MAPPER.writeValueAsString(cachedActions), 0,
                Instant.parse("2026-08-01T00:00:00Z"));
        when(cacheRepository.findByEntityId(ENTITY_ID)).thenReturn(Optional.of(row));

        RecommendedActionsResponse response = service.getRecommendedActions(ENTITY_ID, false);

        assertThat(response.getActions()).hasSize(1);
        assertThat(response.getActions().get(0).getTitle()).isEqualTo("Cached Title");
        verify(candidateService, never()).buildCandidateActions(any());
        verify(llmService, never()).generateReply(any());
    }

    // ==================== refresh=true: background regeneration, low-latency response ====================

    @Test
    void refresh_withExistingCache_respondsWithCachedContentImmediately_andSchedulesBackgroundRefresh()
            throws Exception {
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity(null)));
        List<RecommendedActionItem> cachedActions = List.of(new RecommendedActionItem(
                "test-candidate-1", RecommendedActionCategory.HIGH_IMPACT, "Cached Title", "Cached reason", 85,
                "Factor A", -10, 10, "label", List.of(), List.of(), RecommendedActionStatus.ACTIVE));
        RecommendedActionsCache row = new RecommendedActionsCache(
                1L, ENTITY_ID, "Test Movie", MAPPER.writeValueAsString(cachedActions), 0,
                Instant.parse("2026-08-01T00:00:00Z"));
        when(cacheRepository.findByEntityId(ENTITY_ID)).thenReturn(Optional.of(row));

        RecommendedActionsResponse response = service.getRecommendedActions(ENTITY_ID, true);

        // Responds with the existing cached content right away - no synchronous LLM/candidate-service
        // call on this request's own thread, which is the whole point of moving refresh to the
        // background (low latency over freshness for this one call).
        assertThat(response.getActions()).hasSize(1);
        assertThat(response.getActions().get(0).getTitle()).isEqualTo("Cached Title");
        verify(candidateService, never()).buildCandidateActions(any());
        verify(llmService, never()).generateReply(any());

        // The background regeneration is still scheduled, just deferred - not dropped.
        verify(taskScheduler).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void refresh_withNoExistingCache_stillGeneratesSynchronously() throws Exception {
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity(null)));
        when(cacheRepository.findByEntityId(ENTITY_ID)).thenReturn(Optional.empty());

        RecommendedActionCandidate c1 = candidate(
                "factor-46-teaser", "Teaser/Trailer Timing", 90, -45, -30, "label", "some fact");
        when(candidateService.buildCandidateActions(ENTITY_ID)).thenReturn(List.of(c1));
        when(llmService.generateReply(any())).thenReturn(
                "[{\"candidateId\": \"factor-46-teaser\", \"reason\": \"Grounded reason.\"}]");

        // refresh=true, but there's nothing cached yet to respond with immediately - this one call has
        // to generate synchronously and wait for it, same as a cache miss without refresh.
        RecommendedActionsResponse response = service.getRecommendedActions(ENTITY_ID, true);

        assertThat(response.getActions()).hasSize(1);
        assertThat(response.getActions().get(0).getReason()).isEqualTo("Grounded reason.");
        verify(llmService).generateReply(any());
        verify(taskScheduler, never()).schedule(any(Runnable.class), any(Instant.class));
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

        RecommendedActionsResponse response = service.getRecommendedActions(ENTITY_ID, false);

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

        RecommendedActionsResponse response = service.getRecommendedActions(ENTITY_ID, false);

        assertThat(response.getActions()).hasSize(1);
        assertThat(response.getActions().get(0).getReason()).isEqualTo("Grounded reason.");
    }

    // ==================== LLM-supplied confidencePct (playbook candidates only) ====================

    @Test
    void llmConfidencePct_usedForUnderdogPlaybookCandidate() {
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity(null)));
        when(cacheRepository.findByEntityId(ENTITY_ID)).thenReturn(Optional.empty());

        RecommendedActionCandidate c1 = candidate(
                "underdog-playbook-curiosity-gap", "Weaponize the Curiosity Gap", 60, -270, -14, "label", "guidance");
        when(candidateService.buildCandidateActions(ENTITY_ID)).thenReturn(List.of(c1));
        when(llmService.generateReply(any())).thenReturn(
                "[{\"candidateId\": \"underdog-playbook-curiosity-gap\", \"reason\": \"Fits this movie well.\", " +
                        "\"confidencePct\": 78}]");

        RecommendedActionsResponse response = service.getRecommendedActions(ENTITY_ID, false);

        assertThat(response.getActions().get(0).getConfidencePct()).isEqualTo(78);
    }

    @Test
    void llmConfidencePct_usedForViralStuntPlaybookCandidate() {
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity(null)));
        when(cacheRepository.findByEntityId(ENTITY_ID)).thenReturn(Optional.empty());

        RecommendedActionCandidate c1 = candidate(
                "viral-stunt-playbook-manufactured-leak", "Manufacture a Viral Leak", 60, -270, -14, "label",
                "guidance");
        when(candidateService.buildCandidateActions(ENTITY_ID)).thenReturn(List.of(c1));
        when(llmService.generateReply(any())).thenReturn(
                "[{\"candidateId\": \"viral-stunt-playbook-manufactured-leak\", \"reason\": \"Fits this movie.\", " +
                        "\"confidencePct\": 45}]");

        RecommendedActionsResponse response = service.getRecommendedActions(ENTITY_ID, false);

        assertThat(response.getActions().get(0).getConfidencePct()).isEqualTo(45);
    }

    @Test
    void llmConfidencePct_ignoredForOrdinaryCandidate() {
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity(null)));
        when(cacheRepository.findByEntityId(ENTITY_ID)).thenReturn(Optional.empty());

        RecommendedActionCandidate c1 = candidate(
                "factor-46-teaser", "Teaser/Trailer Timing", 90, -45, -30, "label", "some fact");
        when(candidateService.buildCandidateActions(ENTITY_ID)).thenReturn(List.of(c1));
        // Even if the LLM (against instructions) supplies a confidencePct for a non-playbook candidate,
        // it must be ignored - only the two curated playbook families are allowed to override it.
        when(llmService.generateReply(any())).thenReturn(
                "[{\"candidateId\": \"factor-46-teaser\", \"reason\": \"Grounded reason.\", " +
                        "\"confidencePct\": 10}]");

        RecommendedActionsResponse response = service.getRecommendedActions(ENTITY_ID, false);

        assertThat(response.getActions().get(0).getConfidencePct()).isEqualTo(90);
    }

    @Test
    void llmConfidencePct_fallsBackToServerDefaultWhenOutOfRange() {
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity(null)));
        when(cacheRepository.findByEntityId(ENTITY_ID)).thenReturn(Optional.empty());

        RecommendedActionCandidate c1 = candidate(
                "underdog-playbook-curiosity-gap", "Weaponize the Curiosity Gap", 60, -270, -14, "label", "guidance");
        when(candidateService.buildCandidateActions(ENTITY_ID)).thenReturn(List.of(c1));
        when(llmService.generateReply(any())).thenReturn(
                "[{\"candidateId\": \"underdog-playbook-curiosity-gap\", \"reason\": \"Fits this movie well.\", " +
                        "\"confidencePct\": 150}]");

        RecommendedActionsResponse response = service.getRecommendedActions(ENTITY_ID, false);

        assertThat(response.getActions().get(0).getConfidencePct()).isEqualTo(60);
    }

    @Test
    void llmConfidencePct_fallsBackToServerDefaultWhenNonInteger() {
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity(null)));
        when(cacheRepository.findByEntityId(ENTITY_ID)).thenReturn(Optional.empty());

        RecommendedActionCandidate c1 = candidate(
                "underdog-playbook-curiosity-gap", "Weaponize the Curiosity Gap", 60, -270, -14, "label", "guidance");
        when(candidateService.buildCandidateActions(ENTITY_ID)).thenReturn(List.of(c1));
        when(llmService.generateReply(any())).thenReturn(
                "[{\"candidateId\": \"underdog-playbook-curiosity-gap\", \"reason\": \"Fits this movie well.\", " +
                        "\"confidencePct\": \"high\"}]");

        RecommendedActionsResponse response = service.getRecommendedActions(ENTITY_ID, false);

        assertThat(response.getActions().get(0).getConfidencePct()).isEqualTo(60);
    }

    @Test
    void llmConfidencePct_usesServerDefaultWhenOmittedForPlaybookCandidate() {
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity(null)));
        when(cacheRepository.findByEntityId(ENTITY_ID)).thenReturn(Optional.empty());

        RecommendedActionCandidate c1 = candidate(
                "underdog-playbook-curiosity-gap", "Weaponize the Curiosity Gap", 60, -270, -14, "label", "guidance");
        when(candidateService.buildCandidateActions(ENTITY_ID)).thenReturn(List.of(c1));
        when(llmService.generateReply(any())).thenReturn(
                "[{\"candidateId\": \"underdog-playbook-curiosity-gap\", \"reason\": \"Fits this movie well.\"}]");

        RecommendedActionsResponse response = service.getRecommendedActions(ENTITY_ID, false);

        assertThat(response.getActions().get(0).getConfidencePct()).isEqualTo(60);
    }

    // ==================== statisticalEvidence prompt construction ====================

    // Covers the F9 contract: buildPrompt must serialize exactly the StatisticalEvidence fields present
    // on the candidate, unmodified - never omit one that's set, never fabricate one that's null.
    @Test
    void prompt_includesOnlyStatisticalEvidenceFieldsPresentOnCandidate_unmodified() throws Exception {
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity(null)));
        when(cacheRepository.findByEntityId(ENTITY_ID)).thenReturn(Optional.empty());

        RecommendedActionCandidate.StatisticalEvidence evidence = new RecommendedActionCandidate.StatisticalEvidence(
                "trailer_before_friday", "HIGHER_IN_OVERPERFORMERS", 0.0041, 0.031, 57L, null, null, null);
        RecommendedActionCandidate c1 = new RecommendedActionCandidate(
                "nonobvious-lever-trailer-before-friday", "trailer_before_friday", RecommendedActionCategory.MEDIUM_IMPACT,
                70, -120, -1, "label", List.of(), List.of(), List.of(), evidence);
        when(candidateService.buildCandidateActions(ENTITY_ID)).thenReturn(List.of(c1));

        when(llmService.generateReply(any())).thenReturn(
                "[{\"candidateId\": \"nonobvious-lever-trailer-before-friday\", " +
                        "\"reason\": \"This is 3.1% (q=0.031) with 57 comparable entities.\"}]");

        service.getRecommendedActions(ENTITY_ID, false);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(llmService).generateReply(promptCaptor.capture());
        JsonNode candidateNode = MAPPER.readTree(promptCaptor.getValue()).get("candidates").get(0);
        JsonNode se = candidateNode.get("statisticalEvidence");

        assertThat(se.get("featureName").asText()).isEqualTo("trailer_before_friday");
        assertThat(se.get("direction").asText()).isEqualTo("HIGHER_IN_OVERPERFORMERS");
        assertThat(se.get("pValue").asDouble()).isEqualTo(0.0041);
        assertThat(se.get("fdrQValue").asDouble()).isEqualTo(0.031);
        assertThat(se.get("nEntities").asLong()).isEqualTo(57L);
        // Fields not present on this candidate's StatisticalEvidence (playbook-only fields) must not
        // appear at all - the Java layer never backfills or computes a value the candidate didn't carry.
        assertThat(se.has("patternSequence")).isFalse();
        assertThat(se.has("supportTopTier")).isFalse();
        assertThat(se.has("supportBottomTier")).isFalse();
    }

    @Test
    void prompt_omitsStatisticalEvidenceEntirelyForOrdinaryCandidate() throws Exception {
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity(null)));
        when(cacheRepository.findByEntityId(ENTITY_ID)).thenReturn(Optional.empty());

        RecommendedActionCandidate c1 = candidate(
                "factor-46-teaser", "Teaser/Trailer Timing", 90, -45, -30, "label", "some fact");
        when(candidateService.buildCandidateActions(ENTITY_ID)).thenReturn(List.of(c1));
        when(llmService.generateReply(any())).thenReturn(
                "[{\"candidateId\": \"factor-46-teaser\", \"reason\": \"Grounded reason.\"}]");

        service.getRecommendedActions(ENTITY_ID, false);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(llmService).generateReply(promptCaptor.capture());
        JsonNode candidateNode = MAPPER.readTree(promptCaptor.getValue()).get("candidates").get(0);
        assertThat(candidateNode.has("statisticalEvidence")).isFalse();
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

        RecommendedActionsResponse response = service.getRecommendedActions(ENTITY_ID, false);

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

        RecommendedActionsResponse response = service.getRecommendedActions(ENTITY_ID, false);

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

        RecommendedActionsResponse response = service.getRecommendedActions(ENTITY_ID, false);

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

        RecommendedActionsResponse response = service.getRecommendedActions(ENTITY_ID, false);

        assertThat(response.getActions()).hasSize(1);
        RecommendedActionItem item = response.getActions().get(0);
        assertThat(item.getTitle()).isEqualTo("Factor X");
        assertThat(item.getReason()).contains("18 comparable releases averaged $1,000,000.");
        assertThat(item.getConfidencePct()).isEqualTo(70);
    }

    // ==================== daysToRelease is informational only, no longer filters ====================

    private void stubCachedActions(List<RecommendedActionItem> actions, LocalDate releaseDate) throws Exception {
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity(releaseDate)));
        RecommendedActionsCache row = new RecommendedActionsCache(
                1L, ENTITY_ID, "Test Movie", MAPPER.writeValueAsString(actions), 0,
                Instant.parse("2026-08-01T00:00:00Z"));
        when(cacheRepository.findByEntityId(ENTITY_ID)).thenReturn(Optional.of(row));
    }

    // Regression coverage for the "Lord Gaaga" bug: an ACTIVE action whose own execution window
    // doesn't contain today must still be returned - getRecommendedActions no longer narrows by
    // windowStartDaysFromRelease/windowEndDaysFromRelease at all (see that method's own doc for why:
    // it made the panel's count fluctuate day to day for reasons the API contract never explained).
    // daysToRelease is still computed and returned on the response, but purely informational.
    @Test
    void actionOutsideItsOwnWindow_isStillReturned() throws Exception {
        LocalDate releaseDate = LocalDate.of(2026, 8, 20);
        RecommendedActionItem outOfWindow = new RecommendedActionItem(
                "test-candidate-1", RecommendedActionCategory.HIGH_IMPACT, "T", "R", 90, "Factor", 50, 60, "label",
                List.of(), List.of(), RecommendedActionStatus.ACTIVE);
        stubCachedActions(List.of(outOfWindow), releaseDate);

        RecommendedActionsResponse response = service.getRecommendedActions(ENTITY_ID, false);

        assertThat(response.getDaysToRelease()).isEqualTo(-10);
        assertThat(response.getActions()).hasSize(1);
    }

    @Test
    void noReleaseDate_daysToReleaseIsNullButActionsStillReturned() throws Exception {
        RecommendedActionItem action = new RecommendedActionItem(
                "test-candidate-2", RecommendedActionCategory.HIGH_IMPACT, "T", "R", 90, "Factor", -100, -90,
                "label", List.of(), List.of(), RecommendedActionStatus.ACTIVE);
        stubCachedActions(List.of(action), null);

        RecommendedActionsResponse response = service.getRecommendedActions(ENTITY_ID, false);

        assertThat(response.getDaysToRelease()).isNull();
        assertThat(response.getActions()).hasSize(1);
    }

    // ==================== Status filtering on the main "what to do now" panel ====================

    @Test
    void getRecommendedActions_includesDoneAndIrrelevantActionsAlongsideActive() throws Exception {
        LocalDate releaseDate = LocalDate.of(2026, 8, 20);
        RecommendedActionItem active = new RecommendedActionItem(
                "id-active", RecommendedActionCategory.HIGH_IMPACT, "Active", "R", 90, "Factor", -10, 10, "label",
                List.of(), List.of(), RecommendedActionStatus.ACTIVE);
        RecommendedActionItem done = new RecommendedActionItem(
                "id-done", RecommendedActionCategory.HIGH_IMPACT, "Done", "R", 90, "Factor2", -10, 10, "label",
                List.of(), List.of(), RecommendedActionStatus.DONE);
        RecommendedActionItem irrelevant = new RecommendedActionItem(
                "id-irrelevant", RecommendedActionCategory.HIGH_IMPACT, "Irrelevant", "R", 90, "Factor3", -10, 10,
                "label", List.of(), List.of(), RecommendedActionStatus.IRRELEVANT);
        stubCachedActions(List.of(active, done, irrelevant), releaseDate);

        RecommendedActionsResponse response = service.getRecommendedActions(ENTITY_ID, false);

        // DONE/IRRELEVANT are already-handled history, not a queue to trim - always included, uncapped,
        // alongside whichever ACTIVE actions made the (possibly random) cut.
        assertThat(response.getActions()).extracting(RecommendedActionItem::getTitle)
                .containsExactlyInAnyOrder("Active", "Done", "Irrelevant");
    }

    @Test
    void getRecommendedActions_capsActiveActionsAtFiveButKeepsAllDoneAndIrrelevant() throws Exception {
        LocalDate releaseDate = LocalDate.of(2026, 8, 20);
        List<RecommendedActionItem> activeActions = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            activeActions.add(new RecommendedActionItem(
                    "id-active-" + i, RecommendedActionCategory.HIGH_IMPACT, "Active " + i, "R", 90, "Factor", -10,
                    10, "label", List.of(), List.of(), RecommendedActionStatus.ACTIVE));
        }
        RecommendedActionItem done = new RecommendedActionItem(
                "id-done", RecommendedActionCategory.HIGH_IMPACT, "Done", "R", 90, "Factor2", -10, 10, "label",
                List.of(), List.of(), RecommendedActionStatus.DONE);
        List<RecommendedActionItem> all = new ArrayList<>(activeActions);
        all.add(done);
        stubCachedActions(all, releaseDate);

        RecommendedActionsResponse response = service.getRecommendedActions(ENTITY_ID, false);

        List<RecommendedActionItem> returnedActive = response.getActions().stream()
                .filter(a -> a.getStatus() == RecommendedActionStatus.ACTIVE)
                .toList();
        assertThat(returnedActive).hasSize(5);
        // Every returned active action is a real one from the original 8, not fabricated.
        assertThat(activeActions).containsAll(returnedActive);
        assertThat(response.getActions()).filteredOn(a -> a.getStatus() == RecommendedActionStatus.DONE)
                .extracting(RecommendedActionItem::getTitle).containsExactly("Done");
    }

    @Test
    void getRecommendedActions_doesNotCapWhenFiveOrFewerActiveActions() throws Exception {
        LocalDate releaseDate = LocalDate.of(2026, 8, 20);
        List<RecommendedActionItem> activeActions = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            activeActions.add(new RecommendedActionItem(
                    "id-active-" + i, RecommendedActionCategory.HIGH_IMPACT, "Active " + i, "R", 90, "Factor", -10,
                    10, "label", List.of(), List.of(), RecommendedActionStatus.ACTIVE));
        }
        stubCachedActions(activeActions, releaseDate);

        RecommendedActionsResponse response = service.getRecommendedActions(ENTITY_ID, false);

        assertThat(response.getActions()).hasSize(5);
    }

    // ==================== Regeneration merges onto history instead of overwriting it ====================

    @Test
    void regenerate_preservesExistingStatusForReselectedCandidate_andRetainsHistoricalActionNotReselected()
            throws Exception {
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity(null)));

        RecommendedActionItem doneBefore = new RecommendedActionItem(
                "factor-A", RecommendedActionCategory.HIGH_IMPACT, "Old Title", "Old reason", 80, "Factor A",
                -10, 10, "label", List.of(), List.of(), RecommendedActionStatus.DONE);
        RecommendedActionItem historicalOnly = new RecommendedActionItem(
                "factor-old", RecommendedActionCategory.MEDIUM_IMPACT, "Historical Title", "Historical reason", 60,
                "Factor Old", -30, -20, "label2", List.of(), List.of(), RecommendedActionStatus.ACTIVE);
        RecommendedActionsCache existingRow = new RecommendedActionsCache(
                1L, ENTITY_ID, "Test Movie", MAPPER.writeValueAsString(List.of(doneBefore, historicalOnly)), 0,
                Instant.parse("2026-08-01T00:00:00Z"));
        when(cacheRepository.findByEntityId(ENTITY_ID)).thenReturn(Optional.of(existingRow));

        RecommendedActionCandidate freshCandidate = candidate(
                "factor-A", "Factor A", 85, -10, 10, "label", "fresh fact");
        when(candidateService.buildCandidateActions(ENTITY_ID)).thenReturn(List.of(freshCandidate));
        when(llmService.generateReply(any())).thenReturn(
                "[{\"candidateId\": \"factor-A\", \"title\": \"New Title\", \"reason\": \"fresh fact\"}]");

        // refresh=true schedules regeneration in the background rather than running it inline (see
        // getCachedOrGenerate) - capture and run the scheduled task ourselves to exercise the same
        // regenerate/merge/persist path this test is actually covering.
        service.getRecommendedActions(ENTITY_ID, true);
        ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(taskCaptor.capture(), any(Instant.class));
        taskCaptor.getValue().run();

        ArgumentCaptor<RecommendedActionsCache> captor = ArgumentCaptor.forClass(RecommendedActionsCache.class);
        verify(cacheRepository).save(captor.capture());
        List<RecommendedActionItem> persisted = MAPPER.readValue(
                captor.getValue().getActionsJson(), new TypeReference<List<RecommendedActionItem>>() {
                });

        assertThat(persisted).hasSize(2);
        RecommendedActionItem persistedFactorA = persisted.stream()
                .filter(a -> "factor-A".equals(a.getCandidateId())).findFirst().orElseThrow();
        // Reselected this cycle: content refreshed, but the marketing team's DONE status survives.
        assertThat(persistedFactorA.getStatus()).isEqualTo(RecommendedActionStatus.DONE);
        assertThat(persistedFactorA.getTitle()).isEqualTo("New Title");

        RecommendedActionItem persistedHistorical = persisted.stream()
                .filter(a -> "factor-old".equals(a.getCandidateId())).findFirst().orElseThrow();
        // Not reselected this cycle - carried forward unchanged rather than dropped.
        assertThat(persistedHistorical.getStatus()).isEqualTo(RecommendedActionStatus.ACTIVE);
        assertThat(persistedHistorical.getTitle()).isEqualTo("Historical Title");
    }

    // ==================== updateActionStatus ====================

    @Test
    void updateActionStatus_marksMatchingActionAndPersists() throws Exception {
        RecommendedActionItem action = new RecommendedActionItem(
                "id-1", RecommendedActionCategory.HIGH_IMPACT, "Title", "Reason", 90, "Factor", -10, 10, "label",
                List.of(), List.of(), RecommendedActionStatus.ACTIVE);
        RecommendedActionsCache row = new RecommendedActionsCache(
                1L, ENTITY_ID, "Test Movie", MAPPER.writeValueAsString(List.of(action)), 0,
                Instant.parse("2026-08-01T00:00:00Z"));
        when(cacheRepository.findByEntityId(ENTITY_ID)).thenReturn(Optional.of(row));

        RecommendedActionItem updated = service.updateActionStatus(ENTITY_ID, "id-1", RecommendedActionStatus.DONE);

        assertThat(updated.getStatus()).isEqualTo(RecommendedActionStatus.DONE);
        verify(cacheRepository).save(row);
        List<RecommendedActionItem> persisted = MAPPER.readValue(
                row.getActionsJson(), new TypeReference<List<RecommendedActionItem>>() {
                });
        assertThat(persisted).extracting(RecommendedActionItem::getStatus).containsExactly(RecommendedActionStatus.DONE);
    }

    @Test
    void updateActionStatus_throwsWhenNoCacheRowExists() {
        when(cacheRepository.findByEntityId(ENTITY_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateActionStatus(ENTITY_ID, "id-1", RecommendedActionStatus.DONE))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updateActionStatus_throwsWhenCandidateIdUnknown() throws Exception {
        RecommendedActionItem action = new RecommendedActionItem(
                "id-1", RecommendedActionCategory.HIGH_IMPACT, "Title", "Reason", 90, "Factor", -10, 10, "label",
                List.of(), List.of(), RecommendedActionStatus.ACTIVE);
        RecommendedActionsCache row = new RecommendedActionsCache(
                1L, ENTITY_ID, "Test Movie", MAPPER.writeValueAsString(List.of(action)), 0,
                Instant.parse("2026-08-01T00:00:00Z"));
        when(cacheRepository.findByEntityId(ENTITY_ID)).thenReturn(Optional.of(row));

        assertThatThrownBy(() -> service.updateActionStatus(ENTITY_ID, "unknown-id", RecommendedActionStatus.DONE))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ==================== getAllRecommendedActions ====================

    @Test
    void getAllRecommendedActions_returnsEveryStatusUnfilteredByWindow() throws Exception {
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity(LocalDate.of(2026, 8, 20))));
        RecommendedActionItem active = new RecommendedActionItem(
                "id-active", RecommendedActionCategory.HIGH_IMPACT, "Active", "R", 90, "Factor", 100, 120, "label",
                List.of(), List.of(), RecommendedActionStatus.ACTIVE);
        RecommendedActionItem done = new RecommendedActionItem(
                "id-done", RecommendedActionCategory.HIGH_IMPACT, "Done", "R", 90, "Factor2", -365, -300, "label",
                List.of(), List.of(), RecommendedActionStatus.DONE);
        RecommendedActionsCache row = new RecommendedActionsCache(
                1L, ENTITY_ID, "Test Movie", MAPPER.writeValueAsString(List.of(active, done)), 0,
                Instant.parse("2026-08-01T00:00:00Z"));
        when(cacheRepository.findByEntityId(ENTITY_ID)).thenReturn(Optional.of(row));

        RecommendedActionsResponse response = service.getAllRecommendedActions(ENTITY_ID, null);

        assertThat(response.getActions()).extracting(RecommendedActionItem::getTitle)
                .containsExactlyInAnyOrder("Active", "Done");
    }

    @Test
    void getAllRecommendedActions_filtersByRequestedStatus() throws Exception {
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity(null)));
        RecommendedActionItem active = new RecommendedActionItem(
                "id-active", RecommendedActionCategory.HIGH_IMPACT, "Active", "R", 90, "Factor", -10, 10, "label",
                List.of(), List.of(), RecommendedActionStatus.ACTIVE);
        RecommendedActionItem done = new RecommendedActionItem(
                "id-done", RecommendedActionCategory.HIGH_IMPACT, "Done", "R", 90, "Factor2", -10, 10, "label",
                List.of(), List.of(), RecommendedActionStatus.DONE);
        RecommendedActionsCache row = new RecommendedActionsCache(
                1L, ENTITY_ID, "Test Movie", MAPPER.writeValueAsString(List.of(active, done)), 0,
                Instant.parse("2026-08-01T00:00:00Z"));
        when(cacheRepository.findByEntityId(ENTITY_ID)).thenReturn(Optional.of(row));

        RecommendedActionsResponse response = service.getAllRecommendedActions(ENTITY_ID, RecommendedActionStatus.DONE);

        assertThat(response.getActions()).extracting(RecommendedActionItem::getTitle).containsExactly("Done");
    }

    @Test
    void getAllRecommendedActions_throwsWhenNoCacheRowExists() {
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity(null)));
        when(cacheRepository.findByEntityId(ENTITY_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getAllRecommendedActions(ENTITY_ID, null))
                .isInstanceOf(ResourceNotFoundException.class);
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
