package com.aura.service.dto;

import java.util.List;

/**
 * Aggregate audience size for every {@code MOVIE} entity in a given language: the count of unique
 * posters (mention authors) across all of those movies combined, counting a user only once even if
 * they posted about several movies in the language. Only mentions with a non-zero sentiment score
 * are counted (see {@code MovieAudienceServiceImpl}).
 */
public record LanguageAudienceResponse(
        String language,
        int movieCount,
        long uniqueAudienceCount,
        List<String> movieNames) {
}
