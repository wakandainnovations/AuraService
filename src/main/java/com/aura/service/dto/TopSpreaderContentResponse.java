package com.aura.service.dto;

import java.util.List;

/**
 * {@code language} is the requested filter as given (null when the caller didn't scope to one
 * language, in which case {@code spreaders} is deduped across every language this entity is tracked
 * in - see {@code TopSpreaderContentService}).
 */
public record TopSpreaderContentResponse(
        Long entityId,
        String language,
        List<TopSpreaderContent> spreaders) {
}
