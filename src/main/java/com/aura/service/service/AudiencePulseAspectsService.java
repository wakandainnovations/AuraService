package com.aura.service.service;

import com.aura.service.dto.AudiencePulseAspectsResponse;
import com.aura.service.entity.AudiencePulseAspectsCache;
import com.aura.service.entity.ManagedEntity;
import com.aura.service.proxy.AuraMathProperties;
import com.aura.service.proxy.AuraMathProxyService;
import com.aura.service.repository.AudiencePulseAspectsCacheRepository;
import com.aura.service.repository.ManagedEntityRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Backs the "People Love" / "People Concerned About" chips on the Command Center's Audience Pulse
 * panel. Calls AuraMath's aspect-driver analysis ({@code GET /api/marketing/aspect-drivers?entityId=}),
 * a proper aspect-based sentiment analysis (ABSA): each candidate aspect is scored from the
 * sentiment of the single sentence it appears in (not the whole post it came from), restricted to
 * common-noun lemmas with no named-entity tag, and ranked only once it clears both a minimum mention
 * count and a minimum number of distinct authors — see {@code AspectSentimentAnalyzer} and
 * {@code AspectDriversPrecomputer} in AuraMath for the full methodology. peopleLove is the top 3
 * "strengths" (highest average sentiment, ranked by an author-diversity-shrunk impact score);
 * peopleConcerned is the top 3 "weaknesses" (lowest/most negative).
 *
 * <p>An earlier version asked an LLM to freely extract aspects from raw post text, which could latch
 * onto an off-topic tangent mentioned in a single post. The version before this one proxied straight
 * to AuraMath's aspect-drivers endpoint, which turned out to have its own problem: it copied each
 * post's whole-document sentiment onto every noun in it (so an off-topic aside could rank as high as
 * the post's real subject) and had no named-entity filtering (so hashtags, @handles, and the movie's
 * own cast could rank as "aspects"). AuraMath's analyzer was fixed at the source to address both;
 * {@link #isDisplayableAspect} keeps a second, cheap layer of the same hashtag/handle/cast-name
 * filtering here as defense in depth.
 *
 * <p>Generation is persisted to {@link AudiencePulseAspectsCache} and refreshed for every entity by
 * {@link #refreshAllAspects()} every 6 hours, so the endpoint normally just reads the cached row
 * instead of calling AuraMath on request; {@code refresh=true} or a not-yet-cached entity fall back
 * to generating on request.
 */
@Slf4j
@Service
public class AudiencePulseAspectsService {

    private static final String WRAPPER_PATH = "/api/dashboard/{entityId}/audience-pulse-aspects";
    private static final int TOP_N_ASPECTS = 3;
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };

    private final ManagedEntityRepository managedEntityRepository;
    private final AudiencePulseAspectsCacheRepository cacheRepository;
    private final AuraMathProxyService auraMathProxy;
    private final AuraMathProperties auraMathProperties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AudiencePulseAspectsService(
            ManagedEntityRepository managedEntityRepository,
            AudiencePulseAspectsCacheRepository cacheRepository,
            AuraMathProxyService auraMathProxy,
            AuraMathProperties auraMathProperties,
            ObjectMapper objectMapper,
            Clock clock) {
        this.managedEntityRepository = managedEntityRepository;
        this.cacheRepository = cacheRepository;
        this.auraMathProxy = auraMathProxy;
        this.auraMathProperties = auraMathProperties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // Transaction spans the whole call chain (including the private generate()/persist() below) so
    // entity.getActors() — a lazy @ElementCollection — can still be read after findById() returns
    // (outside a transaction, e.g. the @Scheduled loop below which has no request-bound Hibernate
    // session the way an HTTP request does under open-in-view, that access throws
    // LazyInitializationException), and so the cache write in persist() has a session to flush
    // through — not readOnly, since a cache-miss/refresh call writes the row.
    @Transactional
    public AudiencePulseAspectsResponse getAspects(Long entityId, boolean refresh) {
        GeneratedContent content = getCachedOrGenerate(entityId, refresh);
        return new AudiencePulseAspectsResponse(
                entityId, content.entityName(), content.peopleLove(), content.peopleConcerned(), content.generatedAt());
    }

    /**
     * Refreshes the cached aspects for every managed entity so the endpoint above can always serve a
     * persisted row instead of paying for an AuraMath call on request. Runs at startup and every 6
     * hours after that; one entity's failure is logged and skipped rather than aborting the run.
     */
    @Scheduled(fixedDelayString = "PT6H")
    @Transactional
    public void refreshAllAspects() {
        List<ManagedEntity> entities = managedEntityRepository.findAll();
        log.info("Refreshing audience pulse aspects for {} entities", entities.size());
        for (ManagedEntity entity : entities) {
            try {
                regenerateAndStore(entity.getId());
            } catch (Exception e) {
                log.error("Failed to refresh audience pulse aspects for entity {}", entity.getId(), e);
            }
        }
    }

    private GeneratedContent getCachedOrGenerate(Long entityId, boolean refresh) {
        if (!refresh) {
            var cached = cacheRepository.findByEntityId(entityId);
            if (cached.isPresent()) {
                return toGeneratedContent(cached.get());
            }
        }
        return regenerateAndStore(entityId);
    }

    private GeneratedContent regenerateAndStore(Long entityId) {
        GeneratedContent generated = generate(entityId);
        persist(entityId, generated);
        return generated;
    }

    private void persist(Long entityId, GeneratedContent content) {
        AudiencePulseAspectsCache row = cacheRepository.findByEntityId(entityId)
                .orElseGet(AudiencePulseAspectsCache::new);
        row.setEntityId(entityId);
        row.setEntityName(content.entityName());
        row.setPeopleLoveJson(writeStringListJson(content.peopleLove(), entityId, "peopleLove"));
        row.setPeopleConcernedJson(writeStringListJson(content.peopleConcerned(), entityId, "peopleConcerned"));
        row.setGeneratedAt(content.generatedAt());
        cacheRepository.save(row);
    }

    private GeneratedContent toGeneratedContent(AudiencePulseAspectsCache row) {
        return new GeneratedContent(
                row.getEntityName(),
                readStringListJson(row.getPeopleLoveJson(), row.getEntityId()),
                readStringListJson(row.getPeopleConcernedJson(), row.getEntityId()),
                row.getGeneratedAt());
    }

    private String writeStringListJson(List<String> values, Long entityId, String field) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to serialize audience pulse aspects " + field + " for entity " + entityId, e);
        }
    }

    private List<String> readStringListJson(String json, Long entityId) {
        try {
            return objectMapper.readValue(json, STRING_LIST_TYPE);
        } catch (Exception e) {
            log.error("Failed to deserialize cached audience pulse aspects for entity {}", entityId, e);
            return Collections.emptyList();
        }
    }

    private GeneratedContent generate(Long entityId) {
        ManagedEntity entity = managedEntityRepository.findById(entityId)
                .orElseThrow(() -> new RuntimeException("Entity not found with id: " + entityId));

        Set<String> castAndCrewNames = castAndCrewNames(entity);
        JsonNode node = fetchAspectDrivers(entityId);
        List<String> peopleLove = extractAspects(node, "strengths", entityId, castAndCrewNames);
        List<String> peopleConcerned = extractAspects(node, "weaknesses", entityId, castAndCrewNames);

        return new GeneratedContent(entity.getName(), peopleLove, peopleConcerned, clock.instant());
    }

    /**
     * Every individual word of the entity's director and actor names, lowercased, so a bare surname
     * extracted as a noun can be recognized as a cast/crew mention rather than a genuine aspect.
     * AuraMath's own NER filtering should already catch most of these; this is a second, cheap,
     * entity-specific layer in case NER misses a lowercased or inconsistently-capitalized mention.
     */
    private Set<String> castAndCrewNames(ManagedEntity entity) {
        Set<String> names = new HashSet<>();
        addNameWords(names, entity.getDirector());
        if (entity.getActors() != null) {
            entity.getActors().forEach(actor -> addNameWords(names, actor));
        }
        return names;
    }

    private void addNameWords(Set<String> names, String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return;
        }
        for (String word : fullName.toLowerCase().split("\\s+")) {
            if (!word.isBlank()) {
                names.add(word);
            }
        }
    }

    // Missing/unreachable AuraMath data degrades to null (→ empty aspect lists) rather than
    // propagating an error — a single flaky downstream shouldn't break the whole panel, and this
    // matches AuraMath's own "existing entity with no matching precomputed posts" contract.
    private JsonNode fetchAspectDrivers(Long entityId) {
        try {
            ResponseEntity<String> upstream = auraMathProxy.forwardMarketingGet(
                    WRAPPER_PATH,
                    "/api/marketing/aspect-drivers?entityId=" + entityId,
                    auraMathProperties.getCache().getDefaultTtlSeconds());

            if (!upstream.getStatusCode().is2xxSuccessful()) {
                log.info("aspect-drivers auramath unavailable entityId={} status={}",
                        entityId, upstream.getStatusCode().value());
                return null;
            }
            String body = upstream.getBody();
            return body == null || body.isBlank() ? null : objectMapper.readTree(body);
        } catch (Exception e) {
            log.warn("aspect-drivers auramath fetch failed entityId={}", entityId, e);
            return null;
        }
    }

    private List<String> extractAspects(JsonNode node, String field, Long entityId, Set<String> castAndCrewNames) {
        List<String> aspects = new ArrayList<>();
        if (node == null) {
            return aspects;
        }
        JsonNode arrayNode = node.path(field);
        if (!arrayNode.isArray()) {
            log.warn("aspect-drivers response missing array field '{}' for entity {}", field, entityId);
            return aspects;
        }
        for (JsonNode item : arrayNode) {
            if (aspects.size() >= TOP_N_ASPECTS) {
                break;
            }
            String aspect = item.path("aspect").asText(null);
            if (aspect != null && isDisplayableAspect(aspect, castAndCrewNames)) {
                aspects.add(aspect);
            }
        }
        return aspects;
    }

    /**
     * Excludes AuraMath aspect tokens that aren't genuine movie aspects: social hashtags/@handles,
     * bare 1-2 character tokens, and the entity's own cast/crew names. Belt-and-suspenders on top of
     * AuraMath's own NER/proper-noun filtering (see class doc).
     */
    private boolean isDisplayableAspect(String aspect, Set<String> castAndCrewNames) {
        String trimmed = aspect.trim();
        if (trimmed.length() < 3) {
            return false;
        }
        if (trimmed.startsWith("#") || trimmed.startsWith("@")) {
            return false;
        }
        return !castAndCrewNames.contains(trimmed.toLowerCase());
    }

    private record GeneratedContent(String entityName, List<String> peopleLove, List<String> peopleConcerned, Instant generatedAt) {
    }
}
