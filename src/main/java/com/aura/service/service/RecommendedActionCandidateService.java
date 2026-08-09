package com.aura.service.service;

import com.aura.service.dto.RecommendedActionCandidate;

import java.util.List;

/**
 * Phase 1 of the "Recommended Actions" Command Center panel: builds the fully-numeric candidate
 * marketing actions for a movie - category, confidence, and timing window all computed here in plain
 * Java from real data, never asked of an LLM. Phase 2 (a separate service) will select from and add
 * prose to the candidates this produces.
 *
 * <p>Defined as an interface (mirroring {@link LLMService} / {@link BoxOfficeBaselineService}) so
 * Phase 2's tests can mock this service as an interface rather than a concrete class - this project's
 * Java 25 setup breaks Mockito's inline mocking of concrete classes.
 */
public interface RecommendedActionCandidateService {

    /**
     * Builds every recommended-action candidate this movie currently has real backing data for. A
     * factor with insufficient supporting data simply produces no candidate - never a placeholder or
     * guessed number.
     */
    List<RecommendedActionCandidate> buildCandidateActions(Long entityId);
}
