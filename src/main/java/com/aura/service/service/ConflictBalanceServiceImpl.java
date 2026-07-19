package com.aura.service.service;

import com.aura.service.dto.ConflictBalanceScore;
import com.aura.service.entity.ManagedEntity;
import com.aura.service.exception.ResourceNotFoundException;
import com.aura.service.repository.ManagedEntityRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Scores the "Protagonist-Antagonist Conflict Balance" narrative metric from a movie's synopsis.
 * The LLM is only asked for qualitative ordinal ratings (1-5) plus a short rationale — the balance
 * number itself is computed here, since LLMs are unreliable at consistent arithmetic and a
 * server-computed formula is auditable independent of the model. Per the catalog decision (Direction:
 * Positive, Impact: +25% to +35%, read as fixed bounds on the score itself — same convention as
 * {@link NarrativeNoveltyServiceImpl}), balanceScore is an affine remap of the normalized ratings into
 * [0.25, 0.35]: the floor is never breached even for the weakest antagonist, and the ceiling is never
 * exceeded even for the strongest one.
 */
@Slf4j
@Service
public class ConflictBalanceServiceImpl implements ConflictBalanceService {

    private static final String SYNOPSIS_PLACEHOLDER = "[Insert Synopsis]";
    private static final double SCORE_FLOOR = 0.25;
    private static final double SCORE_CEILING = 0.35;

    private final LLMService llmService;
    private final ManagedEntityRepository managedEntityRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${llm.prompt.generate.conflict.balance}")
    private String llmPrompt;

    public ConflictBalanceServiceImpl(LLMService llmService, ManagedEntityRepository managedEntityRepository) {
        this.llmService = llmService;
        this.managedEntityRepository = managedEntityRepository;
    }

    @Override
    public ConflictBalanceScore getConflictBalance(Long movieId) {
        ManagedEntity entity = managedEntityRepository.findById(movieId)
                .orElseThrow(() -> new ResourceNotFoundException("Entity not found: " + movieId));

        String synopsis = entity.getSynopsis();
        if (synopsis == null || synopsis.isBlank()) {
            throw new IllegalArgumentException(
                    "Synopsis required to compute conflict balance for movie " + movieId);
        }

        String prompt = llmPrompt.replace(SYNOPSIS_PLACEHOLDER, synopsis);
        String reply = llmService.generateReply(prompt);

        JsonNode node;
        try {
            node = objectMapper.readTree(reply);
        } catch (Exception e) {
            throw new RuntimeException(
                    "Conflict balance LLM response could not be parsed as JSON for movie " + movieId, e);
        }

        int protagonistPower = ratingOrDefault(node, "protagonistPower", movieId);
        int antagonistPower = ratingOrDefault(node, "antagonistPower", movieId);
        int antagonistMotivationClarity = ratingOrDefault(node, "antagonistMotivationClarity", movieId);
        int stakesEscalation = ratingOrDefault(node, "stakesEscalation", movieId);
        String rationale = node.hasNonNull("rationale") ? node.get("rationale").asText() : null;

        // Per the catalog decision (Direction: Positive) — "a strong, competent antagonist raises
        // narrative stakes" — the score must be monotonic in antagonist strength with no cap at
        // parity; a dominant antagonist must never score lower than an evenly-matched one. A
        // symmetric |protagonist - antagonist| gap formula can't express that (it's identical for
        // "weak antagonist, strong protagonist" and "strong antagonist, weak protagonist"), so
        // protagonistPower deliberately does not feed into balanceScore — it's still returned for
        // context. Each [1,5] rating is normalized to [0,1] via (x-1)/4, then weighted.
        double normalizedBalance = (antagonistPower - 1) / 4.0 * 0.5
                + (antagonistMotivationClarity - 1) / 4.0 * 0.25
                + (stakesEscalation - 1) / 4.0 * 0.25;

        double balanceScore = SCORE_FLOOR + normalizedBalance * (SCORE_CEILING - SCORE_FLOOR);

        return new ConflictBalanceScore(
                protagonistPower, antagonistPower, antagonistMotivationClarity, stakesEscalation,
                rationale, balanceScore);
    }

    // Defaults to the floor (1) rather than failing the whole request: LLMs frequently emit "NA", an
    // out-of-range placeholder like 0, or omit a rating entirely (most often antagonist-related
    // fields, when the synopsis has no clear antagonist — which the prompt itself says should be
    // rated 1). Treating any missing/invalid/out-of-range rating as 1 makes that behavior uniform
    // and keeps a single bad field from discarding an otherwise-usable response.
    private int ratingOrDefault(JsonNode node, String field, Long movieId) {
        Integer value = node.hasNonNull(field) ? asRatingInteger(node.get(field)) : null;
        if (value == null || value < 1 || value > 5) {
            log.warn("Conflict balance LLM response missing/invalid field '{}' for movie {} (raw: {}) — defaulting to 1",
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
