package com.aura.service.service;

import com.aura.service.dto.CheckpointMarker;
import com.aura.service.dto.EntitySentimentData;
import com.aura.service.dto.SentimentOverTimeResponse;
import com.aura.service.entity.Checkpoint;
import com.aura.service.entity.ManagedEntity;
import com.aura.service.entity.Mention;
import com.aura.service.enums.Platform;
import com.aura.service.enums.Sentiment;
import com.aura.service.enums.TimePeriod;
import com.aura.service.repository.CheckpointRepository;
import com.aura.service.repository.CrisisPlanRepository;
import com.aura.service.repository.ManagedEntityRepository;
import com.aura.service.repository.MentionRepository;
import com.aura.service.repository.ReplyDraftRepository;
import com.aura.service.service.ImpressionsResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DashboardServiceSentimentOverTimeTest {

    private static final Long ENTITY_ID = 1L;

    private MentionRepository mentionRepository;
    private ManagedEntityRepository entityRepository;
    private CheckpointRepository checkpointRepository;
    private DashboardService service;

    @BeforeEach
    void setUp() {
        mentionRepository = mock(MentionRepository.class);
        entityRepository = mock(ManagedEntityRepository.class);
        checkpointRepository = mock(CheckpointRepository.class);
        service = new DashboardService(
                mentionRepository,
                entityRepository,
                mock(ReplyDraftRepository.class),
                mock(CrisisPlanRepository.class),
                checkpointRepository,
                new ImpressionsResolver(mentionRepository)
        );
    }

    private ManagedEntity entity(Long id, String name) {
        ManagedEntity e = new ManagedEntity();
        e.setId(id);
        e.setName(name);
        return e;
    }

    private Checkpoint checkpoint(Long entityId, LocalDate date, String description) {
        ManagedEntity e = entity(entityId, "entity");
        return Checkpoint.builder()
                .id(1L)
                .managedEntity(e)
                .checkpointDate(date)
                .description(description)
                .build();
    }

    private Mention mention(Long entityId, String entityName, Instant postDate, Sentiment sentiment) {
        ManagedEntity e = entity(entityId, entityName);
        Mention m = new Mention();
        m.setId(1L);
        m.addManagedEntity(e);
        m.setPlatform(Platform.REDDIT);
        m.setPostId("post-" + postDate.toString());
        m.setContent("test");
        m.setPostDate(postDate);
        m.setSentiment(sentiment);
        return m;
    }

    @Test
    void sentimentOverTimeIncludesCheckpointMarkers() {
        ManagedEntity e = entity(ENTITY_ID, "TestEntity");
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(e));

        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        LocalDate threeDaysAgo = today.minusDays(3);

        Checkpoint cp = checkpoint(ENTITY_ID, threeDaysAgo, "Launch Day");

        when(mentionRepository.findByEntityIdsAndDateRange(
                eq(Collections.singletonList(ENTITY_ID)), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of());

        when(checkpointRepository.findByManagedEntityIdAndCheckpointDateBetweenOrderByCheckpointDateAsc(
                eq(ENTITY_ID), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(cp));

        SentimentOverTimeResponse response = service.getSentimentOverTime(
                TimePeriod.DAY, List.of(ENTITY_ID));

        EntitySentimentData data = response.getEntities().get(0);
        assertThat(data.getCheckpoints()).hasSize(1);

        CheckpointMarker marker = data.getCheckpoints().get(0);
        assertThat(marker.getDescription()).isEqualTo("Launch Day");
        assertThat(marker.getDate()).isEqualTo(threeDaysAgo.format(
                DateTimeFormatter.ofPattern("yyyy-MM-dd")));
    }

    @Test
    void checkpointsOutsideTimeRangeAreExcluded() {
        ManagedEntity e = entity(ENTITY_ID, "TestEntity");
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(e));

        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        LocalDate withinRange = today.minusDays(2);
        LocalDate outsideRange = today.minusDays(60);

        Checkpoint inRange = checkpoint(ENTITY_ID, withinRange, "In Range");
        Checkpoint outRange = checkpoint(ENTITY_ID, outsideRange, "Out Range");

        when(mentionRepository.findByEntityIdsAndDateRange(
                eq(Collections.singletonList(ENTITY_ID)), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of());

        when(checkpointRepository.findByManagedEntityIdAndCheckpointDateBetweenOrderByCheckpointDateAsc(
                eq(ENTITY_ID), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(inRange, outRange));

        SentimentOverTimeResponse response = service.getSentimentOverTime(
                TimePeriod.DAY, List.of(ENTITY_ID));

        EntitySentimentData data = response.getEntities().get(0);
        assertThat(data.getCheckpoints()).hasSize(1);
        assertThat(data.getCheckpoints().get(0).getDescription()).isEqualTo("In Range");
    }

    @Test
    void checkpointDateFormatMatchesTimeSeriesForDayPeriod() {
        ManagedEntity e = entity(ENTITY_ID, "TestEntity");
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(e));

        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        LocalDate threeDaysAgo = today.minusDays(3);
        Instant threeDaysAgoInstant = threeDaysAgo.atStartOfDay(ZoneId.systemDefault()).toInstant();

        Checkpoint cp = checkpoint(ENTITY_ID, threeDaysAgo, "Release");
        Mention m = mention(ENTITY_ID, "TestEntity", threeDaysAgoInstant, Sentiment.POSITIVE);

        when(mentionRepository.findByEntityIdsAndDateRange(
                eq(Collections.singletonList(ENTITY_ID)), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of(m));

        when(checkpointRepository.findByManagedEntityIdAndCheckpointDateBetweenOrderByCheckpointDateAsc(
                eq(ENTITY_ID), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(cp));

        SentimentOverTimeResponse response = service.getSentimentOverTime(
                TimePeriod.DAY, List.of(ENTITY_ID));

        EntitySentimentData data = response.getEntities().get(0);
        String checkpointDate = data.getCheckpoints().get(0).getDate();
        List<String> timeSeriesDates = data.getSentiments().stream()
                .map(ts -> ts.getDate())
                .toList();

        assertThat(timeSeriesDates).contains(checkpointDate);
    }

    @Test
    void checkpointDateFormatMatchesTimeSeriesForMonthPeriod() {
        ManagedEntity e = entity(ENTITY_ID, "TestEntity");
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(e));

        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        LocalDate twoMonthsAgo = today.minusMonths(2);
        Instant twoMonthsAgoInstant = twoMonthsAgo.atStartOfDay(ZoneId.systemDefault()).toInstant();

        Checkpoint cp = checkpoint(ENTITY_ID, twoMonthsAgo, "Milestone");
        Mention m = mention(ENTITY_ID, "TestEntity", twoMonthsAgoInstant, Sentiment.NEGATIVE);

        when(mentionRepository.findByEntityIdsAndDateRange(
                eq(Collections.singletonList(ENTITY_ID)), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of(m));

        when(checkpointRepository.findByManagedEntityIdAndCheckpointDateBetweenOrderByCheckpointDateAsc(
                eq(ENTITY_ID), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(cp));

        SentimentOverTimeResponse response = service.getSentimentOverTime(
                TimePeriod.MONTH, List.of(ENTITY_ID));

        EntitySentimentData data = response.getEntities().get(0);
        String checkpointDate = data.getCheckpoints().get(0).getDate();

        assertThat(checkpointDate).isEqualTo(twoMonthsAgo.format(
                DateTimeFormatter.ofPattern("yyyy-MM")));

        List<String> timeSeriesDates = data.getSentiments().stream()
                .map(ts -> ts.getDate())
                .toList();
        assertThat(timeSeriesDates).contains(checkpointDate);
    }

    @Test
    void day90PeriodProducesDailyBucketsSpanning90Days() {
        ManagedEntity e = entity(ENTITY_ID, "TestEntity");
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(e));

        when(mentionRepository.findByEntityIdsAndDateRange(
                eq(Collections.singletonList(ENTITY_ID)), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of());

        when(checkpointRepository.findByManagedEntityIdAndCheckpointDateBetweenOrderByCheckpointDateAsc(
                eq(ENTITY_ID), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of());

        SentimentOverTimeResponse response = service.getSentimentOverTime(
                TimePeriod.DAY90, List.of(ENTITY_ID));

        EntitySentimentData data = response.getEntities().get(0);
        List<String> timeSeriesDates = data.getSentiments().stream()
                .map(ts -> ts.getDate())
                .toList();

        // 90-day window, daily buckets -> 91 days inclusive of both endpoints
        assertThat(timeSeriesDates).hasSize(91);

        DateTimeFormatter dayFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        assertThat(timeSeriesDates).contains(today.format(dayFormatter));
        assertThat(timeSeriesDates).contains(today.minusDays(90).format(dayFormatter));
    }

    @Test
    void checkpoint60DaysAgoIncludedForDay90Period() {
        ManagedEntity e = entity(ENTITY_ID, "TestEntity");
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(e));

        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        LocalDate sixtyDaysAgo = today.minusDays(60);

        Checkpoint cp = checkpoint(ENTITY_ID, sixtyDaysAgo, "Quarterly Review");

        when(mentionRepository.findByEntityIdsAndDateRange(
                eq(Collections.singletonList(ENTITY_ID)), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of());

        when(checkpointRepository.findByManagedEntityIdAndCheckpointDateBetweenOrderByCheckpointDateAsc(
                eq(ENTITY_ID), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(cp));

        SentimentOverTimeResponse response = service.getSentimentOverTime(
                TimePeriod.DAY90, List.of(ENTITY_ID));

        EntitySentimentData data = response.getEntities().get(0);
        assertThat(data.getCheckpoints()).hasSize(1);

        CheckpointMarker marker = data.getCheckpoints().get(0);
        assertThat(marker.getDescription()).isEqualTo("Quarterly Review");
        assertThat(marker.getDate()).isEqualTo(sixtyDaysAgo.format(
                DateTimeFormatter.ofPattern("yyyy-MM-dd")));
    }

    @Test
    void noCheckpointsReturnsEmptyList() {
        ManagedEntity e = entity(ENTITY_ID, "TestEntity");
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(e));

        when(mentionRepository.findByEntityIdsAndDateRange(
                eq(Collections.singletonList(ENTITY_ID)), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of());

        when(checkpointRepository.findByManagedEntityIdAndCheckpointDateBetweenOrderByCheckpointDateAsc(
                eq(ENTITY_ID), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of());

        SentimentOverTimeResponse response = service.getSentimentOverTime(
                TimePeriod.DAY, List.of(ENTITY_ID));

        EntitySentimentData data = response.getEntities().get(0);
        assertThat(data.getCheckpoints()).isEmpty();
    }
}
