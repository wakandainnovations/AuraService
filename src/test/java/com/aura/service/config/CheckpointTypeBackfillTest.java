package com.aura.service.config;

import com.aura.service.entity.Checkpoint;
import com.aura.service.enums.CheckpointType;
import com.aura.service.repository.CheckpointRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CheckpointTypeBackfillTest {

    private final CheckpointRepository checkpointRepository = mock(CheckpointRepository.class);
    private final CheckpointTypeBackfill backfill = new CheckpointTypeBackfill(checkpointRepository);

    private Checkpoint untyped(Long id) {
        return Checkpoint.builder()
                .id(id)
                .checkpointDate(LocalDate.of(2026, 6, 1))
                .description("Legacy " + id)
                .build();
    }

    @Test
    void setsOtherOnEveryUntypedCheckpointExactlyOnce() {
        Checkpoint a = untyped(1L);
        Checkpoint b = untyped(2L);
        when(checkpointRepository.findByCheckpointTypeIsNull()).thenReturn(List.of(a, b));

        backfill.run(null);

        assertThat(a.getCheckpointType()).isEqualTo(CheckpointType.OTHER);
        assertThat(b.getCheckpointType()).isEqualTo(CheckpointType.OTHER);
        verify(checkpointRepository).saveAll(List.of(a, b));
    }

    @Test
    void isIdempotentOnSecondRun() {
        List<Checkpoint> untypedRows = new ArrayList<>(List.of(untyped(1L)));
        when(checkpointRepository.findByCheckpointTypeIsNull())
                .thenReturn(untypedRows)
                .thenReturn(List.of());

        backfill.run(null);
        backfill.run(null);

        verify(checkpointRepository).saveAll(any());
    }

    @Test
    void isNoOpWhenNoUntypedCheckpoints() {
        when(checkpointRepository.findByCheckpointTypeIsNull()).thenReturn(List.of());

        backfill.run(null);

        verify(checkpointRepository, never()).saveAll(any());
    }
}
