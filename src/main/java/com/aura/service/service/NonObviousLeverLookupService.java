package com.aura.service.service;

import java.util.List;

/**
 * Defined as an interface (mirroring {@link GenreMarketingLookupService}) so
 * {@code RecommendedActionCandidateServiceImplTest} can mock this service directly rather than
 * constructing the real AuraMath-backed implementation - this project's Java 25 setup breaks
 * Mockito's inline mocking of concrete classes.
 */
public interface NonObviousLeverLookupService {

    /** One row of AuraMath's F5 {@code nonobvious_lever_findings}, pooled across the 'ALL' cohort. */
    record LeverFinding(String featureName, String direction, double pValue, double fdrQValue, long nEntities) {
    }

    /** Empty list if AuraMath is unavailable, reports insufficient_history, or the response can't be parsed. */
    List<LeverFinding> getNonObviousLevers(long entityId);
}
