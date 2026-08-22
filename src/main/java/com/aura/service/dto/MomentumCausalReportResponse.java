package com.aura.service.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * Post-release "Momentum & Causal Chain" report for a single managed entity — audience-behavior
 * pattern analysis over an entity's tracked history, distinct from the pre-release
 * {@code PREDICTIVE_LAUNCH_FEATURE_BREAKDOWN.md} "Launch Plan" report.
 *
 * <p>Assembled by {@link com.aura.service.service.MomentumCausalReportService} from four upstream
 * AuraMath sources:
 * <ul>
 *   <li>{@link #vmiTrend} — F1 {@code entity_daily_vmi} series + peak day, forwarded verbatim.</li>
 *   <li>{@link #causalChains} — F4 {@code causal_precedence_chains}/{@code _edges}, forwarded
 *       verbatim with each edge's full evidence (lag, FDR q-value, effect size, supporting-entity
 *       count) intact.</li>
 *   <li>{@link #topCausalLiftUsers} — the F6-enriched language/movie user cohort, filtered to users
 *       with a real causal-lift score and ranked confidence-first.</li>
 *   <li>{@link #nonObviousLevers} / {@link #playbookMatches} — the subset of F9's already-generated
 *       {@link RecommendedActionCandidate} list backed by AuraMath's F5/F7 statistical mining.</li>
 * </ul>
 *
 * <p>{@link #vmiTrend} and {@link #causalChains} carry AuraMath's own response shape verbatim,
 * including its {@code {"status": "insufficient_history", "details": "..."}} envelope when the
 * entity doesn't clear that table's qualifying-history bar. {@link #topCausalLiftUsers},
 * {@link #nonObviousLevers} and {@link #playbookMatches} mirror that same envelope shape
 * ({@code status}/{@code details}) when this service itself has nothing to show for that section —
 * every section always renders an explicit placeholder, never an empty list or a propagated error.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MomentumCausalReportResponse {

    private Long entityId;
    private String entityName;

    /** Server time the report was assembled. */
    private Instant generatedAt;

    /** F1: AuraMath's {@code GET /api/marketing/entity/{id}/vmi} payload, forwarded verbatim. */
    private JsonNode vmiTrend;

    /** F4: AuraMath's {@code GET /api/marketing/entity/{id}/causal-chains} payload, forwarded verbatim. */
    private JsonNode causalChains;

    /** F6: this entity's language/movie user cohort, filtered/ranked by causal-lift score. */
    private TopCausalLiftUsersSection topCausalLiftUsers;

    /** F9 non-obvious-lever candidates (AuraMath F5), reused from the already-generated candidate list. */
    private StatisticalCandidateSection nonObviousLevers;

    /** F9 playbook-sequence candidates (AuraMath F7), reused from the already-generated candidate list. */
    private StatisticalCandidateSection playbookMatches;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TopCausalLiftUsersSection {
        /** {@code "ok"} or {@code "insufficient_history"}. */
        private String status;
        /** Populated only when {@link #status} is {@code "insufficient_history"}. */
        private String details;
        /** Causal-lift-scored users, HIGH confidence first, each group ranked by score descending. */
        @Builder.Default
        private List<CausalLiftUser> users = List.of();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CausalLiftUser {
        private String globalUserId;
        private Double causalLiftScore;
        private Long nQualifyingEvents;
        /** {@code "HIGH"} or {@code "LOW"} — surfaced as-is so a LOW entry is never mistaken for HIGH. */
        private String confidence;
        private Long mentionCount;
        private Double engagementRating;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StatisticalCandidateSection {
        /** {@code "ok"} or {@code "insufficient_history"}. */
        private String status;
        /** Populated only when {@link #status} is {@code "insufficient_history"}. */
        private String details;
        /** The matching {@link RecommendedActionCandidate}s, evidence carried verbatim. */
        @Builder.Default
        private List<RecommendedActionCandidate> candidates = List.of();
    }
}
