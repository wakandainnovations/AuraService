package com.aura.service.service;

import com.aura.service.dto.AudiencePulseResponse;
import com.aura.service.dto.AuthorTypeBreakdownResponse;
import com.aura.service.dto.CheckpointImpactResponse;
import com.aura.service.dto.CompetitorSnapshot;
import com.aura.service.dto.ContentIntentBreakdownResponse;
import com.aura.service.dto.EntityStatsResponse;
import com.aura.service.dto.PromotionalMixResponse;
import com.aura.service.dto.SentimentDeltaResponse;
import com.aura.service.dto.TopicCategoryBreakdownResponse;
import com.aura.service.entity.ManagedEntity;
import com.aura.service.repository.CommandCenterSummaryCacheRepository;
import com.aura.service.repository.ManagedEntityRepository;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers the day-over-day "vsYesterday" guard in {@link CommandCenterSummaryService#buildFacts}: the
 * sentiment-ratio delta fed to the LLM is only meaningful when both days have enough mentions to not be
 * noise (see {@code MIN_MENTIONS_FOR_DAILY_DELTA}). Regression test for GD Naidu's "Yesterday's positive
 * sentiment ratio dropped by 96.8%" highlight, which turned out to be a sparse-data artifact (a day with
 * ~1 mention swings between 0% and 100%). {@link DashboardService} is a concrete class, so per project
 * convention it's subclassed with the facts-gathering methods stubbed rather than mocked directly.
 */
class CommandCenterSummaryServiceVsYesterdayGuardTest {

    private static final Long ENTITY_ID = 29L;
    private static final String PROMPT_TEMPLATE = "[Analytics Data]";

    private StubDashboardService dashboardService;
    private LLMService llmService;
    private CommandCenterSummaryService service;

    @BeforeEach
    void setUp() {
        dashboardService = new StubDashboardService();
        llmService = mock(LLMService.class);
        when(llmService.generateReply(any())).thenReturn(
                "{\"summary\": \"s\", \"highlights\": []}");

        ManagedEntityRepository entityRepository = mock(ManagedEntityRepository.class);
        ManagedEntity entity = new ManagedEntity();
        entity.setId(ENTITY_ID);
        entity.setName("GD Naidu");
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));

        CommandCenterSummaryCacheRepository cacheRepository = mock(CommandCenterSummaryCacheRepository.class);
        when(cacheRepository.findByEntityId(anyLong())).thenReturn(Optional.empty());

        Clock clock = Clock.fixed(Instant.parse("2026-08-09T10:00:00Z"), ZoneOffset.UTC);

        service = new CommandCenterSummaryService(dashboardService, entityRepository, cacheRepository, llmService, clock);
        ReflectionTestUtils.setField(service, "llmPrompt", PROMPT_TEMPLATE);
    }

    @Test
    void vsYesterdayOmittedWhenTodayHasZeroMentions() {
        // The exact GD Naidu scenario: yesterday had a healthy positive ratio, today has collected
        // nothing yet, so toPositiveRatio defaults to 0.0 and would read as a ~100% "drop".
        dashboardService.sentimentDelta = deltaWithTotals(20, 0);

        String prompt = generatePromptSentToLlm();

        assertThat(prompt).doesNotContain("vsYesterday");
    }

    @Test
    void vsYesterdayOmittedWhenEitherDayIsBelowMinimumMentions() {
        dashboardService.sentimentDelta = deltaWithTotals(4, 10);

        String prompt = generatePromptSentToLlm();

        assertThat(prompt).doesNotContain("vsYesterday");
    }

    @Test
    void vsYesterdayIncludedWhenBothDaysMeetMinimumMentions() {
        dashboardService.sentimentDelta = deltaWithTotals(5, 5);

        String prompt = generatePromptSentToLlm();

        assertThat(prompt).contains("vsYesterday");
    }

    private String generatePromptSentToLlm() {
        service.getTodaysHighlights(ENTITY_ID, true);
        return org.mockito.Mockito.mockingDetails(llmService).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("generateReply"))
                .findFirst()
                .orElseThrow()
                .getArgument(0);
    }

    private static SentimentDeltaResponse deltaWithTotals(long fromTotal, long toTotal) {
        return SentimentDeltaResponse.builder()
                .fromDate(LocalDate.of(2026, 8, 8))
                .toDate(LocalDate.of(2026, 8, 9))
                .fromTotalMentions(fromTotal)
                .toTotalMentions(toTotal)
                .mentionsDelta(toTotal - fromTotal)
                .fromPositiveRatio(0.968)
                .toPositiveRatio(0.0)
                .positiveRatioDelta(-0.968)
                .fromNetSentiment(30.0)
                .toNetSentiment(0.0)
                .netSentimentDelta(-30.0)
                .build();
    }

    /**
     * Stubs every {@link DashboardService} method that {@code buildFacts} calls with minimal, valid
     * data, except {@link #getSentimentDelta} which each test configures directly — that's the one
     * value the guard under test actually branches on.
     */
    private static class StubDashboardService extends DashboardService {
        SentimentDeltaResponse sentimentDelta;

        StubDashboardService() {
            super(null, null, null, null, null, null);
        }

        @Override
        public EntityStatsResponse getEntityStats(Long entityId) {
            return new EntityStatsResponse(100, 0.5, 0.2, 0.3, 2.5, 0.3);
        }

        @Override
        public SentimentDeltaResponse getSentimentDelta(Long entityId, LocalDate fromDate, LocalDate toDate, int windowDays) {
            return sentimentDelta;
        }

        @Override
        public AudiencePulseResponse getAudiencePulse(Long entityId) {
            return new AudiencePulseResponse(entityId, "GD Naidu", 0, List.of());
        }

        @Override
        public PromotionalMixResponse getPromotionalMix(Long entityId) {
            return new PromotionalMixResponse(entityId, "GD Naidu", 0, 0, 0, 0.0);
        }

        @Override
        public AuthorTypeBreakdownResponse getAuthorTypeBreakdown(Long entityId) {
            return new AuthorTypeBreakdownResponse(entityId, "GD Naidu", 0, List.of());
        }

        @Override
        public ContentIntentBreakdownResponse getContentIntentBreakdown(Long entityId) {
            return new ContentIntentBreakdownResponse(entityId, "GD Naidu", 0, List.of());
        }

        @Override
        public TopicCategoryBreakdownResponse getTopicCategoryBreakdown(Long entityId) {
            return new TopicCategoryBreakdownResponse(entityId, "GD Naidu", 0, List.of());
        }

        @Override
        public CheckpointImpactResponse getCheckpointImpact(Long entityId, int windowDays) {
            return CheckpointImpactResponse.builder()
                    .entityId(entityId)
                    .entityName("GD Naidu")
                    .windowDays(windowDays)
                    .impacts(List.of())
                    .build();
        }

        @Override
        public List<CompetitorSnapshot> getCompetitorSnapshot(Long entityId) {
            return List.of();
        }
    }
}
