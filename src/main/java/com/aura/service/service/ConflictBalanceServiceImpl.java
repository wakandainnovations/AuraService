package com.aura.service.service;

import com.aura.service.dto.ConflictBalanceScore;
import com.aura.service.entity.ManagedEntity;
import com.aura.service.exception.ResourceNotFoundException;
import com.aura.service.repository.ManagedEntityRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Scores the "Protagonist-Antagonist Conflict Balance" narrative metric from a movie's synopsis.
 * The LLM is only asked for qualitative ordinal ratings (1-5) plus a short rationale — the balance
 * number itself is computed here, since LLMs are unreliable at consistent arithmetic and a
 * server-computed formula is auditable independent of the model.
 */
@Service
public class ConflictBalanceServiceImpl implements ConflictBalanceService {

    private static final String SYNOPSIS_PLACEHOLDER = "[Insert Synopsis]";

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

        int protagonistPower = requireRating(node, "protagonistPower", movieId);
        int antagonistPower = requireRating(node, "antagonistPower", movieId);
        int antagonistMotivationClarity = requireRating(node, "antagonistMotivationClarity", movieId);
        int stakesEscalation = requireRating(node, "stakesEscalation", movieId);
        String rationale = node.hasNonNull("rationale") ? node.get("rationale").asText() : null;

        // Per the catalog decision (Direction: Positive) — "a strong, competent antagonist raises
        // narrative stakes" — the score must be monotonic in antagonist strength with no cap at
        // parity; a dominant antagonist must never score lower than an evenly-matched one. A
        // symmetric |protagonist - antagonist| gap formula can't express that (it's identical for
        // "weak antagonist, strong protagonist" and "strong antagonist, weak protagonist"), so
        // protagonistPower deliberately does not feed into balanceScore — it's still returned for
        // context. Each [1,5] rating is normalized to [0,1] via (x-1)/4, then weighted.
        double balanceScore = (antagonistPower - 1) / 4.0 * 0.5
                + (antagonistMotivationClarity - 1) / 4.0 * 0.25
                + (stakesEscalation - 1) / 4.0 * 0.25;

        return new ConflictBalanceScore(
                protagonistPower, antagonistPower, antagonistMotivationClarity, stakesEscalation,
                rationale, balanceScore);
    }

    private int requireRating(JsonNode node, String field, Long movieId) {
        if (!node.hasNonNull(field) || !node.get(field).isIntegralNumber()) {
            throw new RuntimeException(
                    "Conflict balance LLM response missing/invalid field '" + field + "' for movie " + movieId);
        }
        int value = node.get(field).asInt();
        if (value < 1 || value > 5) {
            throw new RuntimeException(
                    "Conflict balance LLM response field '" + field + "' out of range [1,5]: " + value
                            + " for movie " + movieId);
        }
        return value;
    }
}
