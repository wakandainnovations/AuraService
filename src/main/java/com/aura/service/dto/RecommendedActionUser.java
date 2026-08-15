package com.aura.service.dto;

/**
 * One real account behind a {@link RecommendedActionItem}/{@link RecommendedActionCandidate} -
 * the expanded "View Details" roster (up to
 * {@link com.aura.service.service.RecommendedActionCandidateServiceImpl#MAX_RELEVANT_USERS} users),
 * as opposed to {@code exampleHandles}' short inline-text sample. {@code userId} is always a real
 * globalUserId/handle from AuraMath - never LLM-authored. {@code platform} and {@code profileUrl} are
 * only populated when AuraMath's own response actually carried them for that author; both are null
 * (not guessed or constructed from the handle) when it didn't.
 */
public record RecommendedActionUser(
        String userId,
        String platform,
        String profileUrl
) {
}
