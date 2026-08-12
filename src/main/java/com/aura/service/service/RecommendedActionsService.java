package com.aura.service.service;

import com.aura.service.dto.RecommendedActionCandidate;
import com.aura.service.dto.RecommendedActionItem;
import com.aura.service.dto.RecommendedActionsResponse;
import com.aura.service.entity.ManagedEntity;
import com.aura.service.entity.RecommendedActionsCache;
import com.aura.service.repository.ManagedEntityRepository;
import com.aura.service.repository.RecommendedActionsCacheRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 2 of the "Recommended Actions" Command Center panel: takes the fully-numeric candidates from
 * {@link RecommendedActionCandidateService} (Phase 1 - never re-derived or second-guessed here) and
 * runs the single LLM call in this feature to select which candidates are worth surfacing for this
 * specific movie and write natural-language prose about them. The LLM never sees a schema field for
 * category, confidencePct, or either window day-offset, and never supplies one - those three numbers
 * flow from the candidate record to {@link RecommendedActionItem} untouched; the LLM's only output is
 * which candidateIds to keep and what to say about them.
 *
 * <p>Generation is persisted to {@link RecommendedActionsCache} and refreshed for every entity by
 * {@link #refreshAllActionPlans()} once a day - unlike the 6-hour cadence used by
 * {@link CommandCenterSummaryService}/{@link AudiencePulseAspectsService}, the facts this plan is
 * built from (genre, budget, historical comps) change rarely, so there's no value in re-running the
 * LLM call more than once a day; what changes daily is only which phase of the plan is "current",
 * which {@link #getRecommendedActions} computes live against {@code entity.releaseDate} on every call
 * rather than baking it into the cached plan.
 */
@Slf4j
@Service
public class RecommendedActionsService {

    private static final String CANDIDATE_DATA_PLACEHOLDER = "[Candidate Actions Data]";
    private static final Pattern DIGIT_SEQUENCE = Pattern.compile("\\d+");
    // Catches a weaker/local LLM literally echoing a bracketed example from the prompt (e.g.
    // "[Movie]", "[genre]") into its actual output instead of substituting a real value - observed
    // in production against the real-movie-reference latitude added for organic/low-cost factors.
    private static final Pattern BRACKET_PLACEHOLDER = Pattern.compile("\\[[^\\[\\]]{1,60}]");
    private static final TypeReference<List<RecommendedActionItem>> ACTION_LIST_TYPE = new TypeReference<>() {
    };

    private final ManagedEntityRepository managedEntityRepository;
    private final RecommendedActionsCacheRepository cacheRepository;
    private final RecommendedActionCandidateService candidateService;
    private final LLMService llmService;
    private final Clock clock;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${llm.prompt.generate.recommended.actions}")
    private String llmPrompt;

    public RecommendedActionsService(
            ManagedEntityRepository managedEntityRepository,
            RecommendedActionsCacheRepository cacheRepository,
            RecommendedActionCandidateService candidateService,
            LLMService llmService,
            Clock clock) {
        this.managedEntityRepository = managedEntityRepository;
        this.cacheRepository = cacheRepository;
        this.candidateService = candidateService;
        this.llmService = llmService;
        this.clock = clock;
    }

    /**
     * @param allPhases when true, returns the whole cached plan ungrouped/unfiltered (the full
     *                  campaign roadmap) instead of only the actions whose window currently contains
     *                  today. An entity with no releaseDate can't have a "current" window computed, so
     *                  it always gets the full-plan behavior regardless of this flag. Also falls back
     *                  when the window filter would leave nothing - the curated factor windows (see
     *                  {@code WINDOW_BY_FACTOR}) don't blanket every day of a movie's runway, so "no
     *                  factor's window covers today" is a real gap, not a signal that there's nothing
     *                  to recommend; the panel should never render empty when a grounded plan actually
     *                  exists for this entity. Once the movie has released, that fallback excludes
     *                  actions whose window is entirely pre-release (e.g. trailer/teaser timing, first-
     *                  single timing) since those are no longer actionable, falling back further to the
     *                  full plan only if nothing post-release-relevant remains.
     */
    @Transactional
    public RecommendedActionsResponse getRecommendedActions(Long entityId, boolean refresh, boolean allPhases) {
        ManagedEntity entity = managedEntityRepository.findById(entityId)
                .orElseThrow(() -> new RuntimeException("Entity not found with id: " + entityId));
        GeneratedContent content = getCachedOrGenerate(entityId, refresh);

        Integer daysToRelease = todayOffsetFromRelease(entity.getReleaseDate());
        List<RecommendedActionItem> actions = (allPhases || daysToRelease == null)
                ? content.actions()
                : filterToCurrentWindow(content.actions(), daysToRelease);
        if (actions.isEmpty() && !content.actions().isEmpty()) {
            List<RecommendedActionItem> fallback = (daysToRelease != null && daysToRelease > 0)
                    ? filterOutExpiredPreRelease(content.actions())
                    : content.actions();
            actions = fallback.isEmpty() ? content.actions() : fallback;
        }

        return new RecommendedActionsResponse(entityId, content.entityName(), daysToRelease, actions, content.generatedAt());
    }

    /**
     * Refreshes the cached action plan for every managed entity. Runs at startup and every 24 hours
     * after that (see class doc for why this cadence differs from the 6-hour panels); one entity's
     * failure is logged and skipped rather than aborting the run.
     */
    @Scheduled(fixedDelayString = "PT24H")
    @Transactional
    public void refreshAllActionPlans() {
        List<ManagedEntity> entities = managedEntityRepository.findAll();
        log.info("Refreshing recommended action plans for {} entities", entities.size());
        for (ManagedEntity entity : entities) {
            try {
                regenerateAndStore(entity.getId());
            } catch (Exception e) {
                log.error("Failed to refresh recommended actions for entity {}", entity.getId(), e);
            }
        }
    }

    private static List<RecommendedActionItem> filterToCurrentWindow(List<RecommendedActionItem> actions, int todayOffset) {
        return actions.stream()
                .filter(a -> todayOffset >= a.getWindowStartDaysFromRelease() && todayOffset <= a.getWindowEndDaysFromRelease())
                .toList();
    }

    // Drops actions whose window ends before release day (e.g. trailer/teaser timing, first-single
    // timing) - once a movie has released, those pre-release-only beats are no longer actionable and
    // shouldn't resurface just because no window covers today.
    private static List<RecommendedActionItem> filterOutExpiredPreRelease(List<RecommendedActionItem> actions) {
        return actions.stream()
                .filter(a -> a.getWindowEndDaysFromRelease() >= 0)
                .toList();
    }

    // Signed day-offset of "today" from entity.releaseDate, using the same sign convention as
    // RecommendedActionCandidate's own window offsets (negative = before release, positive = after) so
    // the two can be compared directly. Null when the entity has no releaseDate - callers fall back to
    // the full, unfiltered plan in that case rather than computing a meaningless window membership.
    private Integer todayOffsetFromRelease(LocalDate releaseDate) {
        if (releaseDate == null) {
            return null;
        }
        return (int) ChronoUnit.DAYS.between(releaseDate, LocalDate.now(clock));
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
        RecommendedActionsCache row = cacheRepository.findByEntityId(entityId)
                .orElseGet(RecommendedActionsCache::new);
        row.setEntityId(entityId);
        row.setEntityName(content.entityName());
        row.setActionsJson(writeActionsJson(content.actions(), entityId));
        row.setDaysToReleaseAtGeneration(content.daysToReleaseAtGeneration());
        row.setGeneratedAt(content.generatedAt());
        cacheRepository.save(row);
    }

    private GeneratedContent toGeneratedContent(RecommendedActionsCache row) {
        return new GeneratedContent(
                row.getEntityName(), readActionsJson(row), row.getDaysToReleaseAtGeneration(), row.getGeneratedAt());
    }

    private String writeActionsJson(List<RecommendedActionItem> actions, Long entityId) {
        try {
            return objectMapper.writeValueAsString(actions);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize recommended actions for entity " + entityId, e);
        }
    }

    private List<RecommendedActionItem> readActionsJson(RecommendedActionsCache row) {
        try {
            return objectMapper.readValue(row.getActionsJson(), ACTION_LIST_TYPE);
        } catch (Exception e) {
            log.error("Failed to deserialize cached recommended actions for entity {}", row.getEntityId(), e);
            return Collections.emptyList();
        }
    }

    private GeneratedContent generate(Long entityId) {
        ManagedEntity entity = managedEntityRepository.findById(entityId)
                .orElseThrow(() -> new RuntimeException("Entity not found with id: " + entityId));

        List<RecommendedActionCandidate> candidates = candidateService.buildCandidateActions(entityId);
        Integer daysToRelease = todayOffsetFromRelease(entity.getReleaseDate());
        int daysToReleaseAtGeneration = daysToRelease != null ? daysToRelease : 0;

        List<RecommendedActionItem> actions = candidates.isEmpty()
                ? List.of()
                : selectAndPhraseWithLlm(entity, candidates, entityId);

        return new GeneratedContent(entity.getName(), actions, daysToReleaseAtGeneration, clock.instant());
    }

    /**
     * The one LLM call in this feature: select which candidates are worth surfacing and write prose
     * about them. Never asks the LLM for (and never accepts back) category, confidencePct, or either
     * window day-offset - those are merged back onto the selection from the original candidate record
     * by id. Falls back to {@link #fallbackActions} (unfiltered candidates, Java-built generic reasons)
     * if the LLM call fails, its response can't be parsed, or it selects nothing usable - the panel
     * should never render empty just because the LLM had a bad reply.
     */
    private List<RecommendedActionItem> selectAndPhraseWithLlm(
            ManagedEntity entity, List<RecommendedActionCandidate> candidates, Long entityId) {
        Map<String, RecommendedActionCandidate> byId = new LinkedHashMap<>();
        for (RecommendedActionCandidate c : candidates) {
            byId.put(c.candidateId(), c);
        }

        String reply;
        try {
            reply = llmService.generateReply(buildPrompt(entity, candidates));
        } catch (Exception e) {
            log.warn("Recommended actions LLM call failed for entity {} — falling back to unfiltered candidates",
                    entityId, e);
            return fallbackActions(candidates);
        }

        JsonNode node;
        try {
            node = objectMapper.readTree(reply);
        } catch (Exception e) {
            log.warn("Recommended actions LLM response could not be parsed as JSON for entity {} — falling back " +
                    "to unfiltered candidates", entityId, e);
            return fallbackActions(candidates);
        }
        if (!node.isArray()) {
            log.warn("Recommended actions LLM response was not a JSON array for entity {} — falling back to " +
                    "unfiltered candidates", entityId);
            return fallbackActions(candidates);
        }

        List<RecommendedActionItem> selected = new ArrayList<>();
        for (JsonNode item : node) {
            String candidateId = item.hasNonNull("candidateId") ? item.get("candidateId").asText() : null;
            RecommendedActionCandidate candidate = candidateId == null ? null : byId.get(candidateId);
            if (candidate == null) {
                log.warn("Recommended actions LLM response referenced unknown candidateId '{}' for entity {} — " +
                        "dropping", candidateId, entityId);
                continue;
            }
            String reason = item.hasNonNull("reason") ? item.get("reason").asText().trim() : "";
            if (reason.isEmpty()) {
                continue;
            }
            String title = item.hasNonNull("title") ? item.get("title").asText().trim() : "";
            if (title.isEmpty()) {
                title = candidate.factorName();
            }
            warnIfReasonHasUngroundedNumber(candidateId, reason, candidate.supportingFacts(), entityId);
            if (BRACKET_PLACEHOLDER.matcher(reason).find() || BRACKET_PLACEHOLDER.matcher(title).find()) {
                log.warn("Recommended actions LLM output a literal bracket placeholder for candidate '{}' " +
                        "(entity {}) — using this candidate's generic fallback reason instead. title=\"{}\" reason=\"{}\"",
                        candidateId, entityId, title, reason);
                selected.add(toActionItem(candidate, candidate.factorName(), String.join(" ", candidate.supportingFacts())));
                continue;
            }
            selected.add(toActionItem(candidate, title, reason));
        }

        if (selected.isEmpty()) {
            log.warn("Recommended actions LLM response produced no usable selections for entity {} — falling " +
                    "back to unfiltered candidates", entityId);
            return fallbackActions(candidates);
        }
        return selected;
    }

    // Fallback for an LLM failure/unparseable reply/empty selection: every server-computed candidate,
    // unfiltered, with a generic reason built only from its own supporting facts - so the panel still
    // shows something grounded in real data rather than going empty because of an LLM hiccup.
    private static List<RecommendedActionItem> fallbackActions(List<RecommendedActionCandidate> candidates) {
        return candidates.stream()
                .map(c -> toActionItem(c, c.factorName(), String.join(" ", c.supportingFacts())))
                .toList();
    }

    private static RecommendedActionItem toActionItem(RecommendedActionCandidate candidate, String title, String reason) {
        return new RecommendedActionItem(
                candidate.category(),
                title,
                reason,
                candidate.confidencePct(),
                candidate.factorName(),
                candidate.windowStartDaysFromRelease(),
                candidate.windowEndDaysFromRelease(),
                candidate.windowLabel());
    }

    // Cheap defensive check (not a hard requirement, but worth it given how central "no invented
    // numbers" is to this feature): every digit sequence appearing in the LLM's reason should also
    // appear somewhere in that candidate's own supportingFacts. A mismatch doesn't block the reason
    // from being used - it's logged so an invented number can be caught and investigated.
    private static void warnIfReasonHasUngroundedNumber(
            String candidateId, String reason, List<String> supportingFacts, Long entityId) {
        String factsJoined = String.join(" ", supportingFacts);
        Matcher matcher = DIGIT_SEQUENCE.matcher(reason);
        while (matcher.find()) {
            String digits = matcher.group();
            if (!factsJoined.contains(digits)) {
                log.warn("Recommended actions LLM reason for candidate '{}' (entity {}) contains digit sequence " +
                                "'{}' not found in its own supporting facts — possible invented number. Reason: \"{}\"",
                        candidateId, entityId, digits, reason);
            }
        }
    }

    private String buildPrompt(ManagedEntity entity, List<RecommendedActionCandidate> candidates) {
        ObjectNode root = objectMapper.createObjectNode();

        ObjectNode movie = root.putObject("movie");
        movie.put("name", entity.getName());
        putIfPresent(movie, "genre", entity.getGenre());
        putIfPresent(movie, "language", entity.getLanguage());
        putIfPresent(movie, "industry", entity.getIndustry());
        if (entity.getBudget() != null) {
            movie.put("budget", entity.getBudget());
        }
        Integer daysToRelease = todayOffsetFromRelease(entity.getReleaseDate());
        if (daysToRelease != null) {
            movie.put("daysToRelease", daysToRelease);
        }

        ArrayNode candidatesNode = root.putArray("candidates");
        for (RecommendedActionCandidate c : candidates) {
            ObjectNode n = candidatesNode.addObject();
            n.put("candidateId", c.candidateId());
            n.put("factor", c.factorName());
            n.put("category", c.category().name());
            n.put("confidencePct", c.confidencePct());
            n.put("windowStartDaysFromRelease", c.windowStartDaysFromRelease());
            n.put("windowEndDaysFromRelease", c.windowEndDaysFromRelease());
            n.put("windowLabel", c.windowLabel());
            ArrayNode facts = n.putArray("supportingFacts");
            c.supportingFacts().forEach(facts::add);
        }

        return llmPrompt.replace(CANDIDATE_DATA_PLACEHOLDER, root.toString());
    }

    private static void putIfPresent(ObjectNode node, String field, String value) {
        if (value != null && !value.isBlank()) {
            node.put(field, value);
        }
    }

    private record GeneratedContent(
            String entityName, List<RecommendedActionItem> actions, int daysToReleaseAtGeneration, Instant generatedAt) {
    }
}
