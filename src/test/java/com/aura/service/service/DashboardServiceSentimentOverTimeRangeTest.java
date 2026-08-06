package com.aura.service.service;

import com.aura.service.dto.CheckpointMarker;
import com.aura.service.dto.EntitySentimentData;
import com.aura.service.dto.SentimentOverTimeResponse;
import com.aura.service.entity.Checkpoint;
import com.aura.service.entity.ManagedEntity;
import com.aura.service.entity.Mention;
import com.aura.service.enums.Platform;
import com.aura.service.enums.Sentiment;
import com.aura.service.repository.CheckpointRepository;
import com.aura.service.repository.CrisisPlanRepository;
import com.aura.service.repository.ManagedEntityRepository;
import com.aura.service.repository.MentionRepository;
import com.aura.service.repository.ReplyDraftRepository;
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

class DashboardServiceSentimentOverTimeRangeTest {

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
    void shortRangeProducesDailyBucketsAndBucketsMentionsBySentiment() {
        ManagedEntity e = entity(ENTITY_ID, "TestEntity");
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(e));

        LocalDate start = LocalDate.of(2025, 10, 1);
        LocalDate end = LocalDate.of(2025, 10, 5);
        Instant middayOnOct3 = LocalDate.of(2025, 10, 3)
                .atStartOfDay(ZoneId.systemDefault()).plusHours(12).toInstant();

        Mention positive = mention(ENTITY_ID, "TestEntity", middayOnOct3, Sentiment.POSITIVE);
        Mention negative = mention(ENTITY_ID, "TestEntity", middayOnOct3, Sentiment.NEGATIVE);

        when(mentionRepository.findByEntityIdsAndDateRange(
                eq(Collections.singletonList(ENTITY_ID)), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of(positive, negative));

        when(checkpointRepository.findByManagedEntityIdAndCheckpointDateBetweenOrderByCheckpointDateAsc(
                eq(ENTITY_ID), eq(start), eq(end)))
                .thenReturn(List.of());

        SentimentOverTimeResponse response = service.getSentimentOverTimeForRange(start, end, List.of(ENTITY_ID));

        EntitySentimentData data = response.getEntities().get(0);
        assertThat(data.getName()).isEqualTo("TestEntity");

        List<String> dates = data.getSentiments().stream().map(ts -> ts.getDate()).toList();
        // 5-day range, daily buckets -> one entry per day inclusive of both endpoints
        assertThat(dates).containsExactly("2025-10-01", "2025-10-02", "2025-10-03", "2025-10-04", "2025-10-05");

        var oct3Bucket = data.getSentiments().stream()
                .filter(ts -> ts.getDate().equals("2025-10-03"))
                .findFirst().orElseThrow();
        assertThat(oct3Bucket.getPositive()).isEqualTo(1);
        assertThat(oct3Bucket.getNegative()).isEqualTo(1);
        assertThat(oct3Bucket.getTotal()).isEqualTo(2);
    }

    @Test
    void mediumRangeProducesWeeklyBuckets() {
        ManagedEntity e = entity(ENTITY_ID, "TestEntity");
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(e));

        LocalDate start = LocalDate.of(2025, 1, 1);
        LocalDate end = start.plusDays(200);

        when(mentionRepository.findByEntityIdsAndDateRange(
                eq(Collections.singletonList(ENTITY_ID)), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of());

        when(checkpointRepository.findByManagedEntityIdAndCheckpointDateBetweenOrderByCheckpointDateAsc(
                eq(ENTITY_ID), eq(start), eq(end)))
                .thenReturn(List.of());

        SentimentOverTimeResponse response = service.getSentimentOverTimeForRange(start, end, List.of(ENTITY_ID));

        EntitySentimentData data = response.getEntities().get(0);
        List<String> dates = data.getSentiments().stream().map(ts -> ts.getDate()).toList();

        assertThat(dates).isNotEmpty();
        assertThat(dates).allMatch(d -> d.matches("\\d{4}-W\\d{2}"));
    }

    @Test
    void longRangeProducesMonthlyBuckets() {
        ManagedEntity e = entity(ENTITY_ID, "TestEntity");
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(e));

        LocalDate start = LocalDate.of(2024, 1, 1);
        LocalDate end = LocalDate.of(2025, 6, 30);

        when(mentionRepository.findByEntityIdsAndDateRange(
                eq(Collections.singletonList(ENTITY_ID)), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of());

        when(checkpointRepository.findByManagedEntityIdAndCheckpointDateBetweenOrderByCheckpointDateAsc(
                eq(ENTITY_ID), eq(start), eq(end)))
                .thenReturn(List.of());

        SentimentOverTimeResponse response = service.getSentimentOverTimeForRange(start, end, List.of(ENTITY_ID));

        EntitySentimentData data = response.getEntities().get(0);
        List<String> dates = data.getSentiments().stream().map(ts -> ts.getDate()).toList();

        assertThat(dates).contains("2024-01", "2025-06");
        assertThat(dates).allMatch(d -> d.matches("\\d{4}-\\d{2}"));
    }

    @Test
    void checkpointMarkerDateFormatMatchesInferredGranularity() {
        ManagedEntity e = entity(ENTITY_ID, "TestEntity");
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(e));

        LocalDate start = LocalDate.of(2025, 10, 1);
        LocalDate end = LocalDate.of(2025, 10, 10);
        LocalDate checkpointDate = LocalDate.of(2025, 10, 5);

        Checkpoint cp = checkpoint(ENTITY_ID, checkpointDate, "Launch Day");

        when(mentionRepository.findByEntityIdsAndDateRange(
                eq(Collections.singletonList(ENTITY_ID)), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of());

        when(checkpointRepository.findByManagedEntityIdAndCheckpointDateBetweenOrderByCheckpointDateAsc(
                eq(ENTITY_ID), eq(start), eq(end)))
                .thenReturn(List.of(cp));

        SentimentOverTimeResponse response = service.getSentimentOverTimeForRange(start, end, List.of(ENTITY_ID));

        EntitySentimentData data = response.getEntities().get(0);
        assertThat(data.getCheckpoints()).hasSize(1);

        CheckpointMarker marker = data.getCheckpoints().get(0);
        assertThat(marker.getDescription()).isEqualTo("Launch Day");
        assertThat(marker.getDate()).isEqualTo(checkpointDate.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")));
    }

    @Test
    void singleDayRangeProducesOneBucket() {
        ManagedEntity e = entity(ENTITY_ID, "TestEntity");
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(e));

        LocalDate day = LocalDate.of(2025, 10, 1);

        when(mentionRepository.findByEntityIdsAndDateRange(
                eq(Collections.singletonList(ENTITY_ID)), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of());

        when(checkpointRepository.findByManagedEntityIdAndCheckpointDateBetweenOrderByCheckpointDateAsc(
                eq(ENTITY_ID), eq(day), eq(day)))
                .thenReturn(List.of());

        SentimentOverTimeResponse response = service.getSentimentOverTimeForRange(day, day, List.of(ENTITY_ID));

        EntitySentimentData data = response.getEntities().get(0);
        assertThat(data.getSentiments()).hasSize(1);
        assertThat(data.getSentiments().get(0).getDate()).isEqualTo("2025-10-01");
    }

    @Test
    void multipleEntitiesEachGetTheirOwnSeries() {
        Long secondEntityId = 2L;
        ManagedEntity first = entity(ENTITY_ID, "FirstEntity");
        ManagedEntity second = entity(secondEntityId, "SecondEntity");
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(first));
        when(entityRepository.findById(secondEntityId)).thenReturn(Optional.of(second));

        LocalDate start = LocalDate.of(2025, 10, 1);
        LocalDate end = LocalDate.of(2025, 10, 3);

        when(mentionRepository.findByEntityIdsAndDateRange(
                eq(Collections.singletonList(ENTITY_ID)), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of());
        when(mentionRepository.findByEntityIdsAndDateRange(
                eq(Collections.singletonList(secondEntityId)), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of());
        when(checkpointRepository.findByManagedEntityIdAndCheckpointDateBetweenOrderByCheckpointDateAsc(
                any(Long.class), eq(start), eq(end)))
                .thenReturn(List.of());

        SentimentOverTimeResponse response = service.getSentimentOverTimeForRange(
                start, end, List.of(ENTITY_ID, secondEntityId));

        assertThat(response.getEntities()).hasSize(2);
        assertThat(response.getEntities().get(0).getName()).isEqualTo("FirstEntity");
        assertThat(response.getEntities().get(1).getName()).isEqualTo("SecondEntity");
    }
}
