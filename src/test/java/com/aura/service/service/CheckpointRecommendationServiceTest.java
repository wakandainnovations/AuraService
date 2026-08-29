package com.aura.service.service;

import com.aura.service.dto.CheckpointRecommendation;
import com.aura.service.dto.CheckpointRecommendationsResponse;
import com.aura.service.entity.Checkpoint;
import com.aura.service.entity.ManagedEntity;
import com.aura.service.enums.AnchorType;
import com.aura.service.enums.CheckpointStage;
import com.aura.service.repository.CheckpointRepository;
import com.aura.service.repository.MentionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CheckpointRecommendationServiceTest {

    private static final Long ENTITY_ID = 1L;
    private static final Long COMPETITOR_ID = 2L;

    private CheckpointRepository checkpointRepository;
    private MentionRepository mentionRepository;
    private EntityAccessService entityAccess;
    private CheckpointRecommendationService service;

    @BeforeEach
    void setUp() {
        checkpointRepository = mock(CheckpointRepository.class);
        mentionRepository = mock(MentionRepository.class);
        entityAccess = mock(EntityAccessService.class);
        service = new CheckpointRecommendationService(checkpointRepository, mentionRepository, entityAccess);
    }

    private ManagedEntity entity(Long id, String name, List<ManagedEntity> competitors) {
        ManagedEntity entity = new ManagedEntity();
        entity.setId(id);
        entity.setName(name);
        entity.setCompetitors(competitors);
        return entity;
    }

    private Checkpoint anchorCheckpoint(int selectedCount) {
        List<AnchorType> anchors = List.of(AnchorType.values()).subList(0, selectedCount);
        return Checkpoint.builder()
                .id(100L)
                .stage(CheckpointStage.ANCHOR_SEED)
                .isDefault(true)
                .selectedAnchors(anchors)
                .build();
    }

    @Test
    void flagsInsufficientAnchorsWhenFewerThanTwoSelected() {
        ManagedEntity entity = entity(ENTITY_ID, "Movie A", List.of());
        when(entityAccess.assertOwnedByCurrentUser(ENTITY_ID)).thenReturn(entity);
        when(checkpointRepository.findByManagedEntityIdAndIsDefaultTrue(ENTITY_ID))
                .thenReturn(List.of(anchorCheckpoint(1)));

        CheckpointRecommendationsResponse response = service.getRecommendations(ENTITY_ID);

        assertThat(response.getRecommendations()).hasSize(1);
        CheckpointRecommendation rec = response.getRecommendations().get(0);
        assertThat(rec.getRuleType()).isEqualTo("INSUFFICIENT_ANCHORS");
        assertThat(rec.getSelectedAnchorCount()).isEqualTo(1);
        assertThat(rec.getRequiredAnchorCount()).isEqualTo(2);
    }

    @Test
    void doesNotFlagAnchorsWhenTwoOrMoreSelected() {
        ManagedEntity entity = entity(ENTITY_ID, "Movie A", List.of());
        when(entityAccess.assertOwnedByCurrentUser(ENTITY_ID)).thenReturn(entity);
        when(checkpointRepository.findByManagedEntityIdAndIsDefaultTrue(ENTITY_ID))
                .thenReturn(List.of(anchorCheckpoint(2)));

        CheckpointRecommendationsResponse response = service.getRecommendations(ENTITY_ID);

        assertThat(response.getRecommendations()).isEmpty();
    }

    @Test
    void flagsBelowPeerTractionWhenSelfIsWellBelowPeerAverage() {
        ManagedEntity competitor = entity(COMPETITOR_ID, "Movie B", List.of());
        ManagedEntity entity = entity(ENTITY_ID, "Movie A", List.of(competitor));
        when(entityAccess.assertOwnedByCurrentUser(ENTITY_ID)).thenReturn(entity);

        LocalDate checkpointDate = LocalDate.of(2026, 6, 1);
        Checkpoint teaser = Checkpoint.builder()
                .id(101L).stage(CheckpointStage.TENSION_CURIOSITY).isDefault(true)
                .checkpointDate(checkpointDate).build();
        when(checkpointRepository.findByManagedEntityIdAndIsDefaultTrue(ENTITY_ID))
                .thenReturn(List.of(teaser));

        when(mentionRepository.countByManagedEntityIdAndPostDateBetween(
                org.mockito.ArgumentMatchers.eq(ENTITY_ID), any(), any())).thenReturn(2L);
        when(mentionRepository.countByManagedEntityIdAndPostDateBetween(
                org.mockito.ArgumentMatchers.eq(COMPETITOR_ID), any(), any())).thenReturn(20L);

        CheckpointRecommendationsResponse response = service.getRecommendations(ENTITY_ID);

        assertThat(response.getRecommendations()).hasSize(1);
        CheckpointRecommendation rec = response.getRecommendations().get(0);
        assertThat(rec.getRuleType()).isEqualTo("BELOW_PEER_TRACTION");
        assertThat(rec.getSelfMentionCount()).isEqualTo(2L);
        assertThat(rec.getPeerAverageMentionCount()).isEqualTo(20.0);
        assertThat(rec.getMessage()).contains("Movie B").contains("Teaser Release").contains("Trailer Release");
    }

    @Test
    void doesNotFlagWhenSelfIsAtOrAbovePeerAverage() {
        ManagedEntity competitor = entity(COMPETITOR_ID, "Movie B", List.of());
        ManagedEntity entity = entity(ENTITY_ID, "Movie A", List.of(competitor));
        when(entityAccess.assertOwnedByCurrentUser(ENTITY_ID)).thenReturn(entity);

        Checkpoint teaser = Checkpoint.builder()
                .id(101L).stage(CheckpointStage.TENSION_CURIOSITY).isDefault(true)
                .checkpointDate(LocalDate.of(2026, 6, 1)).build();
        when(checkpointRepository.findByManagedEntityIdAndIsDefaultTrue(ENTITY_ID))
                .thenReturn(List.of(teaser));

        when(mentionRepository.countByManagedEntityIdAndPostDateBetween(anyLong(), any(), any()))
                .thenReturn(10L);

        CheckpointRecommendationsResponse response = service.getRecommendations(ENTITY_ID);

        assertThat(response.getRecommendations()).isEmpty();
    }

    @Test
    void producesNoPeerTractionRecommendationsWhenNoCompetitors() {
        ManagedEntity entity = entity(ENTITY_ID, "Movie A", List.of());
        when(entityAccess.assertOwnedByCurrentUser(ENTITY_ID)).thenReturn(entity);

        Checkpoint teaser = Checkpoint.builder()
                .id(101L).stage(CheckpointStage.TENSION_CURIOSITY).isDefault(true)
                .checkpointDate(LocalDate.of(2026, 6, 1)).build();
        when(checkpointRepository.findByManagedEntityIdAndIsDefaultTrue(ENTITY_ID))
                .thenReturn(List.of(teaser));

        CheckpointRecommendationsResponse response = service.getRecommendations(ENTITY_ID);

        assertThat(response.getRecommendations()).isEmpty();
    }

    @Test
    void doesNotFlagWhenPeerAverageIsBelowTheSampleFloor() {
        ManagedEntity competitor = entity(COMPETITOR_ID, "Movie B", List.of());
        ManagedEntity entity = entity(ENTITY_ID, "Movie A", List.of(competitor));
        when(entityAccess.assertOwnedByCurrentUser(ENTITY_ID)).thenReturn(entity);

        Checkpoint teaser = Checkpoint.builder()
                .id(101L).stage(CheckpointStage.TENSION_CURIOSITY).isDefault(true)
                .checkpointDate(LocalDate.of(2026, 6, 1)).build();
        when(checkpointRepository.findByManagedEntityIdAndIsDefaultTrue(ENTITY_ID))
                .thenReturn(List.of(teaser));

        when(mentionRepository.countByManagedEntityIdAndPostDateBetween(
                org.mockito.ArgumentMatchers.eq(ENTITY_ID), any(), any())).thenReturn(0L);
        when(mentionRepository.countByManagedEntityIdAndPostDateBetween(
                org.mockito.ArgumentMatchers.eq(COMPETITOR_ID), any(), any())).thenReturn(3L);

        CheckpointRecommendationsResponse response = service.getRecommendations(ENTITY_ID);

        assertThat(response.getRecommendations()).isEmpty();
    }
}
