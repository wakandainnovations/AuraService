package com.aura.service.dto;

import com.aura.service.enums.RecommendedActionCategory;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * One entry in the "Recommended Actions" Command Center panel: everything except {@code title}/
 * {@code reason} is copied verbatim from the Phase 1 {@link com.aura.service.dto.RecommendedActionCandidate}
 * this item was built from. {@code reason} (and optionally a sharpened {@code title}) is the only
 * LLM-authored text in this record - see {@link com.aura.service.service.RecommendedActionsService}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RecommendedActionItem {
    private RecommendedActionCategory category;
    private String title;
    private String reason;
    private int confidencePct;
    private String relatedFactor;
    private int windowStartDaysFromRelease;
    private int windowEndDaysFromRelease;
    private String windowLabel;
    private List<String> exampleHandles;
}
