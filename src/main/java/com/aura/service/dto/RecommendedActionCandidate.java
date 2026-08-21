package com.aura.service.dto;

import com.aura.service.enums.RecommendedActionCategory;

import java.util.List;

/**
 * A fully-numeric recommended-action candidate produced by
 * {@link com.aura.service.service.RecommendedActionCandidateService} - every field here is computed
 * from real queries/calendar math, never an LLM guess. Phase 2 selects from and adds prose to these
 * candidates; it may cite {@code supportingFacts} verbatim but must not invent new numbers.
 *
 * @param candidateId    stable id (not regenerated per call) that Phase 2 can reference
 * @param factorName     the {@link com.aura.service.service.BoxOfficeFactorCatalog} factor name this
 *                        candidate is grounded in
 * @param category       impact tier derived from the factor's catalog impact-range midpoint
 * @param confidencePct  0-100, tiered off the strength of the evidence backing this specific candidate
 * @param windowStartDaysFromRelease earlier bound of the execution window, signed (negative = before
 *                                   release, positive = after)
 * @param windowEndDaysFromRelease   later bound of the execution window, signed
 * @param windowLabel    human-readable rendering of the two day offsets
 * @param supportingFacts precise, self-contained strings citing the real data behind this candidate
 * @param exampleHandles up to a few real account handles/usernames backing this candidate (e.g. top
 *                        viral-seed or positive-sentiment accounts), when the underlying data is
 *                        account-level; empty when it isn't. Never LLM-authored - Phase 2 copies this
 *                        through verbatim.
 * @param relevantUsers  the fuller, "View Details" roster behind this candidate - up to {@link
 *                        com.aura.service.service.RecommendedActionCandidateServiceImpl#MAX_RELEVANT_USERS}
 *                        real accounts (a superset of {@code exampleHandles}), each carrying a platform
 *                        and profile link when AuraMath's response actually supplied one; empty when the
 *                        underlying data isn't account-level. Never LLM-authored.
 * @param statisticalEvidence AuraMath F5/F7 statistical-mining evidence (p-value, FDR q-value, sample
 *                        size, direction, or a mined pattern sequence) backing this candidate, copied
 *                        verbatim from AuraMath's response; null for every candidate not sourced from
 *                        those endpoints. Carried as structured data rather than prose so Phase 2 states
 *                        it factually instead of paraphrasing a number out of a sentence.
 */
public record RecommendedActionCandidate(
        String candidateId,
        String factorName,
        RecommendedActionCategory category,
        int confidencePct,
        int windowStartDaysFromRelease,
        int windowEndDaysFromRelease,
        String windowLabel,
        List<String> supportingFacts,
        List<String> exampleHandles,
        List<RecommendedActionUser> relevantUsers,
        StatisticalEvidence statisticalEvidence
) {

    /** Compatibility constructor for every candidate not backed by AuraMath's F5/F7 statistical mining. */
    public RecommendedActionCandidate(
            String candidateId,
            String factorName,
            RecommendedActionCategory category,
            int confidencePct,
            int windowStartDaysFromRelease,
            int windowEndDaysFromRelease,
            String windowLabel,
            List<String> supportingFacts,
            List<String> exampleHandles,
            List<RecommendedActionUser> relevantUsers) {
        this(candidateId, factorName, category, confidencePct, windowStartDaysFromRelease,
                windowEndDaysFromRelease, windowLabel, supportingFacts, exampleHandles, relevantUsers, null);
    }

    /**
     * Statistical-mining evidence copied verbatim from AuraMath - never computed or rounded by this
     * service. {@code featureName}/{@code direction}/{@code pValue} are populated for a nonobvious-lever
     * candidate; {@code patternSequence}/{@code supportTopTier}/{@code supportBottomTier} for a
     * playbook-sequence candidate; {@code fdrQValue}/{@code nEntities} are populated for both. Fields not
     * applicable to a given candidate are null.
     */
    public record StatisticalEvidence(
            String featureName,
            String direction,
            Double pValue,
            Double fdrQValue,
            Long nEntities,
            List<String> patternSequence,
            Long supportTopTier,
            Long supportBottomTier
    ) {
    }
}
