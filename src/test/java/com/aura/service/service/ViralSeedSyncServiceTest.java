package com.aura.service.service;

import com.aura.service.entity.EntityKeyword;
import com.aura.service.entity.EntityViralSeedSnapshot;
import com.aura.service.entity.ManagedEntity;
import com.aura.service.repository.EntityViralSeedSnapshotRepository;
import com.aura.service.repository.ManagedEntityRepository;
import com.aura.service.service.ViralSeedLookupService.ViralSeed;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers {@link ViralSeedSyncService}: keyword dedup across an entity's tracked keywords, the
 * no-tracked-keyword skip rule, the upsert-by-entity persistence, and the per-entity failure
 * isolation. Collaborator repositories and {@link ViralSeedLookupService} are all mocked as
 * interfaces (never concrete classes, per this project's Java 25 / Mockito constraint).
 */
class ViralSeedSyncServiceTest {

    private ManagedEntityRepository entityRepository;
    private EntityViralSeedSnapshotRepository snapshotRepository;
    private ViralSeedLookupService viralSeedLookup;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private ViralSeedSyncService service;

    @BeforeEach
    void setUp() {
        entityRepository = mock(ManagedEntityRepository.class);
        snapshotRepository = mock(EntityViralSeedSnapshotRepository.class);
        viralSeedLookup = mock(ViralSeedLookupService.class);
        service = new ViralSeedSyncService(entityRepository, snapshotRepository, viralSeedLookup, objectMapper, clock);
        // No Spring context in this test, so self-invocation through the proxy (see the `self` field's
        // doc comment on the service) isn't exercised here - wiring it to the instance itself keeps
        // refreshOneEntity() reachable without needing a real @Transactional interceptor, which is
        // framework behavior, not this class's logic.
        ReflectionTestUtils.setField(service, "self", service);

        when(snapshotRepository.findByEntityId(any())).thenReturn(Optional.empty());
        when(viralSeedLookup.getViralSeeds(any())).thenReturn(List.of());
    }

    @Test
    void dedupesSeedsAcrossEveryTrackedKeywordForTheEntity() {
        ManagedEntity entity = movieWithKeywords(1L, keyword("kw-1"), keyword("kw-2"));
        stubEntities(entity);
        when(viralSeedLookup.getViralSeeds("kw-1")).thenReturn(List.of(
                new ViralSeed("handle-a", "TWITTER", null),
                new ViralSeed("handle-b", "TWITTER", null)));
        when(viralSeedLookup.getViralSeeds("kw-2")).thenReturn(List.of(
                new ViralSeed("handle-b", "TWITTER", null), // duplicate author
                new ViralSeed("handle-c", "YOUTUBE", null)));

        service.refreshAllEntityViralSeeds();

        var captor = org.mockito.ArgumentCaptor.forClass(EntityViralSeedSnapshot.class);
        verify(snapshotRepository).save(captor.capture());
        EntityViralSeedSnapshot saved = captor.getValue();
        assertThat(saved.getEntityId()).isEqualTo(1L);
        assertThat(saved.getSeedCount()).isEqualTo(3); // handle-a, handle-b (deduped), handle-c
        assertThat(saved.getGeneratedAt()).isEqualTo(Instant.now(clock));
    }

    @Test
    void entityWithNoTrackedKeywordsIsSkippedEntirely() {
        ManagedEntity entity = movieWithKeywords(1L);
        stubEntities(entity);

        service.refreshAllEntityViralSeeds();

        verify(snapshotRepository, never()).save(any());
    }

    @Test
    void updatesExistingSnapshotRowInsteadOfInsertingADuplicate() {
        ManagedEntity entity = movieWithKeywords(1L, keyword("kw-1"));
        stubEntities(entity);
        when(viralSeedLookup.getViralSeeds("kw-1")).thenReturn(List.of(new ViralSeed("handle-a", "TWITTER", null)));
        EntityViralSeedSnapshot existing = new EntityViralSeedSnapshot();
        existing.setId(99L);
        existing.setEntityId(1L);
        when(snapshotRepository.findByEntityId(1L)).thenReturn(Optional.of(existing));

        service.refreshAllEntityViralSeeds();

        var captor = org.mockito.ArgumentCaptor.forClass(EntityViralSeedSnapshot.class);
        verify(snapshotRepository).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(99L); // same row updated, not a new one inserted
    }

    @Test
    void oneEntitysFailureDoesNotAbortTheRestOfTheBatch() {
        ManagedEntity failing = movieWithKeywords(2L, keyword("kw-fail"));
        ManagedEntity healthy = movieWithKeywords(3L, keyword("kw-ok"));
        stubEntities(failing, healthy);
        when(viralSeedLookup.getViralSeeds("kw-fail")).thenReturn(List.of(new ViralSeed("h-fail", "TWITTER", null)));
        when(viralSeedLookup.getViralSeeds("kw-ok")).thenReturn(List.of(new ViralSeed("h-ok", "TWITTER", null)));
        when(snapshotRepository.save(argThatEntityId(2L))).thenThrow(new RuntimeException("db unavailable"));

        service.refreshAllEntityViralSeeds();

        verify(snapshotRepository).save(argThatEntityId(3L));
    }

    // ==================== Helpers ====================

    /** Stubs both findByType (the batch listing) and findById (refreshOneEntity's re-fetch, needed to
     *  read ManagedEntity.keywords inside its own @Transactional boundary - see that method's doc). */
    private void stubEntities(ManagedEntity... entities) {
        when(entityRepository.findByType("MOVIE")).thenReturn(List.of(entities));
        for (ManagedEntity entity : entities) {
            when(entityRepository.findById(entity.getId())).thenReturn(Optional.of(entity));
        }
    }

    private static EntityViralSeedSnapshot argThatEntityId(Long entityId) {
        return argThat(s -> s != null && entityId.equals(s.getEntityId()));
    }

    private static ManagedEntity movieWithKeywords(Long id, EntityKeyword... keywords) {
        ManagedEntity entity = new ManagedEntity();
        entity.setId(id);
        entity.setType("MOVIE");
        entity.setKeywords(new ArrayList<>(List.of(keywords)));
        return entity;
    }

    private static EntityKeyword keyword(String keyword) {
        EntityKeyword ek = new EntityKeyword();
        ek.setKeyword(keyword);
        return ek;
    }
}
