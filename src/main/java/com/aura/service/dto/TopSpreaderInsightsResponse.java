package com.aura.service.dto;

import java.time.Instant;
import java.util.List;

/**
 * AI-generated collaboration insights for an entity's top spreaders, built from the same data
 * {@code TopSpreaderContentService} resolves and sent to the LLM by {@code TopSpreaderInsightsService}.
 * {@code summary} and each action's prose are the only LLM-authored text here; every other value is
 * server-computed. {@code language} mirrors the requested filter as given (see
 * {@link TopSpreaderContentResponse}).
 */
public record TopSpreaderInsightsResponse(
        Long entityId,
        String language,
        String summary,
        List<TopSpreaderInsightAction> actions,
        Instant generatedAt) {
}
