package com.aura.service.enums;

/**
 * Impact tier for a {@link com.aura.service.dto.RecommendedActionCandidate}, derived from the
 * midpoint of the backing {@link com.aura.service.service.BoxOfficeFactorCatalog} factor's
 * [low, high] impact range. See RecommendedActionCandidateServiceImpl.HIGH_IMPACT_THRESHOLD /
 * MEDIUM_IMPACT_THRESHOLD for the exact cutoffs.
 */
public enum RecommendedActionCategory {
    HIGH_IMPACT,
    MEDIUM_IMPACT,
    LOW_IMPACT
}
