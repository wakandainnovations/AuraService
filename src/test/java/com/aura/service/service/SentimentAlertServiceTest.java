package com.aura.service.service;

import com.aura.service.alert.AlertDispatcher;
import com.aura.service.entity.EntityKeyword;
import com.aura.service.entity.ManagedEntity;
import com.aura.service.entity.Mention;
import com.aura.service.entity.SentimentAlert;
import com.aura.service.enums.Sentiment;
import com.aura.service.repository.ManagedEntityRepository;
import com.aura.service.repository.MentionRepository;
import com.aura.service.repository.SentimentAlertRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SentimentAlertServiceTest {

    private static final Instant NOW = Instant.parse("2026-05-21T12:00:00Z");
    private static final Long ENTITY_ID = 42L;

    private ManagedEntityRepository entityRepository;
    private MentionRepository mentionRepository;
    private SentimentAlertRepository alertRepository;
    private StubSpreaderLookup spreaderLookup;
    private AlertDispatcher alertDispatcher;
    private Clock clock;
    private SentimentAlertService service;

    @BeforeEach
    void setUp() {
        entityRepository = mock(ManagedEntityRepository.class);
        mentionRepository = mock(MentionRepository.class);
        alertRepository = mock(SentimentAlertRepository.class);
        spreaderLookup = new StubSpreaderLookup();
        alertDispatcher = new NoopDispatcher();
        clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new SentimentAlertService(
                entityRepository, mentionRepository, alertRepository, spreaderLookup, alertDispatcher, clock);

        ManagedEntity entity = new ManagedEntity();
        entity.setId(ENTITY_ID);
        when(entityRepository.findAll()).thenReturn(List.of(entity));
    }

    static class NoopDispatcher extends AlertDispatcher {
        NoopDispatcher() {
            super(null, null, null);
        }

        @Override
        public void dispatch(com.aura.service.entity.SentimentAlert alert) {
            // no-op for tests
        }
    }

    /**
     * Hand-written test double instead of a Mockito mock — the JDK in use breaks Mockito's
     * inline mock maker for non-final concrete classes.
     */
    static class StubSpreaderLookup extends TopSpreaderLookupService {
        private final java.util.Map<String, Set<String>> byKeyword = new java.util.HashMap<>();
        private final java.util.Map<String, RuntimeException> errorsByKeyword = new java.util.HashMap<>();
        private final java.util.List<String> calls = new java.util.ArrayList<>();

        StubSpreaderLookup() {
            super(null, null);
        }

        void put(String keyword, Set<String> spreaders) {
            byKeyword.put(keyword, spreaders);
        }

        void throwOnceFor(String keyword, RuntimeException ex) {
            errorsByKeyword.put(keyword, ex);
        }

        java.util.List<String> calls() {
            return calls;
        }

        @Override
        public Set<String> getSpreaders(String keyword) {
            calls.add(keyword);
            RuntimeException ex = errorsByKeyword.remove(keyword);
            if (ex != null) {
                throw ex;
            }
            return byKeyword.getOrDefault(keyword, Set.of());
        }
    }

    private void stubWindowCounts(long totalRolling, long negativeRolling,
                                  long totalBaseline, long negativeBaseline) {
        Instant rollingStart = NOW.minus(SentimentAlertService.ROLLING_WINDOW);
        Instant baselineStart = NOW.minus(SentimentAlertService.BASELINE_WINDOW);

        when(mentionRepository.countByManagedEntityIdAndPostDateBetween(
                ENTITY_ID, rollingStart, NOW)).thenReturn(totalRolling);
        when(mentionRepository.countByManagedEntityIdAndSentimentAndPostDateBetween(
                ENTITY_ID, Sentiment.NEGATIVE, rollingStart, NOW)).thenReturn(negativeRolling);

        when(mentionRepository.countByManagedEntityIdAndPostDateBetween(
                ENTITY_ID, baselineStart, NOW)).thenReturn(totalBaseline);
        when(mentionRepository.countByManagedEntityIdAndSentimentAndPostDateBetween(
                ENTITY_ID, Sentiment.NEGATIVE, baselineStart, NOW)).thenReturn(negativeBaseline);
    }

    @Test
    void createsAlertWhenRatioExceedsBaselineAndCountMeetsThreshold() {
        // current ratio = 30/60 = 0.5; baseline = 200/1000 = 0.2; 0.5 > 0.2 * 1.5 = 0.3
        stubWindowCounts(60, 30, 1000, 200);
        when(alertRepository.existsByManagedEntityIdAndStatusAndTriggeredAtAfter(
                eq(ENTITY_ID), eq(SentimentAlert.Status.OPEN), any())).thenReturn(false);

        service.scanForSpikes();

        verify(alertRepository).save(any(SentimentAlert.class));
    }

    @Test
    void persistsExpectedFields() {
        stubWindowCounts(60, 30, 1000, 200);
        when(alertRepository.existsByManagedEntityIdAndStatusAndTriggeredAtAfter(
                eq(ENTITY_ID), eq(SentimentAlert.Status.OPEN), any())).thenReturn(false);
        org.mockito.ArgumentCaptor<SentimentAlert> captor =
                org.mockito.ArgumentCaptor.forClass(SentimentAlert.class);

        service.scanForSpikes();

        verify(alertRepository).save(captor.capture());
        SentimentAlert saved = captor.getValue();
        assertThat(saved.getManagedEntityId()).isEqualTo(ENTITY_ID);
        assertThat(saved.getTriggeredAt()).isEqualTo(NOW);
        assertThat(saved.getKind()).isEqualTo(SentimentAlert.Kind.SPIKE);
        assertThat(saved.getStatus()).isEqualTo(SentimentAlert.Status.OPEN);
        assertThat(saved.getCurrentValue()).isEqualTo(0.5);
        assertThat(saved.getBaselineValue()).isEqualTo(0.2);
    }

    @Test
    void skipsWhenAbsoluteNegativeCountBelowMinimum() {
        // 9 < 10 even though ratio 9/10 dwarfs baseline
        stubWindowCounts(10, 9, 1000, 100);

        service.scanForSpikes();

        verify(alertRepository, never()).save(any());
    }

    @Test
    void skipsWhenRatioDoesNotExceedBaselineMultiplier() {
        // current ratio = 0.25; baseline 0.2 * 1.5 = 0.3
        stubWindowCounts(40, 10, 1000, 200);

        service.scanForSpikes();

        verify(alertRepository, never()).save(any());
    }

    @Test
    void skipsWhenBaselineIsZero() {
        // No baseline data -> baselineRatio=0, do not treat as infinite spike
        stubWindowCounts(60, 30, 0, 0);

        service.scanForSpikes();

        verify(alertRepository, never()).save(any());
    }

    @Test
    void skipsWhenOpenAlertExistsWithinDedupWindow() {
        stubWindowCounts(60, 30, 1000, 200);
        when(alertRepository.existsByManagedEntityIdAndStatusAndTriggeredAtAfter(
                eq(ENTITY_ID), eq(SentimentAlert.Status.OPEN), any())).thenReturn(true);

        service.scanForSpikes();

        verify(alertRepository, never()).save(any());
    }

    @Test
    void usesFixedClockForDedupWindowBoundary() {
        stubWindowCounts(60, 30, 1000, 200);
        when(alertRepository.existsByManagedEntityIdAndStatusAndTriggeredAtAfter(
                eq(ENTITY_ID), eq(SentimentAlert.Status.OPEN), any())).thenReturn(false);
        org.mockito.ArgumentCaptor<Instant> dedupCaptor = org.mockito.ArgumentCaptor.forClass(Instant.class);

        service.scanForSpikes();

        verify(alertRepository).existsByManagedEntityIdAndStatusAndTriggeredAtAfter(
                eq(ENTITY_ID), eq(SentimentAlert.Status.OPEN), dedupCaptor.capture());
        assertThat(dedupCaptor.getValue())
                .isEqualTo(NOW.minus(SentimentAlertService.DEDUP_WINDOW));
    }

    @Test
    void continuesProcessingOtherEntitiesAfterFailure() {
        ManagedEntity good = new ManagedEntity();
        good.setId(ENTITY_ID);
        ManagedEntity bad = new ManagedEntity();
        bad.setId(999L);
        when(entityRepository.findAll()).thenReturn(List.of(bad, good));

        Instant rollingStart = NOW.minus(SentimentAlertService.ROLLING_WINDOW);
        when(mentionRepository.countByManagedEntityIdAndPostDateBetween(999L, rollingStart, NOW))
                .thenThrow(new RuntimeException("boom"));
        stubWindowCounts(60, 30, 1000, 200);
        when(alertRepository.existsByManagedEntityIdAndStatusAndTriggeredAtAfter(
                eq(ENTITY_ID), eq(SentimentAlert.Status.OPEN), any())).thenReturn(false);

        service.scanForSpikes();

        verify(alertRepository).save(any(SentimentAlert.class));
    }

    // ------------------------------------------------------------------
    // INFLUENCER_NEGATIVE detector
    // ------------------------------------------------------------------

    private ManagedEntity entityWithKeywords(Long id, String... keywords) {
        ManagedEntity entity = new ManagedEntity();
        entity.setId(id);
        List<EntityKeyword> kws = new ArrayList<>();
        for (String k : keywords) {
            kws.add(new EntityKeyword(k, null, null, null, null));
        }
        entity.setKeywords(kws);
        return entity;
    }

    private Mention mention(long id, ManagedEntity entity, String author, String permalink, Sentiment sentiment) {
        Mention m = new Mention();
        m.setId(id);
        m.setManagedEntity(entity);
        m.setAuthor(author);
        m.setPermalink(permalink);
        m.setSentiment(sentiment);
        m.setPostDate(NOW);
        return m;
    }

    private void stubInitialWatermark(long initialMaxMentionId) {
        when(alertRepository.findMaxSourceMentionIdByKind(SentimentAlert.Kind.INFLUENCER_NEGATIVE))
                .thenReturn(null);
        when(mentionRepository.findMaxId()).thenReturn(initialMaxMentionId);
    }

    @Test
    void emitsInfluencerNegativeAlertWhenAuthorIsTopSpreader() {
        stubInitialWatermark(100L);
        ManagedEntity entity = entityWithKeywords(ENTITY_ID, "comedy");
        Mention m = mention(101L, entity, "alice", "https://x.com/alice/1", Sentiment.NEGATIVE);

        when(mentionRepository.findByIdGreaterThanAndSentimentOrderByIdAsc(100L, Sentiment.NEGATIVE))
                .thenReturn(List.of(m));
        spreaderLookup.put("comedy", Set.of("alice", "bob"));
        when(alertRepository.existsByKindAndSourceMentionId(
                SentimentAlert.Kind.INFLUENCER_NEGATIVE, 101L)).thenReturn(false);

        org.mockito.ArgumentCaptor<SentimentAlert> captor =
                org.mockito.ArgumentCaptor.forClass(SentimentAlert.class);

        service.scanForInfluencerNegatives();

        verify(alertRepository).save(captor.capture());
        SentimentAlert saved = captor.getValue();
        assertThat(saved.getKind()).isEqualTo(SentimentAlert.Kind.INFLUENCER_NEGATIVE);
        assertThat(saved.getManagedEntityId()).isEqualTo(ENTITY_ID);
        assertThat(saved.getStatus()).isEqualTo(SentimentAlert.Status.OPEN);
        assertThat(saved.getSourceMentionId()).isEqualTo(101L);
        assertThat(saved.getMatchedAuthor()).isEqualTo("alice");
        assertThat(saved.getPermalink()).isEqualTo("https://x.com/alice/1");
        assertThat(saved.getTriggeredAt()).isEqualTo(NOW);
    }

    @Test
    void skipsMentionWhenAuthorNotInAnyKeywordSpreaderList() {
        stubInitialWatermark(100L);
        ManagedEntity entity = entityWithKeywords(ENTITY_ID, "comedy", "drama");
        Mention m = mention(101L, entity, "carol", "https://x.com/carol/1", Sentiment.NEGATIVE);

        when(mentionRepository.findByIdGreaterThanAndSentimentOrderByIdAsc(100L, Sentiment.NEGATIVE))
                .thenReturn(List.of(m));
        spreaderLookup.put("comedy", Set.of("alice"));
        spreaderLookup.put("drama", Set.of("bob"));

        service.scanForInfluencerNegatives();

        verify(alertRepository, never()).save(any());
    }

    @Test
    void emitsAlertWhenAuthorMatchesAnyEntityKeyword() {
        stubInitialWatermark(100L);
        ManagedEntity entity = entityWithKeywords(ENTITY_ID, "comedy", "drama");
        Mention m = mention(101L, entity, "bob", "https://x.com/bob/1", Sentiment.NEGATIVE);

        when(mentionRepository.findByIdGreaterThanAndSentimentOrderByIdAsc(100L, Sentiment.NEGATIVE))
                .thenReturn(List.of(m));
        spreaderLookup.put("comedy", Set.of("alice"));
        spreaderLookup.put("drama", Set.of("bob"));

        service.scanForInfluencerNegatives();

        verify(alertRepository).save(any(SentimentAlert.class));
    }

    @Test
    void processesBulkInsertedMentionsAndAdvancesWatermark() {
        stubInitialWatermark(100L);
        ManagedEntity entity = entityWithKeywords(ENTITY_ID, "comedy");
        // A bulk-inserted batch arrives as a single result set.
        Mention m1 = mention(101L, entity, "alice", "p1", Sentiment.NEGATIVE);
        Mention m2 = mention(102L, entity, "stranger", "p2", Sentiment.NEGATIVE);
        Mention m3 = mention(103L, entity, "bob", "p3", Sentiment.NEGATIVE);

        when(mentionRepository.findByIdGreaterThanAndSentimentOrderByIdAsc(100L, Sentiment.NEGATIVE))
                .thenReturn(List.of(m1, m2, m3));
        spreaderLookup.put("comedy", Set.of("alice", "bob"));

        service.scanForInfluencerNegatives();

        verify(alertRepository, times(2)).save(any(SentimentAlert.class));

        // Second scan after the batch — watermark should now skip ids <= 103.
        when(mentionRepository.findByIdGreaterThanAndSentimentOrderByIdAsc(103L, Sentiment.NEGATIVE))
                .thenReturn(List.of());
        service.scanForInfluencerNegatives();
        verify(mentionRepository).findByIdGreaterThanAndSentimentOrderByIdAsc(103L, Sentiment.NEGATIVE);
    }

    @Test
    void skipsAlertWhenAlreadyEmittedForSameMention() {
        stubInitialWatermark(100L);
        ManagedEntity entity = entityWithKeywords(ENTITY_ID, "comedy");
        Mention m = mention(101L, entity, "alice", "p1", Sentiment.NEGATIVE);

        when(mentionRepository.findByIdGreaterThanAndSentimentOrderByIdAsc(100L, Sentiment.NEGATIVE))
                .thenReturn(List.of(m));
        spreaderLookup.put("comedy", Set.of("alice"));
        when(alertRepository.existsByKindAndSourceMentionId(
                SentimentAlert.Kind.INFLUENCER_NEGATIVE, 101L)).thenReturn(true);

        service.scanForInfluencerNegatives();

        verify(alertRepository, never()).save(any());
    }

    @Test
    void initializesWatermarkFromPersistentMaxOnFirstScan() {
        when(alertRepository.findMaxSourceMentionIdByKind(SentimentAlert.Kind.INFLUENCER_NEGATIVE))
                .thenReturn(500L);
        when(mentionRepository.findByIdGreaterThanAndSentimentOrderByIdAsc(500L, Sentiment.NEGATIVE))
                .thenReturn(List.of());

        service.scanForInfluencerNegatives();

        verify(mentionRepository).findByIdGreaterThanAndSentimentOrderByIdAsc(500L, Sentiment.NEGATIVE);
        assertThat(spreaderLookup.calls()).isEmpty();
    }

    @Test
    void skipsMentionsWithNullOrBlankAuthor() {
        stubInitialWatermark(100L);
        ManagedEntity entity = entityWithKeywords(ENTITY_ID, "comedy");
        Mention noAuthor = mention(101L, entity, null, "p1", Sentiment.NEGATIVE);
        Mention blankAuthor = mention(102L, entity, "  ", "p2", Sentiment.NEGATIVE);

        when(mentionRepository.findByIdGreaterThanAndSentimentOrderByIdAsc(100L, Sentiment.NEGATIVE))
                .thenReturn(List.of(noAuthor, blankAuthor));

        service.scanForInfluencerNegatives();

        verify(alertRepository, never()).save(any());
        assertThat(spreaderLookup.calls()).isEmpty();
    }

    @Test
    void continuesProcessingOtherMentionsAfterPerMentionFailure() {
        stubInitialWatermark(100L);
        ManagedEntity entity = entityWithKeywords(ENTITY_ID, "comedy");
        Mention bad = mention(101L, entity, "alice", "p1", Sentiment.NEGATIVE);
        Mention good = mention(102L, entity, "bob", "p2", Sentiment.NEGATIVE);

        when(mentionRepository.findByIdGreaterThanAndSentimentOrderByIdAsc(100L, Sentiment.NEGATIVE))
                .thenReturn(List.of(bad, good));
        spreaderLookup.throwOnceFor("comedy", new RuntimeException("upstream boom"));
        spreaderLookup.put("comedy", Set.of("bob"));

        service.scanForInfluencerNegatives();

        verify(alertRepository, times(1)).save(any(SentimentAlert.class));
    }
}
