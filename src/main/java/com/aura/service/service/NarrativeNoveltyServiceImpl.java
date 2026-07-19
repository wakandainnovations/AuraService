package com.aura.service.service;

import com.aura.service.dto.NarrativeNoveltyScore;
import com.aura.service.entity.ManagedEntity;
import com.aura.service.exception.ResourceNotFoundException;
import com.aura.service.repository.ManagedEntityRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Scores the "High-Concept Narrative Novelty" metric from a movie's synopsis. The catalog fixes this
 * metric's contribution to the downstream predictive model at Direction: Positive, Impact: +30% to
 * +45% — read as bounds on the score itself, not just its weight — so, unlike balanceScore in
 * {@link ConflictBalanceServiceImpl} (which ranges over the full [0,1]), noveltyScore is an affine
 * remap of the normalized LLM ratings into the fixed [0.30, 0.45] band: the floor is never breached
 * even for the least novel premise, and the ceiling is never exceeded even for the most novel one.
 * As with conflict balance, the LLM supplies only ordinal ratings — the arithmetic happens here.
 */
@Slf4j
@Service
public class NarrativeNoveltyServiceImpl implements NarrativeNoveltyService {

    private static final String SYNOPSIS_PLACEHOLDER = "[Insert Synopsis]";
    private static final double SCORE_FLOOR = 0.30;
    private static final double SCORE_CEILING = 0.45;

    private final LLMService llmService;
    private final ManagedEntityRepository managedEntityRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${llm.prompt.generate.narrative.novelty}")
    private String llmPrompt;

    public NarrativeNoveltyServiceImpl(LLMService llmService, ManagedEntityRepository managedEntityRepository) {
        this.llmService = llmService;
        this.managedEntityRepository = managedEntityRepository;
    }

    @Override
    public NarrativeNoveltyScore getNarrativeNovelty(Long movieId) {
        ManagedEntity entity = managedEntityRepository.findById(movieId)
                .orElseThrow(() -> new ResourceNotFoundException("Entity not found: " + movieId));

        String synopsis = entity.getSynopsis();
        if (synopsis == null || synopsis.isBlank()) {
            throw new IllegalArgumentException(
                    "Synopsis required to compute narrative novelty for movie " + movieId);
        }

        String prompt = llmPrompt.replace(SYNOPSIS_PLACEHOLDER, synopsis);
        String reply = llmService.generateReply(prompt);

        JsonNode node;
        try {
            node = objectMapper.readTree(reply);
        } catch (Exception e) {
            throw new RuntimeException(
                    "Narrative novelty LLM response could not be parsed as JSON for movie " + movieId, e);
        }

        int premiseClarity = ratingOrDefault(node, "premiseClarity", movieId);
        int worldBuildingDistinctiveness = ratingOrDefault(node, "worldBuildingDistinctiveness", movieId);
        int hookMemorability = ratingOrDefault(node, "hookMemorability", movieId);
        int conceptualCollisionRisk = ratingOrDefault(node, "conceptualCollisionRisk", movieId);
        String rationale = node.hasNonNull("rationale") ? node.get("rationale").asText() : null;

        // Each [1,5] rating normalized to [0,1] via (x-1)/4; conceptualCollisionRisk is inverted
        // (high collision with an existing film means low novelty) before weighting.
        double normalizedNovelty = (worldBuildingDistinctiveness - 1) / 4.0 * 0.40
                + (premiseClarity - 1) / 4.0 * 0.25
                + (hookMemorability - 1) / 4.0 * 0.20
                + (1 - (conceptualCollisionRisk - 1) / 4.0) * 0.15;

        double noveltyScore = SCORE_FLOOR + normalizedNovelty * (SCORE_CEILING - SCORE_FLOOR);

        return new NarrativeNoveltyScore(
                premiseClarity, worldBuildingDistinctiveness, hookMemorability, conceptualCollisionRisk,
                rationale, noveltyScore);
    }

    // Defaults to the floor (1) rather than failing the whole request: LLMs frequently emit "NA", an
    // out-of-range placeholder, or omit a rating entirely despite the prompt's explicit instructions.
    // Treating any missing/invalid/out-of-range rating as 1 keeps a single bad field from discarding
    // an otherwise-usable response — see the identical rationale in ConflictBalanceServiceImpl.
    private int ratingOrDefault(JsonNode node, String field, Long movieId) {
        Integer value = node.hasNonNull(field) ? asRatingInteger(node.get(field)) : null;
        if (value == null || value < 1 || value > 5) {
            log.warn("Narrative novelty LLM response missing/invalid field '{}' for movie {} (raw: {}) — defaulting to 1",
                    field, movieId, node.has(field) ? node.get(field) : "<absent>");
            return 1;
        }
        return value;
    }

    // LLMs are inconsistent about honoring "output a plain integer" — despite the prompt's explicit
    // instruction, the model frequently quotes ratings as strings (e.g. "4" instead of 4). Accept
    // either so a formatting quirk doesn't fail the whole request; a genuinely non-numeric value
    // (e.g. "four", "4.5") still falls through to null and is rejected as invalid.
    private static Integer asRatingInteger(JsonNode valueNode) {
        if (valueNode.isIntegralNumber()) {
            return valueNode.asInt();
        }
        if (valueNode.isTextual()) {
            try {
                return Integer.parseInt(valueNode.asText().trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
