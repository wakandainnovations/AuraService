package com.aura.service.service;

import com.aura.service.dto.WhatsChangedResponse;
import com.aura.service.entity.EntityKeyword;
import com.aura.service.entity.ManagedEntity;
import com.aura.service.enums.Sentiment;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WhatsChangedServiceTest {

    private static final Long USER_ID = 7L;
    private static final Long ENTITY_ID = 42L;
    private static final Instant LAST_SEEN = Instant.parse("2026-05-20T12:00:00Z");

    private MentionRepository mentionRepository;
    private ManagedEntityRepository entityRepository;
    private UserEntityViewService viewService;
    private StubSpreaderLookup spreaderLookup;
    private WhatsChangedService service;

    @BeforeEach
    void setUp() {
        mentionRepository = mock(MentionRepository.class);
        entityRepository = mock(ManagedEntityRepository.class);
        UserEntityViewRepository viewRepo = mock(UserEntityViewRepository.class);
        UserRepository userRepo = mock(UserRepository.class);
        viewService = new UserEntityViewService(viewRepo, userRepo,
                Clock.fixed(Instant.parse("2026-05-23T00:00:00Z"), ZoneOffset.UTC));
        spreaderLookup = new StubSpreaderLookup();
        service = new WhatsChangedService(mentionRepository, entityRepository, viewService, spreaderLookup, userRepo);

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

    @Test
    void returnsAllNullFieldsWhenUserHasNeverVisited() {
        UserEntityViewRepository emptyViewRepo = mock(UserEntityViewRepository.class);
        when(emptyViewRepo.findLastSeen(USER_ID, ENTITY_ID)).thenReturn(Optional.empty());
        WhatsChangedService firstVisitService = new WhatsChangedService(
                mentionRepository, entityRepository,
                new UserEntityViewService(emptyViewRepo, mock(UserRepository.class),
                        Clock.fixed(Instant.now(), ZoneOffset.UTC)),
                spreaderLookup, mock(UserRepository.class));

        WhatsChangedResponse out = firstVisitService.computeDelta(USER_ID, ENTITY_ID);

        assertThat(out.getSentimentScoreDelta()).isNull();
        assertThat(out.getNewMentionsCount()).isNull();
        assertThat(out.getNewNegativeCount()).isNull();
        assertThat(out.getNewSuperSpreaderCount()).isNull();
        assertThat(out.getCompetitorDelta()).isNull();
    }

    @Test
    void returnsAllNullFieldsWhenInputsAreNull() {
        WhatsChangedResponse out = service.computeDelta((Long) null, ENTITY_ID);
        assertThat(out.getSentimentScoreDelta()).isNull();
        assertThat(out.getCompetitorDelta()).isNull();

        out = service.computeDelta(USER_ID, null);
        assertThat(out.getSentimentScoreDelta()).isNull();
        assertThat(out.getCompetitorDelta()).isNull();
    }

    @Test
    void returnsAllNullFieldsWhenEntityMissing() {
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.empty());
        WhatsChangedResponse out = service.computeDelta(USER_ID, ENTITY_ID);
        assertThat(out.getSentimentScoreDelta()).isNull();
        assertThat(out.getCompetitorDelta()).isNull();
    }

    @Test
    void computesScoreDeltaAndCounts() {
        ManagedEntity entity = entityWith();
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));

        // current: 80 positive / 20 negative = 4.0
        // past:    30 positive / 10 negative = 3.0
        // delta = 1.0
        stubScoreCounts(ENTITY_ID, 80, 20, 30, 10);
        when(mentionRepository.countByManagedEntityIdAndPostDateAfter(ENTITY_ID, LAST_SEEN))
                .thenReturn(60L);
        when(mentionRepository.countByManagedEntityIdAndSentimentAndPostDateAfter(
                ENTITY_ID, Sentiment.NEGATIVE, LAST_SEEN)).thenReturn(10L);
        when(mentionRepository.findDistinctAuthorsByEntityIdAndPostDateAfter(ENTITY_ID, LAST_SEEN))
                .thenReturn(List.of());

        WhatsChangedResponse out = service.computeDelta(USER_ID, ENTITY_ID);

        assertThat(out.getSentimentScoreDelta()).isCloseTo(1.0, within(1e-9));
        assertThat(out.getNewMentionsCount()).isEqualTo(60L);
        assertThat(out.getNewNegativeCount()).isEqualTo(10L);
        assertThat(out.getNewSuperSpreaderCount()).isEqualTo(0L);
        assertThat(out.getCompetitorDelta()).isEmpty();
    }

    @Test
    void scoreDeltaTreatsZeroNegativeAsZeroScoreLikeDashboard() {
        ManagedEntity entity = entityWith();
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));

        // current negative = 0 -> score 0; past negative = 5, past positive 10 -> 2.0
        // delta = 0 - 2 = -2
        stubScoreCounts(ENTITY_ID, 50, 0, 10, 5);
        when(mentionRepository.countByManagedEntityIdAndPostDateAfter(ENTITY_ID, LAST_SEEN))
                .thenReturn(0L);
        when(mentionRepository.countByManagedEntityIdAndSentimentAndPostDateAfter(
                ENTITY_ID, Sentiment.NEGATIVE, LAST_SEEN)).thenReturn(0L);
        when(mentionRepository.findDistinctAuthorsByEntityIdAndPostDateAfter(ENTITY_ID, LAST_SEEN))
                .thenReturn(List.of());

        WhatsChangedResponse out = service.computeDelta(USER_ID, ENTITY_ID);

        assertThat(out.getSentimentScoreDelta()).isCloseTo(-2.0, within(1e-9));
    }

    @Test
    void newSuperSpreaderCountsOnlyAuthorsThatAreNewAndInSpreaderList() {
        ManagedEntity entity = entityWith("comedy", "drama");
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));
        stubScoreCounts(ENTITY_ID, 0, 0, 0, 0);
        when(mentionRepository.countByManagedEntityIdAndPostDateAfter(ENTITY_ID, LAST_SEEN))
                .thenReturn(5L);
        when(mentionRepository.countByManagedEntityIdAndSentimentAndPostDateAfter(
                ENTITY_ID, Sentiment.NEGATIVE, LAST_SEEN)).thenReturn(1L);

        // alice and bob are top spreaders. dan is not.
        // bob already posted prior to lastSeen, so even though he's a spreader, he's not "new".
        // carol is new but not a spreader.
        when(mentionRepository.findDistinctAuthorsByEntityIdAndPostDateAfter(ENTITY_ID, LAST_SEEN))
                .thenReturn(List.of("alice", "bob", "carol", "dan"));
        when(mentionRepository.findDistinctAuthorsByEntityIdAndPostDateLessThanEqual(ENTITY_ID, LAST_SEEN))
                .thenReturn(List.of("bob", "edgar"));
        spreaderLookup.put("comedy", Set.of("alice"));
        spreaderLookup.put("drama", Set.of("bob"));

        WhatsChangedResponse out = service.computeDelta(USER_ID, ENTITY_ID);

        // alice is the only NEW author who is also a spreader.
        assertThat(out.getNewSuperSpreaderCount()).isEqualTo(1L);
    }

    @Test
    void newSuperSpreaderCountIsZeroWhenEntityHasNoKeywords() {
        ManagedEntity entity = entityWith();
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));
        stubScoreCounts(ENTITY_ID, 0, 0, 0, 0);
        when(mentionRepository.countByManagedEntityIdAndPostDateAfter(eq(ENTITY_ID), eq(LAST_SEEN)))
                .thenReturn(2L);
        when(mentionRepository.countByManagedEntityIdAndSentimentAndPostDateAfter(
                eq(ENTITY_ID), eq(Sentiment.NEGATIVE), eq(LAST_SEEN))).thenReturn(0L);
        when(mentionRepository.findDistinctAuthorsByEntityIdAndPostDateAfter(ENTITY_ID, LAST_SEEN))
                .thenReturn(List.of("alice"));

        WhatsChangedResponse out = service.computeDelta(USER_ID, ENTITY_ID);

        assertThat(out.getNewSuperSpreaderCount()).isEqualTo(0L);
    }

    @Test
    void competitorDeltaIncludesEachCompetitorByName() {
        ManagedEntity competitorA = new ManagedEntity();
        competitorA.setId(101L);
        competitorA.setName("CompA");

        ManagedEntity competitorB = new ManagedEntity();
        competitorB.setId(102L);
        competitorB.setName("CompB");

        ManagedEntity entity = entityWith();
        entity.setCompetitors(List.of(competitorA, competitorB));
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));

        stubScoreCounts(ENTITY_ID, 0, 0, 0, 0);
        when(mentionRepository.countByManagedEntityIdAndPostDateAfter(ENTITY_ID, LAST_SEEN))
                .thenReturn(0L);
        when(mentionRepository.countByManagedEntityIdAndSentimentAndPostDateAfter(
                ENTITY_ID, Sentiment.NEGATIVE, LAST_SEEN)).thenReturn(0L);
        when(mentionRepository.findDistinctAuthorsByEntityIdAndPostDateAfter(ENTITY_ID, LAST_SEEN))
                .thenReturn(List.of());

        // CompA: current 10/2 = 5.0; past 6/2 = 3.0; delta = 2.0
        stubScoreCounts(101L, 10, 2, 6, 2);
        // CompB: current 4/4 = 1.0; past 8/2 = 4.0; delta = -3.0
        stubScoreCounts(102L, 4, 4, 8, 2);

        WhatsChangedResponse out = service.computeDelta(USER_ID, ENTITY_ID);

        assertThat(out.getCompetitorDelta())
                .containsEntry("CompA", 2.0)
                .containsEntry("CompB", -3.0)
                .hasSize(2);
    }
}
