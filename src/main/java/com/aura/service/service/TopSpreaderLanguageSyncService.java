package com.aura.service.service;

import com.aura.service.entity.EntityKeyword;
import com.aura.service.entity.EntityLanguageSpreaderSnapshot;
import com.aura.service.entity.ManagedEntity;
import com.aura.service.repository.EntityLanguageSpreaderSnapshotRepository;
import com.aura.service.repository.ManagedEntityRepository;
import com.aura.service.service.TopSpreaderLookupService.SpreaderProfile;
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
import java.util.List;
import java.util.Map;

/**
 * Periodically (every 2 days) snapshots, for each MOVIE entity and each language it's actually being
 * marketed in - i.e. has a tracked {@link EntityKeyword} tagged with that language - how many of
 * AuraMath's top-50-spreaders are talking about it in that language, deduped across every keyword
 * tagged with that language via {@link TopSpreaderLookupService}. Stored in
 * {@link EntityLanguageSpreaderSnapshot} so {@link RecommendedActionCandidateServiceImpl}'s
 * top-spreader-gap candidate can compare a movie's own count against a comparable movie's without
 * paying for a live AuraMath round-trip per candidate generation - same "cache the periodic pull, read
 * the cache on request" split as {@link CommandCenterSummaryService}/{@link AudiencePulseAspectsService}.
 * A keyword with no language tag contributes to no snapshot - there's no per-language spreader count to
 * honestly attribute an untagged keyword's spreaders to.
 */
@Slf4j
@Service
public class TopSpreaderLanguageSyncService {

    private static final String MOVIE_TYPE = "MOVIE";

    private final ManagedEntityRepository entityRepository;
    private final EntityLanguageSpreaderSnapshotRepository snapshotRepository;
    private final TopSpreaderLookupService spreaderLookup;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    // Self-injected proxy: calls to refreshOneEntity() below must go through Spring's proxy (not a
    // direct this.refreshOneEntity(...) self-call) for its @Transactional advice to actually apply -
    // required because these calls run off a scheduler thread, which has no request-bound Hibernate
    // session, so ManagedEntity.keywords (a lazy @ElementCollection) can't be read without one. Same
    // pattern as RecommendedActionsService.self - see that field's doc comment. @Lazy avoids the
    // circular-bean chicken/egg problem at construction time.
    @Autowired
    @Lazy
    private TopSpreaderLanguageSyncService self;

    public TopSpreaderLanguageSyncService(
            ManagedEntityRepository entityRepository,
            EntityLanguageSpreaderSnapshotRepository snapshotRepository,
            TopSpreaderLookupService spreaderLookup,
            ObjectMapper objectMapper,
            Clock clock) {
        this.entityRepository = entityRepository;
        this.snapshotRepository = snapshotRepository;
        this.spreaderLookup = spreaderLookup;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "P2D")
    public void refreshAllEntityLanguageSpreaders() {
        List<ManagedEntity> entities = entityRepository.findByType(MOVIE_TYPE);
        log.info("Refreshing top-spreader language snapshots for {} movie entities", entities.size());
        for (ManagedEntity entity : entities) {
            try {
                self.refreshOneEntity(entity.getId());
            } catch (Exception e) {
                log.error("Failed to refresh top-spreader language snapshots for entity {}", entity.getId(), e);
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
        for (Map.Entry<String, List<String>> entry : keywordsByLanguage(entity).entrySet()) {
            String language = entry.getKey();
            try {
                refreshOneLanguage(entity.getId(), language, entry.getValue());
            } catch (Exception e) {
                log.error("Failed to refresh top-spreader snapshot for entity {} language {}",
                        entity.getId(), language, e);
            }
        }
    }

    /** Distinct keywords grouped by language, skipping any keyword with no language tag. */
    static Map<String, List<String>> keywordsByLanguage(ManagedEntity entity) {
        Map<String, List<String>> byLanguage = new LinkedHashMap<>();
        if (entity.getKeywords() == null) {
            return byLanguage;
        }
        for (EntityKeyword ek : entity.getKeywords()) {
            if (ek == null || ek.getKeyword() == null || ek.getKeyword().isBlank()
                    || ek.getLanguage() == null || ek.getLanguage().isBlank()) {
                continue;
            }
            String language = ek.getLanguage().trim();
            List<String> keywords = byLanguage.computeIfAbsent(language, k -> new ArrayList<>());
            if (!keywords.contains(ek.getKeyword())) {
                keywords.add(ek.getKeyword());
            }
        }
        return byLanguage;
    }

    private void refreshOneLanguage(Long entityId, String language, List<String> keywords) {
        Map<String, SpreaderProfile> deduped = new LinkedHashMap<>();
        for (String keyword : keywords) {
            for (SpreaderProfile profile : spreaderLookup.getSpreaderProfiles(keyword)) {
                if (profile.globalUserId() == null || profile.globalUserId().isBlank()) {
                    continue;
                }
                deduped.putIfAbsent(profile.globalUserId(), profile);
            }
        }
        List<SpreaderProfile> profiles = new ArrayList<>(deduped.values());

        EntityLanguageSpreaderSnapshot row = snapshotRepository
                .findByEntityIdAndLanguageIgnoreCase(entityId, language)
                .orElseGet(EntityLanguageSpreaderSnapshot::new);
        row.setEntityId(entityId);
        row.setLanguage(language);
        row.setSpreaderCount(profiles.size());
        row.setSpreadersJson(writeProfilesJson(profiles, entityId, language));
        row.setGeneratedAt(Instant.now(clock));
        snapshotRepository.save(row);
    }

    private String writeProfilesJson(List<SpreaderProfile> profiles, Long entityId, String language) {
        try {
            return objectMapper.writeValueAsString(profiles);
        } catch (Exception e) {
            log.warn("Failed to serialize spreader profiles for entity {} language {}", entityId, language, e);
            return "[]";
        }
    }
}
