package com.aura.service.service;

import com.aura.service.dto.ClonePlaybookRequest;
import com.aura.service.dto.PlaybookResponse;
import com.aura.service.dto.UpdatePlaybookRequest;
import com.aura.service.entity.CrisisPlan;
import com.aura.service.repository.CrisisPlanRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlaybookServiceTest {

    private static final Long ENTITY_ID = 5L;
    private static final Long PLAN_ID = 42L;
    private static final Long CLONER_ID = 7L;

    private CrisisPlanRepository playbookRepository;
    private PlaybookService service;

    @BeforeEach
    void setUp() {
        playbookRepository = mock(CrisisPlanRepository.class);
        service = new PlaybookService(playbookRepository);
    }

    private CrisisPlan plan(Long id, boolean favorite, List<String> tags, Instant createdAt) {
        return CrisisPlan.builder()
                .id(id)
                .entityId(ENTITY_ID)
                .mentionId(100L)
                .title("Negative review surge")
                .planText("1. Acknowledge. 2. Respond. 3. Monitor.")
                .tags(new ArrayList<>(tags))
                .isFavorite(favorite)
                .createdBy(3L)
                .createdAt(createdAt)
                .build();
    }

    @Test
    void list_filtersByTagAndFavoriteAndSortsNewestFirst() {
        CrisisPlan older = plan(1L, true, List.of("review", "launch"), Instant.parse("2026-05-01T00:00:00Z"));
        CrisisPlan newer = plan(2L, true, List.of("review"), Instant.parse("2026-05-10T00:00:00Z"));
        CrisisPlan notFavorite = plan(3L, false, List.of("review"), Instant.parse("2026-05-20T00:00:00Z"));
        CrisisPlan otherTag = plan(4L, true, List.of("legal"), Instant.parse("2026-05-25T00:00:00Z"));
        when(playbookRepository.findByEntityId(ENTITY_ID))
                .thenReturn(List.of(older, newer, notFavorite, otherTag));

        List<PlaybookResponse> result = service.list(ENTITY_ID, "review", true);

        assertThat(result).extracting(PlaybookResponse::getId).containsExactly(2L, 1L);
    }

    @Test
    void list_withoutEntityIdScansAll() {
        when(playbookRepository.findAll())
                .thenReturn(List.of(plan(1L, false, List.of(), Instant.parse("2026-05-01T00:00:00Z"))));

        List<PlaybookResponse> result = service.list(null, null, null);

        assertThat(result).hasSize(1);
        verify(playbookRepository).findAll();
    }

    @Test
    void update_changesOnlyProvidedFields() {
        CrisisPlan existing = plan(PLAN_ID, false, List.of("review"), Instant.parse("2026-05-01T00:00:00Z"));
        when(playbookRepository.findById(PLAN_ID)).thenReturn(Optional.of(existing));
        when(playbookRepository.save(any(CrisisPlan.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdatePlaybookRequest request = new UpdatePlaybookRequest("New title", null, List.of("review", "vip"), true);
        PlaybookResponse result = service.update(PLAN_ID, request);

        assertThat(result.getTitle()).isEqualTo("New title");
        assertThat(result.getPlanText()).isEqualTo("1. Acknowledge. 2. Respond. 3. Monitor.");
        assertThat(result.getTags()).containsExactly("review", "vip");
        assertThat(result.isFavorite()).isTrue();
    }

    @Test
    void update_rejectsBlankPlanText() {
        CrisisPlan existing = plan(PLAN_ID, false, List.of("review"), Instant.parse("2026-05-01T00:00:00Z"));
        when(playbookRepository.findById(PLAN_ID)).thenReturn(Optional.of(existing));

        UpdatePlaybookRequest request = new UpdatePlaybookRequest(null, "   ", null, null);

        assertThatThrownBy(() -> service.update(PLAN_ID, request))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("planText");
        verify(playbookRepository, never()).save(any());
    }

    @Test
    void update_throwsWhenMissing() {
        when(playbookRepository.findById(PLAN_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(PLAN_ID, new UpdatePlaybookRequest()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void clone_copiesTextAndTagsResetsFavoriteAndReassignsOwner() {
        CrisisPlan source = plan(PLAN_ID, true, List.of("review", "launch"), Instant.parse("2026-05-01T00:00:00Z"));
        when(playbookRepository.findById(PLAN_ID)).thenReturn(Optional.of(source));
        when(playbookRepository.save(any(CrisisPlan.class))).thenAnswer(inv -> inv.getArgument(0));

        PlaybookResponse result = service.clone(CLONER_ID, PLAN_ID, null);

        assertThat(result.getTitle()).isEqualTo("Copy of Negative review surge");
        assertThat(result.getPlanText()).isEqualTo(source.getPlanText());
        assertThat(result.getTags()).containsExactly("review", "launch");
        assertThat(result.isFavorite()).isFalse();
        assertThat(result.getCreatedBy()).isEqualTo(CLONER_ID);
        assertThat(result.getEntityId()).isEqualTo(ENTITY_ID);
    }

    @Test
    void clone_usesProvidedTitleAndDoesNotMutateSourceTags() {
        CrisisPlan source = plan(PLAN_ID, true, List.of("review"), Instant.parse("2026-05-01T00:00:00Z"));
        when(playbookRepository.findById(PLAN_ID)).thenReturn(Optional.of(source));
        when(playbookRepository.save(any(CrisisPlan.class))).thenAnswer(inv -> inv.getArgument(0));

        PlaybookResponse result = service.clone(CLONER_ID, PLAN_ID, new ClonePlaybookRequest("Q3 escalation"));

        assertThat(result.getTitle()).isEqualTo("Q3 escalation");
        assertThat(source.getTags()).containsExactly("review");
    }
}
