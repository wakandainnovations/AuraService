package com.aura.service.dto;

import com.aura.service.enums.CheckpointStage;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * A single rule-based (never LLM-generated) checkpoint recommendation produced by
 * {@link com.aura.service.service.CheckpointRecommendationService}. Every number here comes from a
 * real query or a stated, fixed threshold - never an invented score.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CheckpointRecommendation {
    private CheckpointStage stage;
    private Long checkpointId;
    private String ruleType;
    private String message;

    // Populated for ruleType=INSUFFICIENT_ANCHORS; null otherwise.
    private Integer selectedAnchorCount;
    private Integer requiredAnchorCount;

    // Populated for ruleType=BELOW_PEER_TRACTION; null otherwise.
    private Long selfMentionCount;
    private Double peerAverageMentionCount;
    private List<String> peerEntityNames;
}
