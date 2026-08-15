package com.aura.service.dto;

import com.aura.service.enums.RecommendedActionCategory;
import com.aura.service.enums.RecommendedActionStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * One entry in the "Recommended Actions" Command Center panel: everything except {@code title}/
 * {@code reason} is copied verbatim from the Phase 1 {@link com.aura.service.dto.RecommendedActionCandidate}
 * this item was built from. {@code reason} (and optionally a sharpened {@code title}) is the only
 * LLM-authored text in this record - see {@link com.aura.service.service.RecommendedActionsService}.
 *
 * <p>{@code candidateId} carries {@link RecommendedActionCandidate#candidateId()} through so a status
 * set by the marketing team survives regeneration (matched by this id across refreshes - see
 * {@link com.aura.service.service.RecommendedActionsService}). Items deserialized from a cache row
 * written before this field existed have a {@code null} candidateId and default to {@link
 * RecommendedActionStatus#ACTIVE}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RecommendedActionItem {
    private String candidateId;
    private RecommendedActionCategory category;
    private String title;
    private String reason;
    private int confidencePct;
    private String relatedFactor;
    private int windowStartDaysFromRelease;
    private int windowEndDaysFromRelease;
    private String windowLabel;
    private List<String> exampleHandles;
    private RecommendedActionStatus status = RecommendedActionStatus.ACTIVE;
}
