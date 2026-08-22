package com.aura.service.service;

import com.aura.service.entity.EntityKeyword;
import com.aura.service.entity.EntityLanguageSpreaderSnapshot;
import com.aura.service.entity.ManagedEntity;
import com.aura.service.repository.EntityLanguageSpreaderSnapshotRepository;
import com.aura.service.repository.ManagedEntityRepository;
import com.aura.service.service.TopSpreaderLookupService.SpreaderProfile;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers {@link TopSpreaderLanguageSyncService}: language grouping/dedup, the untagged-keyword skip
 * rule, the upsert-by-(entity, language) persistence, and the per-entity failure isolation. Collaborator
 * repositories are mocked as interfaces (never concrete classes, per this project's Java 25 / Mockito
 * constraint); {@link TopSpreaderLookupService} is a concrete class, so per project convention it is
 * constructed for real (with a null proxy) and its cache seeded directly via reflection.
 */
class TopSpreaderLanguageSyncServiceTest {

    private ManagedEntityRepository entityRepository;
    private EntityLanguageSpreaderSnapshotRepository snapshotRepository;
    private TopSpreaderLookupService spreaderLookup;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private TopSpreaderLanguageSyncService service;

    @BeforeEach
    void setUp() {
        entityRepository = mock(ManagedEntityRepository.class);
        snapshotRepository = mock(EntityLanguageSpreaderSnapshotRepository.class);
        spreaderLookup = new TopSpreaderLookupService(null, objectMapper);
        service = new TopSpreaderLanguageSyncService(entityRepository, snapshotRepository, spreaderLookup, objectMapper, clock);

        when(snapshotRepository.findByEntityIdAndLanguageIgnoreCase(any(), any())).thenReturn(Optional.empty());
    }

    @Test
    void groupsKeywordsByLanguageAndDedupesSpreadersAcrossKeywordsInTheSameLanguage() {
        ManagedEntity entity = movieWithKeywords(1L,
                keyword("kw-tamil-1", "Tamil"),
                keyword("kw-tamil-2", "Tamil"));
        when(entityRepository.findByType("MOVIE")).thenReturn(List.of(entity));
        seedSpreaders("kw-tamil-1", List.of(
                new SpreaderProfile("handle-a", "TWITTER", null, 100L, null),
                new SpreaderProfile("handle-b", "TWITTER", null, 200L, null)));
        seedSpreaders("kw-tamil-2", List.of(
                new SpreaderProfile("handle-b", "TWITTER", null, 200L, null), // duplicate globalUserId
                new SpreaderProfile("handle-c", "YOUTUBE", null, 50L, null)));

        service.refreshAllEntityLanguageSpreaders();

        var captor = org.mockito.ArgumentCaptor.forClass(EntityLanguageSpreaderSnapshot.class);
        verify(snapshotRepository).save(captor.capture());
        EntityLanguageSpreaderSnapshot saved = captor.getValue();
        assertThat(saved.getEntityId()).isEqualTo(1L);
        assertThat(saved.getLanguage()).isEqualTo("Tamil");
        assertThat(saved.getSpreaderCount()).isEqualTo(3); // handle-a, handle-b (deduped), handle-c
        assertThat(saved.getGeneratedAt()).isEqualTo(Instant.now(clock));
    }

    @Test
    void keywordsWithNoLanguageTagAreSkippedEntirely() {
        ManagedEntity entity = movieWithKeywords(1L, keyword("untagged-keyword", null));
        when(entityRepository.findByType("MOVIE")).thenReturn(List.of(entity));

        service.refreshAllEntityLanguageSpreaders();

        verify(snapshotRepository, never()).save(any());
    }

    @Test
    void producesOneSnapshotRowPerDistinctLanguage() {
        ManagedEntity entity = movieWithKeywords(1L,
                keyword("kw-tamil", "Tamil"),
                keyword("kw-kannada", "Kannada"));
        when(entityRepository.findByType("MOVIE")).thenReturn(List.of(entity));
        seedSpreaders("kw-tamil", List.of(new SpreaderProfile("t1", "TWITTER", null, 10L, null)));
        seedSpreaders("kw-kannada", List.of(
                new SpreaderProfile("k1", "TWITTER", null, 10L, null),
                new SpreaderProfile("k2", "TWITTER", null, 20L, null)));

        service.refreshAllEntityLanguageSpreaders();

        var captor = org.mockito.ArgumentCaptor.forClass(EntityLanguageSpreaderSnapshot.class);
        verify(snapshotRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        var byLanguage = captor.getAllValues().stream()
                .collect(java.util.stream.Collectors.toMap(EntityLanguageSpreaderSnapshot::getLanguage, s -> s));
        assertThat(byLanguage.get("Tamil").getSpreaderCount()).isEqualTo(1);
        assertThat(byLanguage.get("Kannada").getSpreaderCount()).isEqualTo(2);
    }

    @Test
    void updatesExistingSnapshotRowInsteadOfInsertingADuplicate() {
        ManagedEntity entity = movieWithKeywords(1L, keyword("kw-tamil", "Tamil"));
        when(entityRepository.findByType("MOVIE")).thenReturn(List.of(entity));
        seedSpreaders("kw-tamil", List.of(new SpreaderProfile("t1", "TWITTER", null, 10L, null)));
        EntityLanguageSpreaderSnapshot existing = new EntityLanguageSpreaderSnapshot();
        existing.setId(99L);
        existing.setEntityId(1L);
        existing.setLanguage("Tamil");
        when(snapshotRepository.findByEntityIdAndLanguageIgnoreCase(1L, "Tamil")).thenReturn(Optional.of(existing));

        service.refreshAllEntityLanguageSpreaders();

        var captor = org.mockito.ArgumentCaptor.forClass(EntityLanguageSpreaderSnapshot.class);
        verify(snapshotRepository).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(99L); // same row updated, not a new one inserted
    }

    @Test
    void oneEntitysFailureDoesNotAbortTheRestOfTheBatch() {
        ManagedEntity failing = movieWithKeywords(2L, keyword("kw-fail", "Tamil"));
        ManagedEntity healthy = movieWithKeywords(3L, keyword("kw-ok", "Tamil"));
        when(entityRepository.findByType("MOVIE")).thenReturn(List.of(failing, healthy));
        seedSpreaders("kw-fail", List.of(new SpreaderProfile("h-fail", "TWITTER", null, 1L, null)));
        seedSpreaders("kw-ok", List.of(new SpreaderProfile("h-ok", "TWITTER", null, 1L, null)));
        // Simulate a persistence failure for entity 2's row (e.g. a transient DB error) without mocking
        // the concrete TopSpreaderLookupService (this project's Java 25 / Mockito toolchain can't mock
        // concrete classes - see the class-level doc comment).
        when(snapshotRepository.save(argThatEntityId(2L))).thenThrow(new RuntimeException("db unavailable"));

        service.refreshAllEntityLanguageSpreaders();

        verify(snapshotRepository).save(argThatEntityId(3L));
    }

    // ==================== Helpers ====================

    private static EntityLanguageSpreaderSnapshot argThatEntityId(Long entityId) {
        return org.mockito.ArgumentMatchers.argThat(s -> s != null && entityId.equals(s.getEntityId()));
    }

    private static ManagedEntity movieWithKeywords(Long id, EntityKeyword... keywords) {
        ManagedEntity entity = new ManagedEntity();
        entity.setId(id);
        entity.setType("MOVIE");
        entity.setKeywords(new ArrayList<>(List.of(keywords)));
        return entity;
    }

    private static EntityKeyword keyword(String keyword, String language) {
        EntityKeyword ek = new EntityKeyword();
        ek.setKeyword(keyword);
        ek.setLanguage(language);
        return ek;
    }

    private void seedSpreaders(String keyword, List<SpreaderProfile> profiles) {
        @SuppressWarnings("unchecked")
        com.aura.service.proxy.TtlCache<List<SpreaderProfile>> cache =
                (com.aura.service.proxy.TtlCache<List<SpreaderProfile>>)
                        ReflectionTestUtils.getField(spreaderLookup, "profileCache");
        cache.put(keyword, profiles, Duration.ofMinutes(10).toNanos());
    }
}
