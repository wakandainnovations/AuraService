package com.aura.service.service;

import com.aura.service.dto.AwarenessResponse;
import com.aura.service.dto.BuzzResponse;
import com.aura.service.dto.MovieHealthResponse;
import com.aura.service.dto.MovieSentimentResponse;
import com.aura.service.dto.ReachResponse;
import com.aura.service.dto.SentimentStats;
import com.aura.service.entity.ManagedEntity;
import com.aura.service.entity.User;
import com.aura.service.enums.Sentiment;
import com.aura.service.repository.CheckpointRepository;
import com.aura.service.repository.CrisisPlanRepository;
import com.aura.service.repository.ManagedEntityRepository;
import com.aura.service.repository.MentionRepository;
import com.aura.service.repository.ReplyDraftRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DashboardServiceCommandCenterPanelsTest {

    private static final Long ENTITY_ID = 1L;

    private MentionRepository mentionRepository;
    private ManagedEntityRepository entityRepository;
    private DashboardService service;

    @BeforeEach
    void setUp() {
        mentionRepository = mock(MentionRepository.class);
        entityRepository = mock(ManagedEntityRepository.class);
        service = new DashboardService(
                mentionRepository,
                entityRepository,
                mock(ReplyDraftRepository.class),
                mock(CrisisPlanRepository.class),
                mock(CheckpointRepository.class),
                new ImpressionsResolver(mentionRepository)
        );
    }

    private ManagedEntity entity(Long id, String name) {
        ManagedEntity e = new ManagedEntity();
        e.setId(id);
        e.setName(name);
        e.setType("MOVIE");
        return e;
    }

    private ManagedEntity entityWithOwner(Long id, String name, Long ownerId) {
        ManagedEntity e = entity(id, name);
        User owner = new User();
        owner.setId(ownerId);
        e.setOwner(owner);
        return e;
    }

    // ------------------------------------------------------------------
    // Movie Health
    // ------------------------------------------------------------------

    @Test
    void movieHealth_scoreAboveExcellentThreshold_saturatesAtFullHealthAndIsLabeledExcellent() {
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity(ENTITY_ID, "Test Movie")));
        when(mentionRepository.countByManagedEntityIdAndSentiment(ENTITY_ID, Sentiment.POSITIVE)).thenReturn(30L);
        when(mentionRepository.countByManagedEntityIdAndSentiment(ENTITY_ID, Sentiment.NEGATIVE)).thenReturn(10L);

        MovieHealthResponse response = service.getMovieHealth(ENTITY_ID);

        assertThat(response.getEntityId()).isEqualTo(ENTITY_ID);
        assertThat(response.getEntityName()).isEqualTo("Test Movie");
        assertThat(response.getNetSentimentScore()).isEqualTo(3.0);
        assertThat(response.getHealthPercentage()).isEqualTo(100.0);
        assertThat(response.getHealthLabel()).isEqualTo("Excellent");
    }

    @Test
    void movieHealth_scoreJustAboveGoodThreshold_isLabeledGoodNotExcellent() {
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity(ENTITY_ID, "Test Movie")));
        // 1.6 = 8 positive / 5 negative
        when(mentionRepository.countByManagedEntityIdAndSentiment(ENTITY_ID, Sentiment.POSITIVE)).thenReturn(8L);
        when(mentionRepository.countByManagedEntityIdAndSentiment(ENTITY_ID, Sentiment.NEGATIVE)).thenReturn(5L);

        MovieHealthResponse response = service.getMovieHealth(ENTITY_ID);

        assertThat(response.getNetSentimentScore()).isEqualTo(1.6);
        assertThat(response.getHealthPercentage()).isEqualTo(80.0);
        assertThat(response.getHealthLabel()).isEqualTo("Good");
    }

    @Test
    void movieHealth_scoreExactlyAtGoodThreshold_isNotYetLabeledGood() {
        // The spec says "above 1.5 is good" - a score of exactly 1.5 (3 positive / 2 negative) must not
        // qualify, since it isn't strictly above the threshold.
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity(ENTITY_ID, "Test Movie")));
        when(mentionRepository.countByManagedEntityIdAndSentiment(ENTITY_ID, Sentiment.POSITIVE)).thenReturn(3L);
        when(mentionRepository.countByManagedEntityIdAndSentiment(ENTITY_ID, Sentiment.NEGATIVE)).thenReturn(2L);

        MovieHealthResponse response = service.getMovieHealth(ENTITY_ID);

        assertThat(response.getNetSentimentScore()).isEqualTo(1.5);
        assertThat(response.getHealthPercentage()).isEqualTo(75.0);
        assertThat(response.getHealthLabel()).isEqualTo("Needs Improvement");
    }

    @Test
    void movieHealth_scoreBelowGoodThreshold_isLabeledNeedsImprovement() {
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity(ENTITY_ID, "Test Movie")));
        when(mentionRepository.countByManagedEntityIdAndSentiment(ENTITY_ID, Sentiment.POSITIVE)).thenReturn(1L);
        when(mentionRepository.countByManagedEntityIdAndSentiment(ENTITY_ID, Sentiment.NEGATIVE)).thenReturn(1L);

        MovieHealthResponse response = service.getMovieHealth(ENTITY_ID);

        assertThat(response.getNetSentimentScore()).isEqualTo(1.0);
        assertThat(response.getHealthPercentage()).isEqualTo(50.0);
        assertThat(response.getHealthLabel()).isEqualTo("Needs Improvement");
    }

    @Test
    void movieHealth_noNegativeMentions_scoreAndHealthFloorAtZero() {
        // Same "no negatives yet" edge case as the existing net-sentiment formula elsewhere
        // (DashboardService.getEntityStats): the ratio is defined as 0.0, not "undefined"/infinite.
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity(ENTITY_ID, "Test Movie")));
        when(mentionRepository.countByManagedEntityIdAndSentiment(ENTITY_ID, Sentiment.POSITIVE)).thenReturn(20L);
        when(mentionRepository.countByManagedEntityIdAndSentiment(ENTITY_ID, Sentiment.NEGATIVE)).thenReturn(0L);

        MovieHealthResponse response = service.getMovieHealth(ENTITY_ID);

        assertThat(response.getNetSentimentScore()).isEqualTo(0.0);
        assertThat(response.getHealthPercentage()).isEqualTo(0.0);
        assertThat(response.getHealthLabel()).isEqualTo("Needs Improvement");
    }

    // ------------------------------------------------------------------
    // Buzz
    // ------------------------------------------------------------------

    @Test
    void buzz_moreMentionsTodayThanYesterday_reportsPositiveChange() {
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity(ENTITY_ID, "Test Movie")));
        when(mentionRepository.countByManagedEntityIdAndPostDateBetween(
                eq(ENTITY_ID), any(Instant.class), any(Instant.class)))
                .thenReturn(150L)  // today
                .thenReturn(100L); // yesterday

        BuzzResponse response = service.getBuzz(ENTITY_ID);

        assertThat(response.getEntityId()).isEqualTo(ENTITY_ID);
        assertThat(response.getMentionsToday()).isEqualTo(150L);
        assertThat(response.getMentionsYesterday()).isEqualTo(100L);
        assertThat(response.getMentionsChange()).isEqualTo(50L);
        assertThat(response.getMentionsChangePct()).isEqualTo(50.0);
    }

    @Test
    void buzz_zeroMentionsYesterdayWithSomeToday_reportsHundredPercentChangeWithoutDivideByZero() {
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity(ENTITY_ID, "Test Movie")));
        when(mentionRepository.countByManagedEntityIdAndPostDateBetween(
                eq(ENTITY_ID), any(Instant.class), any(Instant.class)))
                .thenReturn(20L)
                .thenReturn(0L);

        BuzzResponse response = service.getBuzz(ENTITY_ID);

        assertThat(response.getMentionsChange()).isEqualTo(20L);
        assertThat(response.getMentionsChangePct()).isEqualTo(100.0);
    }

    @Test
    void buzz_zeroMentionsBothDays_reportsZeroChange() {
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity(ENTITY_ID, "Test Movie")));
        when(mentionRepository.countByManagedEntityIdAndPostDateBetween(
                eq(ENTITY_ID), any(Instant.class), any(Instant.class)))
                .thenReturn(0L)
                .thenReturn(0L);

        BuzzResponse response = service.getBuzz(ENTITY_ID);

        assertThat(response.getMentionsChange()).isEqualTo(0L);
        assertThat(response.getMentionsChangePct()).isEqualTo(0.0);
    }

    @Test
    void buzz_fewerMentionsTodayThanYesterday_reportsNegativeChange() {
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity(ENTITY_ID, "Test Movie")));
        when(mentionRepository.countByManagedEntityIdAndPostDateBetween(
                eq(ENTITY_ID), any(Instant.class), any(Instant.class)))
                .thenReturn(40L)
                .thenReturn(80L);

        BuzzResponse response = service.getBuzz(ENTITY_ID);

        assertThat(response.getMentionsChange()).isEqualTo(-40L);
        assertThat(response.getMentionsChangePct()).isEqualTo(-50.0);
    }

    // ------------------------------------------------------------------
    // Sentiment
    // ------------------------------------------------------------------

    @Test
    void sentiment_returnsAverageScoreAndPositiveRatioFromStats() {
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity(ENTITY_ID, "Test Movie")));
        when(mentionRepository.countByManagedEntityId(ENTITY_ID)).thenReturn(100L);
        when(mentionRepository.getSentimentStats(ENTITY_ID))
                .thenReturn(Optional.of(new SentimentStats(1.8, 0.65)));

        MovieSentimentResponse response = service.getSentiment(ENTITY_ID);

        assertThat(response.getEntityId()).isEqualTo(ENTITY_ID);
        assertThat(response.getTotalMentions()).isEqualTo(100L);
        assertThat(response.getAverageSentimentScore()).isEqualTo(1.8);
        assertThat(response.getPositiveRatio()).isEqualTo(0.65);
    }

    @Test
    void sentiment_noMentionsYet_defaultsToZeroInsteadOfThrowing() {
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity(ENTITY_ID, "Test Movie")));
        when(mentionRepository.countByManagedEntityId(ENTITY_ID)).thenReturn(0L);
        when(mentionRepository.getSentimentStats(ENTITY_ID)).thenReturn(Optional.empty());

        MovieSentimentResponse response = service.getSentiment(ENTITY_ID);

        assertThat(response.getTotalMentions()).isEqualTo(0L);
        assertThat(response.getAverageSentimentScore()).isEqualTo(0.0);
        assertThat(response.getPositiveRatio()).isEqualTo(0.0);
    }

    // ------------------------------------------------------------------
    // Reach
    // ------------------------------------------------------------------

    @Test
    void reach_returnsDistinctAuthorCountForEntity() {
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity(ENTITY_ID, "Test Movie")));
        when(mentionRepository.countDistinctAuthorsByEntityId(ENTITY_ID)).thenReturn(4321L);

        ReachResponse response = service.getReach(ENTITY_ID);

        assertThat(response.getEntityId()).isEqualTo(ENTITY_ID);
        assertThat(response.getEntityName()).isEqualTo("Test Movie");
        assertThat(response.getUniqueUsers()).isEqualTo(4321L);
    }

    // ------------------------------------------------------------------
    // Awareness
    // ------------------------------------------------------------------

    @Test
    void awareness_topThirdOfOwnersMoviesByViews_isLabeledHigh() {
        ManagedEntity target = entityWithOwner(ENTITY_ID, "Top Movie", 9L);
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(target));
        when(entityRepository.findByTypeAndOwnerId("MOVIE", 9L))
                .thenReturn(List.of(target, entity(2L, "Mid Movie"), entity(3L, "Low Movie")));
        when(mentionRepository.findTotalViewsForEntities(anyList())).thenReturn(List.of(
                new Object[]{ENTITY_ID, 900_000L},
                new Object[]{2L, 500_000L},
                new Object[]{3L, 10_000L}
        ));

        AwarenessResponse response = service.getAwareness(ENTITY_ID);

        assertThat(response.getTotalViews()).isEqualTo(900_000L);
        assertThat(response.getAwarenessLevel()).isEqualTo("High");
        assertThat(response.getComparedMovieCount()).isEqualTo(3);
    }

    @Test
    void awareness_bottomThirdOfOwnersMoviesByViews_isLabeledLow() {
        ManagedEntity target = entityWithOwner(ENTITY_ID, "Quiet Movie", 9L);
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(target));
        when(entityRepository.findByTypeAndOwnerId("MOVIE", 9L))
                .thenReturn(List.of(target, entity(2L, "Mid Movie"), entity(3L, "Loud Movie")));
        when(mentionRepository.findTotalViewsForEntities(anyList())).thenReturn(List.of(
                new Object[]{ENTITY_ID, 10_000L},
                new Object[]{2L, 500_000L},
                new Object[]{3L, 900_000L}
        ));

        AwarenessResponse response = service.getAwareness(ENTITY_ID);

        assertThat(response.getTotalViews()).isEqualTo(10_000L);
        assertThat(response.getAwarenessLevel()).isEqualTo("Low");
    }

    @Test
    void awareness_entityMissingFromViewsResult_defaultsToZeroViewsInsteadOfNpe() {
        // An entity with no X posts (or no views yet) is simply absent from findTotalViewsForEntities.
        ManagedEntity target = entityWithOwner(ENTITY_ID, "No Views Movie", 9L);
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(target));
        when(entityRepository.findByTypeAndOwnerId("MOVIE", 9L))
                .thenReturn(List.of(target, entity(2L, "Other Movie")));
        when(mentionRepository.findTotalViewsForEntities(anyList())).thenReturn(List.<Object[]>of(
                new Object[]{2L, 500_000L}
        ));

        AwarenessResponse response = service.getAwareness(ENTITY_ID);

        assertThat(response.getTotalViews()).isEqualTo(0L);
        assertThat(response.getAwarenessLevel()).isEqualTo("Low");
    }

    @Test
    void awareness_onlyOneMovieToCompareAgainst_defaultsToMediumRatherThanRanking() {
        ManagedEntity target = entityWithOwner(ENTITY_ID, "Only Movie", 9L);
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(target));
        when(entityRepository.findByTypeAndOwnerId("MOVIE", 9L)).thenReturn(List.of(target));
        when(mentionRepository.findTotalViewsForEntities(anyList())).thenReturn(List.<Object[]>of(
                new Object[]{ENTITY_ID, 5_000L}
        ));

        AwarenessResponse response = service.getAwareness(ENTITY_ID);

        assertThat(response.getAwarenessLevel()).isEqualTo("Medium");
        assertThat(response.getComparedMovieCount()).isEqualTo(1);
    }

    @Test
    void awareness_unownedEntity_comparesAgainstAllMoviesInsteadOfOwnerPortfolio() {
        ManagedEntity target = entity(ENTITY_ID, "Legacy Movie"); // no owner set
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(target));
        when(entityRepository.findByType("MOVIE"))
                .thenReturn(List.of(target, entity(2L, "Other Movie"), entity(3L, "Third Movie")));
        when(mentionRepository.findTotalViewsForEntities(anyList())).thenReturn(List.of(
                new Object[]{ENTITY_ID, 700_000L},
                new Object[]{2L, 100_000L},
                new Object[]{3L, 50_000L}
        ));

        AwarenessResponse response = service.getAwareness(ENTITY_ID);

        assertThat(response.getAwarenessLevel()).isEqualTo("High");
    }
}
