package com.aura.service.service;

import com.aura.service.dto.WhatsNewCard;
import com.aura.service.entity.AbuseReport;
import com.aura.service.entity.EntityKeyword;
import com.aura.service.entity.ManagedEntity;
import com.aura.service.entity.Mention;
import org.springframework.data.domain.PageRequest;
import com.aura.service.enums.Sentiment;
import com.aura.service.repository.AbuseReportRepository;
import com.aura.service.repository.ManagedEntityRepository;
import com.aura.service.repository.MentionRepository;
import com.aura.service.repository.UserEntityViewRepository;
import com.aura.service.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WhatsNewServiceTest {

    private static final Long USER_ID = 7L;
    private static final Long ENTITY_ID = 42L;
    private static final Instant LAST_SEEN = Instant.parse("2026-05-20T12:00:00Z");

    private MentionRepository mentionRepository;
    private ManagedEntityRepository entityRepository;
    private AbuseReportRepository abuseReportRepository;
    private UserEntityViewService viewService;
    private StubSpreaderLookup spreaderLookup;
    private WhatsNewService service;

    @BeforeEach
    void setUp() {
        mentionRepository = mock(MentionRepository.class);
        entityRepository = mock(ManagedEntityRepository.class);
        abuseReportRepository = mock(AbuseReportRepository.class);
        UserEntityViewRepository viewRepo = mock(UserEntityViewRepository.class);
        UserRepository userRepo = mock(UserRepository.class);
        viewService = new UserEntityViewService(viewRepo, userRepo,
                Clock.fixed(Instant.parse("2026-05-23T00:00:00Z"), ZoneOffset.UTC));
        spreaderLookup = new StubSpreaderLookup();
        // Deterministic seed so the shuffles inside the service are repeatable.
        service = new WhatsNewService(mentionRepository, entityRepository, viewService, spreaderLookup,
                userRepo, abuseReportRepository, new Random(0L));

        when(viewRepo.findLastSeen(USER_ID, ENTITY_ID)).thenReturn(Optional.of(LAST_SEEN));
    }

    static class StubSpreaderLookup extends TopSpreaderLookupService {
        private final java.util.Map<String, Set<String>> byKeyword = new java.util.HashMap<>();

        StubSpreaderLookup() {
            super(null, null);
        }

        void put(String keyword, Set<String> spreaders) {
            byKeyword.put(keyword, spreaders);
        }

        @Override
        public Set<String> getSpreaders(String keyword) {
            return byKeyword.getOrDefault(keyword, Set.of());
        }
    }

    private ManagedEntity entityWith(String... keywords) {
        ManagedEntity entity = new ManagedEntity();
        entity.setId(ENTITY_ID);
        entity.setName("Primary");
        List<EntityKeyword> kws = new ArrayList<>();
        for (String k : keywords) {
            kws.add(new EntityKeyword(k, null, null, null, null, null));
        }
        entity.setKeywords(kws);
        entity.setCompetitors(new ArrayList<>());
        return entity;
    }

    private void stubScoreCounts(Long entityId,
                                 long currentPositive, long currentNegative,
                                 long pastPositive, long pastNegative) {
        when(mentionRepository.countByManagedEntityIdAndSentiment(entityId, Sentiment.POSITIVE))
                .thenReturn(currentPositive);
        when(mentionRepository.countByManagedEntityIdAndSentiment(entityId, Sentiment.NEGATIVE))
                .thenReturn(currentNegative);
        when(mentionRepository.countByManagedEntityIdAndSentimentAndPostDateLessThanEqual(
                entityId, Sentiment.POSITIVE, LAST_SEEN)).thenReturn(pastPositive);
        when(mentionRepository.countByManagedEntityIdAndSentimentAndPostDateLessThanEqual(
                entityId, Sentiment.NEGATIVE, LAST_SEEN)).thenReturn(pastNegative);
    }

    private static Mention mention(long id) {
        Mention m = new Mention();
        m.setId(id);
        return m;
    }

    private void stubNoNewSpreaders(ManagedEntity entity) {
        when(mentionRepository.findDistinctAuthorsByEntityIdAndPostDateAfter(entity.getId(), LAST_SEEN))
                .thenReturn(List.of());
    }

    private void stubNoNegativeSpike() {
        when(mentionRepository.countByManagedEntityIdAndSentimentAndPostDateAfter(
                ENTITY_ID, Sentiment.NEGATIVE, LAST_SEEN)).thenReturn(0L);
    }

    @Test
    void returnsEmptyWhenUserHasNeverVisited() {
        UserEntityViewRepository emptyViewRepo = mock(UserEntityViewRepository.class);
        when(emptyViewRepo.findLastSeen(USER_ID, ENTITY_ID)).thenReturn(Optional.empty());
        WhatsNewService firstVisit = new WhatsNewService(
                mentionRepository, entityRepository,
                new UserEntityViewService(emptyViewRepo, mock(UserRepository.class),
                        Clock.fixed(Instant.now(), ZoneOffset.UTC)),
                spreaderLookup, mock(UserRepository.class), abuseReportRepository, new Random(0L));

        assertThat(firstVisit.getCards(USER_ID, ENTITY_ID)).isEmpty();
    }

    @Test
    void returnsEmptyWhenInputsAreNull() {
        assertThat(service.getCards((Long) null, ENTITY_ID)).isEmpty();
        assertThat(service.getCards(USER_ID, null)).isEmpty();
    }

    @Test
    void returnsEmptyWhenEntityMissing() {
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.empty());
        assertThat(service.getCards(USER_ID, ENTITY_ID)).isEmpty();
    }

    @Test
    void emitsCompetitorDropCardWhenCompetitorFallsBeyondThreshold() {
        ManagedEntity competitor = new ManagedEntity();
        competitor.setId(101L);
        competitor.setName("CompA");

        ManagedEntity entity = entityWith();
        entity.setCompetitors(List.of(competitor));
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));

        stubScoreCounts(ENTITY_ID, 0, 0, 0, 0);
        // CompA: current 1/1 = 1.0; past 10/2 = 5.0; delta = -4.0 (well past -0.05)
        stubScoreCounts(101L, 1, 1, 10, 2);

        stubNoNewSpreaders(entity);
        stubNoNegativeSpike();
        when(mentionRepository.findTop3ByManagedEntityIdAndSentimentAndPostDateAfter(101L, Sentiment.NEGATIVE, LAST_SEEN, PageRequest.of(0, 3)))
                .thenReturn(List.of(mention(901L), mention(902L)));

        List<WhatsNewCard> cards = service.getCards(USER_ID, ENTITY_ID);

        assertThat(cards).hasSize(1);
        WhatsNewCard card = cards.get(0);
        assertThat(card.getKind()).isEqualTo(WhatsNewService.KIND_COMPETITOR_DROP);
        assertThat(card.getValue()).isEqualTo(-4.0);
        assertThat(card.getHeadline()).contains("CompA");
        assertThat(card.getEvidenceMentionIds()).containsExactly(901L, 902L);
    }

    @Test
    void doesNotEmitCompetitorDropCardWhenDropTooSmall() {
        ManagedEntity competitor = new ManagedEntity();
        competitor.setId(101L);
        competitor.setName("CompA");

        ManagedEntity entity = entityWith();
        entity.setCompetitors(List.of(competitor));
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));

        stubScoreCounts(ENTITY_ID, 0, 0, 0, 0);
        // CompA: current 10/3 = 3.333..; past 10/2 = 5.0; delta ~ -1.667 — that's a big drop;
        // pick a smaller one: current 10/2 = 5.0, past 11/2 = 5.5; delta = -0.5 → still > threshold.
        // Use current 10/2=5.0, past 102/20=5.1; delta = -0.1 — fires.
        // Need < 0.05: current 100/20=5.0, past 1010/200=5.05; delta = -0.05 — boundary; not fire.
        stubScoreCounts(101L, 100, 20, 1010, 200);

        stubNoNewSpreaders(entity);
        stubNoNegativeSpike();

        assertThat(service.getCards(USER_ID, ENTITY_ID)).isEmpty();
    }

    @Test
    void emitsNewPositiveSuperSpreaderCard() {
        ManagedEntity entity = entityWith("comedy");
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));

        stubScoreCounts(ENTITY_ID, 0, 0, 0, 0);
        when(mentionRepository.findDistinctAuthorsByEntityIdAndPostDateAfter(ENTITY_ID, LAST_SEEN))
                .thenReturn(List.of("alice", "bob"));
        when(mentionRepository.findDistinctAuthorsByEntityIdAndPostDateLessThanEqual(ENTITY_ID, LAST_SEEN))
                .thenReturn(List.of("bob"));
        spreaderLookup.put("comedy", Set.of("alice"));

        when(mentionRepository.findTop3ByManagedEntityIdAndAuthorAndSentimentAndPostDateAfter(ENTITY_ID, "alice", Sentiment.POSITIVE, LAST_SEEN, PageRequest.of(0, 3)))
                .thenReturn(List.of(mention(501L), mention(502L)));

        stubNoNegativeSpike();

        List<WhatsNewCard> cards = service.getCards(USER_ID, ENTITY_ID);

        assertThat(cards).hasSize(1);
        WhatsNewCard card = cards.get(0);
        assertThat(card.getKind()).isEqualTo(WhatsNewService.KIND_NEW_POSITIVE_SUPER_SPREADER);
        assertThat(card.getHeadline()).contains("alice");
        assertThat(card.getValue()).isEqualTo(2.0);
        assertThat(card.getEvidenceMentionIds()).containsExactly(501L, 502L);
    }

    @Test
    void emitsSentimentRiseCardWhenScoreClimbsBeyondThreshold() {
        ManagedEntity entity = entityWith();
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));

        // current 80/20 = 4.0; past 30/10 = 3.0; delta = +1.0 (> 0.05)
        stubScoreCounts(ENTITY_ID, 80, 20, 30, 10);
        stubNoNewSpreaders(entity);
        stubNoNegativeSpike();
        when(mentionRepository.findTop3ByManagedEntityIdAndSentimentAndPostDateAfter(ENTITY_ID, Sentiment.POSITIVE, LAST_SEEN, PageRequest.of(0, 3)))
                .thenReturn(List.of(mention(701L), mention(702L), mention(703L)));

        List<WhatsNewCard> cards = service.getCards(USER_ID, ENTITY_ID);

        assertThat(cards).hasSize(1);
        WhatsNewCard card = cards.get(0);
        assertThat(card.getKind()).isEqualTo(WhatsNewService.KIND_SENTIMENT_RISE);
        assertThat(card.getValue()).isEqualTo(1.0);
        assertThat(card.getEvidenceMentionIds()).containsExactly(701L, 702L, 703L);
    }

    @Test
    void emitsNegativeSpikeCardWhenAboveThreshold() {
        ManagedEntity entity = entityWith();
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));

        stubScoreCounts(ENTITY_ID, 0, 0, 0, 0);
        stubNoNewSpreaders(entity);
        when(mentionRepository.countByManagedEntityIdAndSentimentAndPostDateAfter(
                ENTITY_ID, Sentiment.NEGATIVE, LAST_SEEN)).thenReturn(8L);
        when(mentionRepository.findTop3ByManagedEntityIdAndSentimentAndPostDateAfter(ENTITY_ID, Sentiment.NEGATIVE, LAST_SEEN, PageRequest.of(0, 3)))
                .thenReturn(List.of(mention(801L), mention(802L)));

        List<WhatsNewCard> cards = service.getCards(USER_ID, ENTITY_ID);

        assertThat(cards).hasSize(1);
        WhatsNewCard card = cards.get(0);
        assertThat(card.getKind()).isEqualTo(WhatsNewService.KIND_NEGATIVE_SPIKE);
        assertThat(card.getValue()).isEqualTo(8.0);
        assertThat(card.getEvidenceMentionIds()).containsExactly(801L, 802L);
    }

    @Test
    void emitsRewardCardForUpheldAbuseReportAtTopPriority() {
        ManagedEntity entity = entityWith();
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));

        // A qualifying sentiment-rise card exists too; the personal reward must still come first.
        stubScoreCounts(ENTITY_ID, 80, 20, 30, 10);
        stubNoNewSpreaders(entity);
        when(mentionRepository.countByManagedEntityIdAndSentimentAndPostDateAfter(
                ENTITY_ID, Sentiment.NEGATIVE, LAST_SEEN)).thenReturn(0L);
        when(mentionRepository.findTop3ByManagedEntityIdAndSentimentAndPostDateAfter(ENTITY_ID, Sentiment.POSITIVE, LAST_SEEN, PageRequest.of(0, 3))).thenReturn(List.of(mention(701L)));

        AbuseReport upheld = AbuseReport.builder()
                .id(900L).mentionId(555L).userId(USER_ID)
                .status(AbuseReport.Status.UPHELD).build();
        when(abuseReportRepository.findResolvedForUserAndEntitySince(
                USER_ID, ENTITY_ID, AbuseReport.Status.UPHELD, LAST_SEEN))
                .thenReturn(List.of(upheld));
        Mention reported = mention(555L);
        reported.setAuthor("troll_account");
        when(mentionRepository.findAllById(any())).thenReturn(List.of(reported));

        List<WhatsNewCard> cards = service.getCards(USER_ID, ENTITY_ID);

        WhatsNewCard reward = cards.get(0);
        assertThat(reward.getKind()).isEqualTo(WhatsNewService.KIND_ABUSE_REPORT_UPHELD);
        assertThat(reward.getHeadline())
                .isEqualTo("Your report on @troll_account was upheld — post removed.");
        assertThat(reward.getValue()).isNull();
        assertThat(reward.getEvidenceMentionIds()).containsExactly(555L);
    }

    @Test
    void respectsPriorityOrderingAndCapsAtFive() {
        // Build many competitor drops to overflow the cap and verify priority + cap.
        List<ManagedEntity> competitors = new ArrayList<>();
        for (long id = 101; id <= 107; id++) {
            ManagedEntity c = new ManagedEntity();
            c.setId(id);
            c.setName("Comp" + id);
            competitors.add(c);
            // Each competitor drops sharply.
            stubScoreCounts(id, 0, 1, 100, 1);
            when(mentionRepository.findTop3ByManagedEntityIdAndSentimentAndPostDateAfter(id, Sentiment.NEGATIVE, LAST_SEEN, PageRequest.of(0, 3))).thenReturn(List.of());
        }
        ManagedEntity entity = entityWith();
        entity.setCompetitors(competitors);
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));

        // Sentiment rise also qualifies; should still be deprioritized below competitor drops.
        stubScoreCounts(ENTITY_ID, 80, 20, 30, 10);
        stubNoNewSpreaders(entity);
        when(mentionRepository.countByManagedEntityIdAndSentimentAndPostDateAfter(
                ENTITY_ID, Sentiment.NEGATIVE, LAST_SEEN)).thenReturn(20L);

        List<WhatsNewCard> cards = service.getCards(USER_ID, ENTITY_ID);

        assertThat(cards).hasSize(WhatsNewService.MAX_CARDS);
        // All 5 cards should be from the highest-priority tier (competitor drop).
        assertThat(cards).allMatch(c -> WhatsNewService.KIND_COMPETITOR_DROP.equals(c.getKind()));
    }

    @Test
    void tieBreakingShufflesWithinAPriorityTier() {
        ManagedEntity compA = new ManagedEntity();
        compA.setId(101L);
        compA.setName("CompA");
        ManagedEntity compB = new ManagedEntity();
        compB.setId(102L);
        compB.setName("CompB");

        ManagedEntity entity = entityWith();
        entity.setCompetitors(List.of(compA, compB));
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));

        stubScoreCounts(ENTITY_ID, 0, 0, 0, 0);
        stubScoreCounts(101L, 0, 1, 100, 1);
        stubScoreCounts(102L, 0, 1, 100, 1);
        stubNoNewSpreaders(entity);
        stubNoNegativeSpike();
        when(mentionRepository.findTop3ByManagedEntityIdAndSentimentAndPostDateAfter(101L, Sentiment.NEGATIVE, LAST_SEEN, PageRequest.of(0, 3))).thenReturn(List.of());
        when(mentionRepository.findTop3ByManagedEntityIdAndSentimentAndPostDateAfter(102L, Sentiment.NEGATIVE, LAST_SEEN, PageRequest.of(0, 3))).thenReturn(List.of());

        // Collections.shuffle on a 2-element list calls nextInt(2) and swaps index 1 with that
        // result. nextInt(2)=0 reverses; nextInt(2)=1 leaves order alone. Drive both branches
        // directly so the test does not depend on any particular Random seed's bit pattern.
        Random alwaysSwap = new Random() {
            @Override public int nextInt(int bound) { return 0; }
        };
        Random neverSwap = new Random() {
            @Override public int nextInt(int bound) { return bound - 1; }
        };

        WhatsNewService swapped = new WhatsNewService(mentionRepository, entityRepository,
                viewService, spreaderLookup, mock(UserRepository.class), abuseReportRepository, alwaysSwap);
        WhatsNewService unswapped = new WhatsNewService(mentionRepository, entityRepository,
                viewService, spreaderLookup, mock(UserRepository.class), abuseReportRepository, neverSwap);

        List<WhatsNewCard> swappedCards = swapped.getCards(USER_ID, ENTITY_ID);
        List<WhatsNewCard> unswappedCards = unswapped.getCards(USER_ID, ENTITY_ID);

        assertThat(swappedCards).hasSize(2);
        assertThat(unswappedCards).hasSize(2);
        assertThat(swappedCards.get(0).getHeadline()).contains("CompB");
        assertThat(unswappedCards.get(0).getHeadline()).contains("CompA");
    }
}
