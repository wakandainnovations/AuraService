package com.aura.service.service;

import com.aura.service.dto.MomentumCausalReportResponse;
import com.aura.service.dto.MomentumCausalReportResponse.CausalLiftUser;
import com.aura.service.dto.MomentumCausalReportResponse.StatisticalCandidateSection;
import com.aura.service.dto.MomentumCausalReportResponse.TopCausalLiftUsersSection;
import com.aura.service.dto.RecommendedActionCandidate;
import com.aura.service.entity.ManagedEntity;
import com.aura.service.proxy.AuraMathProperties;
import com.aura.service.proxy.AuraMathProxyService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Assembles the post-release "Momentum & Causal Chain" report for a single managed entity, mirroring
 * {@link EntityMarketingReportService}'s pattern: this service owns assembly, {@link
 * MomentumCausalReportPdfService} owns rendering. Unlike that report (which embeds one upstream
 * AuraMath entity-report payload), this one combines four distinct AuraMath sources — F1 VMI, F4
 * causal chains, the F6-enriched language/movie user cohort, and F9's already-generated recommended-
 * action candidates (F5/F7 non-obvious-lever and playbook-sequence subsets) — never re-fetching F5/F7
 * directly, since {@link RecommendedActionCandidateService} already does.
 *
 * <p>Ownership is enforced once, up front, via {@link EntityAccessService#assertOwnedByCurrentUser} —
 * an entity that is missing or not owned by the caller surfaces as a 404 before any upstream call is
 * made. Every section below then degrades independently: a downstream failure or a genuine lack of
 * tracked history both render the same explicit {@code {"status": "insufficient_history"}}-shaped
 * placeholder (matching AuraMath's own F1/F4 contract) rather than an empty section or a propagated
 * error, so the report is always fully shaped even for a freshly-tracked entity.
 */
@Slf4j
@Service
public class MomentumCausalReportService {

    private static final String INSUFFICIENT_HISTORY = "insufficient_history";

    // Reuses the exact wrapper-path labels EntityCausalIntelController uses for the same upstream
    // calls, so proxy logs/metrics correlate regardless of which endpoint triggered the fetch.
    private static final String VMI_WRAPPER_PATH = "/api/entities/{id}/vmi";
    private static final String CAUSAL_CHAINS_WRAPPER_PATH = "/api/entities/{id}/causal-chains";
    private static final String CAUSAL_LIFT_USERS_WRAPPER_PATH =
            "/api/entities/{id}/momentum-report/causal-lift-users";

    private static final String NONOBVIOUS_LEVER_PREFIX = "nonobvious-lever-";
    private static final String PLAYBOOK_SEQUENCE_PREFIX = "playbook-sequence-";

    // "Top" causal-lift users is a curated list, not a full roster dump — same reasoning/cap as
    // RecommendedActionCandidateServiceImpl.MAX_RELEVANT_USERS.
    static final int TOP_CAUSAL_LIFT_USERS_LIMIT = 20;

    private final EntityAccessService entityAccessService;
    private final AuraMathProxyService auraMathProxy;
    private final AuraMathProperties auraMathProps;
    private final RecommendedActionCandidateService candidateService;
    private final ObjectMapper objectMapper;

    public MomentumCausalReportService(EntityAccessService entityAccessService,
                                       AuraMathProxyService auraMathProxy,
                                       AuraMathProperties auraMathProps,
                                       RecommendedActionCandidateService candidateService,
                                       ObjectMapper objectMapper) {
        this.entityAccessService = entityAccessService;
        this.auraMathProxy = auraMathProxy;
        this.auraMathProps = auraMathProps;
        this.candidateService = candidateService;
        this.objectMapper = objectMapper;
    }

    public MomentumCausalReportResponse buildReport(Long entityId) {
        // Mandatory and owner-scoped: 404s if the entity is missing or not owned by the caller — this
        // is what enforces ownership for the whole report, before any upstream call is made.
        ManagedEntity entity = entityAccessService.assertOwnedByCurrentUser(entityId);
        return assemble(entityId, entity);
    }

    /**
     * Same assembly as {@link #buildReport}, but for trusted background callers that have already
     * resolved the entity directly (e.g. {@code EntityMarketingReportService}'s scheduled cache
     * refresh, which runs with no authenticated request to check ownership against) — skips the
     * ownership assertion entirely rather than failing with "no authenticated user".
     */
    public MomentumCausalReportResponse buildReportForEntity(ManagedEntity entity) {
        return assemble(entity.getId(), entity);
    }

    private MomentumCausalReportResponse assemble(Long entityId, ManagedEntity entity) {
        JsonNode vmiTrend = fetchAuraMathJson(VMI_WRAPPER_PATH,
                "/api/marketing/entity/" + entityId + "/vmi", "VMI trend (F1)");
        JsonNode causalChains = fetchAuraMathJson(CAUSAL_CHAINS_WRAPPER_PATH,
                "/api/marketing/entity/" + entityId + "/causal-chains", "causal chains (F4)");
        TopCausalLiftUsersSection topCausalLiftUsers = fetchTopCausalLiftUsers(entity);

        List<RecommendedActionCandidate> candidates = safeCandidates(entityId);
        StatisticalCandidateSection nonObviousLevers = statisticalSection(candidates, NONOBVIOUS_LEVER_PREFIX,
                "No non-obvious-lever findings have cleared the statistical significance bar yet for " +
                        "this entity - the F5 lever-miner batch hasn't run, or too few entities qualify " +
                        "for pooled analysis.");
        StatisticalCandidateSection playbookMatches = statisticalSection(candidates, PLAYBOOK_SEQUENCE_PREFIX,
                "No playbook-sequence findings have cleared the statistical significance bar yet for " +
                        "this entity's (industry, language) cohort - the F7 playbook miner hasn't run, or " +
                        "too few entities qualify for cohort or pooled tiering.");

        return MomentumCausalReportResponse.builder()
                .entityId(entityId)
                .entityName(entity.getName())
                .generatedAt(Instant.now())
                .vmiTrend(vmiTrend)
                .causalChains(causalChains)
                .topCausalLiftUsers(topCausalLiftUsers)
                .nonObviousLevers(nonObviousLevers)
                .playbookMatches(playbookMatches)
                .build();
    }

    // ------------------------------------------------------------------
    // F1 / F4 - verbatim AuraMath JSON (already carries the insufficient_history envelope)
    // ------------------------------------------------------------------

    private JsonNode fetchAuraMathJson(String wrapperPath, String upstreamPath, String sectionLabel) {
        try {
            ResponseEntity<String> upstream = auraMathProxy.forwardGet(
                    wrapperPath, upstreamPath, null, true,
                    (long) auraMathProps.getCache().getDefaultTtlSeconds());
            if (upstream.getStatusCode().is2xxSuccessful()) {
                String body = upstream.getBody();
                if (body != null && !body.isBlank()) {
                    return objectMapper.readTree(body);
                }
            }
            log.info("momentum-report {} unavailable status={}", sectionLabel, upstream.getStatusCode().value());
        } catch (Exception e) {
            log.warn("momentum-report {} fetch failed", sectionLabel, e);
        }
        return insufficientHistoryNode(
                "AuraMath's " + sectionLabel + " lookup was unavailable when this report was generated.");
    }

    private JsonNode insufficientHistoryNode(String details) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("status", INSUFFICIENT_HISTORY);
        node.put("details", details);
        return node;
    }

    // ------------------------------------------------------------------
    // F6 - causal-lift-scored users for this entity's language/movie cohort
    // ------------------------------------------------------------------

    private TopCausalLiftUsersSection fetchTopCausalLiftUsers(ManagedEntity entity) {
        String language = entity.getLanguage();
        String movieName = entity.getName();
        if (language == null || language.isBlank() || movieName == null || movieName.isBlank()) {
            return insufficientUsersSection(
                    "This entity has no language/name on file to resolve a causal-lift user cohort.");
        }

        String upstreamPath = "/api/marketing/language/" + encodeSegment(language)
                + "/movie/" + encodeSegment(movieName) + "/users";
        JsonNode body;
        try {
            ResponseEntity<String> upstream = auraMathProxy.forwardGet(
                    CAUSAL_LIFT_USERS_WRAPPER_PATH, upstreamPath, null, true,
                    (long) auraMathProps.getCache().getDefaultTtlSeconds());
            if (!upstream.getStatusCode().is2xxSuccessful()
                    || upstream.getBody() == null || upstream.getBody().isBlank()) {
                log.info("momentum-report causal-lift users unavailable status={}", upstream.getStatusCode().value());
                return insufficientUsersSection(
                        "AuraMath's causal-lift user cohort lookup was unavailable when this report was generated.");
            }
            body = objectMapper.readTree(upstream.getBody());
        } catch (Exception e) {
            log.warn("momentum-report causal-lift users fetch failed entityId={}", entity.getId(), e);
            return insufficientUsersSection(
                    "AuraMath's causal-lift user cohort lookup was unavailable when this report was generated.");
        }

        List<CausalLiftUser> users = new ArrayList<>();
        JsonNode usersNode = body.get("users");
        if (usersNode != null && usersNode.isArray()) {
            for (JsonNode u : usersNode) {
                JsonNode liftNode = u.get("causal_lift_score");
                // Only users a real user_causal_lift_scores row backs - LEFT JOIN semantics upstream
                // mean every other tracked user is simply absent here, never a guessed zero.
                if (liftNode == null || liftNode.isNull()) {
                    continue;
                }
                users.add(CausalLiftUser.builder()
                        .globalUserId(textOrNull(u, "global_user_id"))
                        .causalLiftScore(liftNode.asDouble())
                        .nQualifyingEvents(longOrNull(u, "n_qualifying_events"))
                        .confidence(textOrNull(u, "confidence"))
                        .mentionCount(longOrNull(u, "mention_count"))
                        .engagementRating(doubleOrNull(u, "engagement_rating"))
                        .build());
            }
        }

        if (users.isEmpty()) {
            return insufficientUsersSection(
                    "No users have a qualifying causal-lift score yet for this entity's language/movie " +
                            "cohort - the causal-lift scoring batch hasn't run, or too few qualifying " +
                            "engagement events exist yet.");
        }

        // Confidence=HIGH entries first (as a group), LOW entries after - each group then ranked by
        // causal_lift_score descending, per the report's ranking contract.
        users.sort(Comparator
                .comparing((CausalLiftUser u) -> "HIGH".equalsIgnoreCase(u.getConfidence()) ? 0 : 1)
                .thenComparing(Comparator.comparing(CausalLiftUser::getCausalLiftScore).reversed()));

        return TopCausalLiftUsersSection.builder()
                .status("ok")
                .users(users.stream().limit(TOP_CAUSAL_LIFT_USERS_LIMIT).toList())
                .build();
    }

    private TopCausalLiftUsersSection insufficientUsersSection(String details) {
        return TopCausalLiftUsersSection.builder()
                .status(INSUFFICIENT_HISTORY)
                .details(details)
                .users(List.of())
                .build();
    }

    // ------------------------------------------------------------------
    // F9 - non-obvious-lever / playbook-sequence candidate subsets (reused, not re-fetched)
    // ------------------------------------------------------------------

    private List<RecommendedActionCandidate> safeCandidates(Long entityId) {
        try {
            return candidateService.buildCandidateActions(entityId);
        } catch (Exception e) {
            log.warn("momentum-report recommended-action candidates unavailable entityId={}", entityId, e);
            return List.of();
        }
    }

    private StatisticalCandidateSection statisticalSection(List<RecommendedActionCandidate> allCandidates,
                                                            String candidateIdPrefix,
                                                            String insufficientDetails) {
        List<RecommendedActionCandidate> matched = allCandidates.stream()
                .filter(c -> c.candidateId() != null && c.candidateId().startsWith(candidateIdPrefix))
                .toList();
        if (matched.isEmpty()) {
            return StatisticalCandidateSection.builder()
                    .status(INSUFFICIENT_HISTORY)
                    .details(insufficientDetails)
                    .candidates(List.of())
                    .build();
        }
        return StatisticalCandidateSection.builder()
                .status("ok")
                .candidates(matched)
                .build();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static String textOrNull(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private static Long longOrNull(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asLong() : null;
    }

    private static Double doubleOrNull(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asDouble() : null;
    }

    /** Mirrors AuraMathMarketingProxyController's segment encoding: forwardGet expects each path
     *  segment pre-encoded, with '+' normalized to '%20' since these are path, not query, segments. */
    private static String encodeSegment(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
