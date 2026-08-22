package com.aura.service.service;

import com.aura.service.entity.EntityKeyword;
import com.aura.service.entity.EntityViralSeedSnapshot;
import com.aura.service.entity.ManagedEntity;
import com.aura.service.repository.EntityViralSeedSnapshotRepository;
import com.aura.service.repository.ManagedEntityRepository;
import com.aura.service.service.ViralSeedLookupService.ViralSeed;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Periodically (every 2 days) snapshots, for each MOVIE entity, which of AuraMath's keyword-scoped
 * viral-seed accounts (see {@link ViralSeedLookupService}) are relevant to it - deduped across every
 * tracked {@link EntityKeyword}, regardless of language. Unlike
 * {@link TopSpreaderLanguageSyncService}'s per-language spreader snapshot, AuraMath's viral-seeds
 * endpoint isn't language-scoped, so this sync stores one snapshot per entity rather than one per
 * (entity, language) pair. Stored in {@link EntityViralSeedSnapshot} so
 * {@link RecommendedActionCandidateServiceImpl}'s cumulative-view-count-gap candidate can read a
 * comparable, higher-viewed movie's viral seeds without paying for a live AuraMath round-trip per
 * candidate generation - same "cache the periodic pull, read the cache on request" split as
 * {@link TopSpreaderLanguageSyncService}.
 */
@Slf4j
@Service
public class ViralSeedSyncService {

    private static final String MOVIE_TYPE = "MOVIE";

    private final ManagedEntityRepository entityRepository;
    private final EntityViralSeedSnapshotRepository snapshotRepository;
    private final ViralSeedLookupService viralSeedLookup;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    // Self-injected proxy: calls to refreshOneEntity() below must go through Spring's proxy (not a
    // direct this.refreshOneEntity(...) self-call) for its @Transactional advice to actually apply -
    // required because these calls run off a scheduler thread, which has no request-bound Hibernate
    // session, so ManagedEntity.keywords (a lazy @ElementCollection) can't be read without one. Same
    // pattern as TopSpreaderLanguageSyncService.self - see that field's doc comment. @Lazy avoids the
    // circular-bean chicken/egg problem at construction time.
    @Autowired
    @Lazy
    private ViralSeedSyncService self;

    public ViralSeedSyncService(
            ManagedEntityRepository entityRepository,
            EntityViralSeedSnapshotRepository snapshotRepository,
            ViralSeedLookupService viralSeedLookup,
            ObjectMapper objectMapper,
            Clock clock) {
        this.entityRepository = entityRepository;
        this.snapshotRepository = snapshotRepository;
        this.viralSeedLookup = viralSeedLookup;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "P2D")
    public void refreshAllEntityViralSeeds() {
        List<ManagedEntity> entities = entityRepository.findByType(MOVIE_TYPE);
        log.info("Refreshing viral-seed snapshots for {} movie entities", entities.size());
        for (ManagedEntity entity : entities) {
            try {
                self.refreshOneEntity(entity.getId());
            } catch (Exception e) {
                log.error("Failed to refresh viral-seed snapshot for entity {}", entity.getId(), e);
            }
        }
    }

    /** Must be called via {@link #self}, not directly - see that field's doc comment. */
    @Transactional
    void refreshOneEntity(Long entityId) {
        ManagedEntity entity = entityRepository.findById(entityId).orElse(null);
        if (entity == null) {
            return;
        }
        List<String> keywords = distinctKeywords(entity);
        if (keywords.isEmpty()) {
            return;
        }

        Map<String, ViralSeed> deduped = new LinkedHashMap<>();
        for (String keyword : keywords) {
            for (ViralSeed seed : viralSeedLookup.getViralSeeds(keyword)) {
                deduped.putIfAbsent(seed.author(), seed);
            }
        }
        List<ViralSeed> seeds = new ArrayList<>(deduped.values());

        EntityViralSeedSnapshot row = snapshotRepository.findByEntityId(entityId)
                .orElseGet(EntityViralSeedSnapshot::new);
        row.setEntityId(entityId);
        row.setSeedCount(seeds.size());
        row.setSeedsJson(writeSeedsJson(seeds, entityId));
        row.setGeneratedAt(Instant.now(clock));
        snapshotRepository.save(row);
    }

    /** Distinct tracked keyword strings for this entity, regardless of language tag. */
    static List<String> distinctKeywords(ManagedEntity entity) {
        if (entity.getKeywords() == null) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        for (EntityKeyword ek : entity.getKeywords()) {
            if (ek != null && ek.getKeyword() != null && !ek.getKeyword().isBlank()) {
                seen.add(ek.getKeyword());
            }
        }
        return new ArrayList<>(seen);
    }

    private String writeSeedsJson(List<ViralSeed> seeds, Long entityId) {
        try {
            return objectMapper.writeValueAsString(seeds);
        } catch (Exception e) {
            log.warn("Failed to serialize viral seeds for entity {}", entityId, e);
            return "[]";
        }
    }
}
