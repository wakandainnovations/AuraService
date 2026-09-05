package com.aura.service.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RecommendedActionsResponse {
    private Long entityId;
    private String entityName;
    private Integer daysToRelease;
    private List<RecommendedActionItem> actions;
    private Instant generatedAt;

    /**
     * Same "Social Buzz Situation" snapshot as {@code GET /api/crisis/situation-recommendation/{entityId}}
     * - see {@link com.aura.service.service.SituationRecommendationService} - folded into this response
     * so the marketing team gets both the tactical action list and the reactive burst/precedent read in
     * one call. Null when it couldn't be computed for this request (see
     * {@link com.aura.service.service.RecommendedActionsService#getRecommendedActions}); never populated
     * by {@code getAllRecommendedActions}, which stays the plain action-history audit view.
     */
    private SituationRecommendationResponse situationRecommendation;
}
