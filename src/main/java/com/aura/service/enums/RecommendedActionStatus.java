package com.aura.service.enums;

/**
 * Marketing-team-set lifecycle status for a single {@link com.aura.service.dto.RecommendedActionItem},
 * tracked by its {@code candidateId} across regenerations - see
 * {@link com.aura.service.service.RecommendedActionsService} for how a status survives a plan refresh.
 */
public enum RecommendedActionStatus {
    ACTIVE,
    DONE,
    IRRELEVANT
}
