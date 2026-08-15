package com.aura.service.service;

import com.aura.service.dto.RecommendedActionCandidate;
import com.aura.service.dto.RecommendedActionItem;
import com.aura.service.dto.RecommendedActionsResponse;
import com.aura.service.entity.ManagedEntity;
import com.aura.service.entity.RecommendedActionsCache;
import com.aura.service.enums.RecommendedActionStatus;
import com.aura.service.exception.ResourceNotFoundException;
import com.aura.service.repository.ManagedEntityRepository;
import com.aura.service.repository.RecommendedActionsCacheRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * <p>Generation is persisted to {@link RecommendedActionsCache}. Unlike the simple fixed-cadence
 * refresh used by {@link CommandCenterSummaryService}/{@link AudiencePulseAspectsService}, this
 * feature's refresh lifecycle has two stages - see {@link #onApplicationReady()} for the startup pass
 * over a small priority movie list and {@link #runFullRefreshCycle()} for the steady-state, spaced,
 * all-entities cycle that follows it 24h later. The facts a plan is built from (genre, budget,
 * historical comps) change rarely, so there's no value in re-running the LLM call more than about once
 * a day per entity; what changes daily is only which phase of the plan is "current", which
 * {@link #getRecommendedActions} computes live against {@code entity.releaseDate} on every call rather
 * than baking it into the cached plan.
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

    // Movies that need a plan available right away rather than waiting for the first full cycle
    // (up to 24h out) - refreshed synchronously, back-to-back, at startup. Matched by exact
    // ManagedEntity.name.
    private static final List<String> STARTUP_PRIORITY_MOVIE_NAMES = List.of("Toxic", "GD Naidu", "Lord Gaaga");
    private static final Duration FULL_CYCLE_INTERVAL = Duration.ofHours(24);
    private static final Duration PER_ENTITY_SPACING = Duration.ofHours(1);

    private final ManagedEntityRepository managedEntityRepository;
    private final RecommendedActionsCacheRepository cacheRepository;
    private final RecommendedActionCandidateService candidateService;
    private final LLMService llmService;
    private final Clock clock;
    private final TaskScheduler taskScheduler;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${llm.prompt.generate.recommended.actions}")
    private String llmPrompt;

    // Self-injected proxy: calls to refreshOneEntity() below must go through Spring's proxy (not a
    // direct this.refreshOneEntity(...) self-call) for its @Transactional advice to actually apply -
    // required because these calls run off scheduler threads, which have no request-bound Hibernate
    // session the way an HTTP request does under open-in-view (see refreshOneEntity's own doc for why
    // that matters). @Lazy avoids the circular-bean chicken/egg problem at construction time.
    @Autowired
    @Lazy
    private RecommendedActionsService self;

    public RecommendedActionsService(
            ManagedEntityRepository managedEntityRepository,
            RecommendedActionsCacheRepository cacheRepository,
            RecommendedActionCandidateService candidateService,
            LLMService llmService,
            Clock clock,
            TaskScheduler taskScheduler) {
        this.managedEntityRepository = managedEntityRepository;
        this.cacheRepository = cacheRepository;
        this.candidateService = candidateService;
        this.llmService = llmService;
        this.clock = clock;
        this.taskScheduler = taskScheduler;
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

        // This panel is "what to do right now" - an action the marketing team already marked DONE or
        // IRRELEVANT (see updateActionStatus) has nothing left to act on, so it's excluded here even
        // though it's retained (never deleted) in the cached plan for the /all history endpoint.
        List<RecommendedActionItem> activeActions = content.actions().stream()
                .filter(a -> a.getStatus() == RecommendedActionStatus.ACTIVE)
                .toList();

        Integer daysToRelease = todayOffsetFromRelease(entity.getReleaseDate());
        List<RecommendedActionItem> actions = (allPhases || daysToRelease == null)
                ? activeActions
                : filterToCurrentWindow(activeActions, daysToRelease);
        if (actions.isEmpty() && !activeActions.isEmpty()) {
            List<RecommendedActionItem> fallback = (daysToRelease != null && daysToRelease > 0)
                    ? filterOutExpiredPreRelease(activeActions)
                    : activeActions;
            actions = fallback.isEmpty() ? activeActions : fallback;
        }

        return new RecommendedActionsResponse(entityId, content.entityName(), daysToRelease, actions, content.generatedAt());
    }

    /**
     * Full accumulated plan for an entity - every action ever recommended, past or present, each
     * carrying whatever status the marketing team last set on it (default {@link
     * RecommendedActionStatus#ACTIVE} for one never explicitly updated). Unlike {@link
     * #getRecommendedActions}, this never filters by status or by today's execution window - it's the
     * "what has and hasn't been handled" audit view, not the "what to do today" panel. {@code
     * statusFilter} narrows to one status (e.g. only DONE, or only ACTIVE) when non-null.
     */
    @Transactional(readOnly = true)
    public RecommendedActionsResponse getAllRecommendedActions(Long entityId, RecommendedActionStatus statusFilter) {
        ManagedEntity entity = managedEntityRepository.findById(entityId)
                .orElseThrow(() -> new ResourceNotFoundException("Entity not found with id: " + entityId));
        RecommendedActionsCache row = cacheRepository.findByEntityId(entityId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No recommended actions have been generated yet for entity " + entityId));

        List<RecommendedActionItem> actions = readActionsJson(row);
        if (statusFilter != null) {
            actions = actions.stream().filter(a -> a.getStatus() == statusFilter).toList();
        }

        Integer daysToRelease = todayOffsetFromRelease(entity.getReleaseDate());
        return new RecommendedActionsResponse(entityId, row.getEntityName(), daysToRelease, actions, row.getGeneratedAt());
    }

    /**
     * Marks a single cached action's status (e.g. DONE once the marketing team has acted on it, or
     * IRRELEVANT if it doesn't apply to this movie), matched by the stable {@code candidateId} it was
     * built from. Does not trigger regeneration or touch any other action in the plan.
     */
    @Transactional
    public RecommendedActionItem updateActionStatus(Long entityId, String candidateId, RecommendedActionStatus status) {
        RecommendedActionsCache row = cacheRepository.findByEntityId(entityId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No recommended actions have been generated yet for entity " + entityId));

        List<RecommendedActionItem> actions = readActionsJson(row);
        RecommendedActionItem target = actions.stream()
                .filter(a -> candidateId.equals(a.getCandidateId()))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No recommended action '" + candidateId + "' found for entity " + entityId));

        target.setStatus(status);
        row.setActionsJson(writeActionsJson(actions, entityId));
        cacheRepository.save(row);
        return target;
    }

    /**
     * Kicks off the refresh lifecycle once, at application startup: an immediate, synchronous pass
     * over {@link #STARTUP_PRIORITY_MOVIE_NAMES} so those panels aren't empty while the first full
     * cycle is still up to {@link #FULL_CYCLE_INTERVAL} away, then schedules that first full cycle.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        Instant startupTime = clock.instant();
        refreshPriorityMoviesAtStartup();
        scheduleNextFullCycle(startupTime);
    }

    private void refreshPriorityMoviesAtStartup() {
        List<ManagedEntity> priorityEntities = managedEntityRepository.findAll().stream()
                .filter(e -> STARTUP_PRIORITY_MOVIE_NAMES.contains(e.getName()))
                .toList();
        log.info("Refreshing recommended action plans at startup for {} priority movie(s)",
                priorityEntities.size());
        for (ManagedEntity entity : priorityEntities) {
            self.refreshOneEntity(entity.getId());
        }
    }

    /**
     * The steady-state refresh: every managed entity (not just the startup priority list), each
     * spaced {@link #PER_ENTITY_SPACING} apart so one cycle doesn't fire N concurrent AuraMath lookups
     * at once. Schedules its own successor cycle once the last entity's refresh completes - see
     * {@link #scheduleNextFullCycle}.
     */
    private void runFullRefreshCycle() {
        Instant cycleStart = clock.instant();
        List<ManagedEntity> entities = managedEntityRepository.findAll();
        log.info("Starting full recommended-actions refresh cycle for {} entities, {} apart",
                entities.size(), PER_ENTITY_SPACING);
        if (entities.isEmpty()) {
            scheduleNextFullCycle(cycleStart);
            return;
        }
        for (int i = 0; i < entities.size(); i++) {
            Long entityId = entities.get(i).getId();
            Instant runAt = cycleStart.plus(PER_ENTITY_SPACING.multipliedBy(i));
            boolean isLastEntity = i == entities.size() - 1;
            taskScheduler.schedule(() -> {
                self.refreshOneEntity(entityId);
                if (isLastEntity) {
                    scheduleNextFullCycle(cycleStart);
                }
            }, runAt);
        }
    }

    /**
     * Schedules the next full cycle to start {@link #FULL_CYCLE_INTERVAL} after the previous cycle's
     * own start - not after it finishes. When a cycle's per-entity spacing pushes its last entity past
     * that mark already (e.g. 36 entities * 1h spacing = 36h, past the 24h target), the computed
     * instant is already in the past by the time this runs (right after the last entity's refresh),
     * and {@link TaskScheduler} executes a past-due instant immediately - so a long cycle chains
     * straight into the next one instead of stacking an idle 24h wait on top of an already-overrun run.
     */
    private void scheduleNextFullCycle(Instant previousCycleStart) {
        Instant nextCycleStart = previousCycleStart.plus(FULL_CYCLE_INTERVAL);
        taskScheduler.schedule(this::runFullRefreshCycle, nextCycleStart);
    }

    /**
     * Regenerates and persists a single entity's plan; isolated per entity (its own transaction, own
     * try/catch) so one failure - e.g. a bad AuraMath response - doesn't take down the rest of a
     * startup pass or cycle, and so a crash mid-cycle doesn't lose already-persisted entities the way
     * one shared transaction across the whole cycle would. Transactional because this runs off a
     * scheduler thread, which has no request-bound Hibernate session the way an HTTP request does
     * under open-in-view - see AudiencePulseAspectsService.getAspects's doc comment for the same
     * constraint. Must be called via {@link #self}, not directly - see that field's doc comment.
     */
    @Transactional
    public void refreshOneEntity(Long entityId) {
        try {
            regenerateAndStore(entityId);
        } catch (Exception e) {
            log.error("Failed to refresh recommended actions for entity {}", entityId, e);
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
        GeneratedContent merged = mergeWithHistory(entityId, generated);
        persist(entityId, merged);
        return merged;
    }

    /**
     * Merges a freshly generated action list onto whatever's already cached for this entity, so a
     * regeneration never deletes an action the marketing team may already have marked DONE/IRRELEVANT
     * on - only the LLM's current selection can change per cycle, not the team's history. Matched by
     * {@code candidateId}: a candidate re-selected this cycle keeps its existing status but otherwise
     * takes the fresh content (numbers/prose may have moved since the candidate is grounded in live
     * data); a previously-cached action not re-selected this cycle is carried forward unchanged rather
     * than dropped; a candidate never seen before is added at its default {@link
     * RecommendedActionStatus#ACTIVE}. Cache rows written before {@code candidateId} existed have
     * {@code null} ids on their old items and so can't be matched - those are superseded by this
     * entity's first post-upgrade regeneration rather than preserved, since there's nothing to key them
     * on.
     */
    private GeneratedContent mergeWithHistory(Long entityId, GeneratedContent fresh) {
        var existing = cacheRepository.findByEntityId(entityId);
        if (existing.isEmpty()) {
            return fresh;
        }

        List<RecommendedActionItem> historical = readActionsJson(existing.get());
        Map<String, RecommendedActionItem> historicalById = new LinkedHashMap<>();
        for (RecommendedActionItem item : historical) {
            if (item.getCandidateId() != null) {
                historicalById.put(item.getCandidateId(), item);
            }
        }

        Set<String> freshIds = new HashSet<>();
        List<RecommendedActionItem> merged = new ArrayList<>();
        for (RecommendedActionItem item : fresh.actions()) {
            freshIds.add(item.getCandidateId());
            RecommendedActionItem previous = historicalById.get(item.getCandidateId());
            if (previous != null) {
                item.setStatus(previous.getStatus());
            }
            merged.add(item);
        }
        for (RecommendedActionItem item : historical) {
            if (item.getCandidateId() != null && !freshIds.contains(item.getCandidateId())) {
                merged.add(item);
            }
        }

        return new GeneratedContent(fresh.entityName(), merged, fresh.daysToReleaseAtGeneration(), fresh.generatedAt());
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
                candidate.candidateId(),
                candidate.category(),
                title,
                ensureReasonNamesExampleHandles(reason, candidate.exampleHandles()),
                candidate.confidencePct(),
                candidate.factorName(),
                candidate.windowStartDaysFromRelease(),
                candidate.windowEndDaysFromRelease(),
                candidate.windowLabel(),
                candidate.exampleHandles(),
                RecommendedActionStatus.ACTIVE);
    }

    // The prompt asks the LLM to name real example handles verbatim in its reason, but instruction-
    // following isn't guaranteed - handles are exactly the concrete, actionable detail this feature
    // exists to surface, so this doesn't leave it to chance: if none of this candidate's real handles
    // made it into the LLM's reason text, append them deterministically rather than silently accept a
    // generic reason. A no-op whenever the LLM already named at least one (including the fallback
    // paths, whose reason is built straight from supportingFacts and so already contains them).
    private static String ensureReasonNamesExampleHandles(String reason, List<String> exampleHandles) {
        if (exampleHandles.isEmpty() || exampleHandles.stream().anyMatch(reason::contains)) {
            return reason;
        }
        return reason + " Example account(s): " + String.join(", ", exampleHandles) + ".";
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
