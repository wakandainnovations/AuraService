package com.aura.service.service;

import com.aura.service.dto.HourlyActivityResponse;
import com.aura.service.entity.ManagedEntity;
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
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DashboardServiceHourlyActivityTest {

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
        return e;
    }

    @Test
    void hourlyActivityForDay90SpansNinetyDaysWithDailyBuckets() {
        ManagedEntity e = entity(ENTITY_ID, "TestEntity");
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(e));

        when(mentionRepository.countActiveUsersByHour(
                eq(ENTITY_ID), any(Instant.class), any(Instant.class),
                isNull(), isNull(), isNull()))
                .thenReturn(List.of(new Object[]{9, 5L}, new Object[]{14, 3L}));

        when(mentionRepository.countDistinctActiveUsers(
                eq(ENTITY_ID), any(Instant.class), any(Instant.class),
                isNull(), isNull(), isNull()))
                .thenReturn(8L);

        when(mentionRepository.countActiveUsersByDayAndHour(
                eq(ENTITY_ID), any(Instant.class), any(Instant.class),
                isNull(), isNull(), isNull()))
                .thenReturn(List.of());

        HourlyActivityResponse response = service.getHourlyActivity(
                ENTITY_ID, TimePeriod.DAY90, null, null, null);

        assertThat(response.getEntityId()).isEqualTo(ENTITY_ID);
        assertThat(response.getEntityName()).isEqualTo("TestEntity");
        assertThat(response.getPeriod()).isEqualTo(TimePeriod.DAY90);

        // 90-day window
        long daysBetween = ChronoUnit.DAYS.between(response.getStartDate(), response.getEndDate());
        assertThat(daysBetween).isEqualTo(90);

        assertThat(response.getTotalActiveUsers()).isEqualTo(8L);

        // hourly distribution always has 24 buckets, populated from the query rows
        assertThat(response.getHourlyDistribution()).hasSize(24);
        assertThat(response.getHourlyDistribution().get(9)).isEqualTo(5L);
        assertThat(response.getHourlyDistribution().get(14)).isEqualTo(3L);
        assertThat(response.getHourlyDistribution().get(0)).isEqualTo(0L);

        // daily distribution covers the full 90-day window inclusive of both endpoints
        assertThat(response.getDailyDistribution()).hasSize(91);
        response.getDailyDistribution().values()
                .forEach(hours -> assertThat(hours).hasSize(24));
    }
}
