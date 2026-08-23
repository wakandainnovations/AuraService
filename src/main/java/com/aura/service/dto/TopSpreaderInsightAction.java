package com.aura.service.dto;

import com.aura.service.enums.RecommendedActionCategory;

/**
 * One LLM-recommended collaboration action tied to a specific top spreader (see
 * {@code TopSpreaderInsightsService}). {@code impact} is never LLM-authored - it's the same
 * server-computed reach tier the spreader was already ranked into (by total views, relative to the
 * other spreaders in this response) before the LLM ever saw it; the LLM's only job is picking which
 * spreaders are worth recommending collaboration with and writing {@code action}, grounded in that
 * spreader's real sample post content.
 */
public record TopSpreaderInsightAction(
        String spreaderId,
        String action,
        RecommendedActionCategory impact) {
}
