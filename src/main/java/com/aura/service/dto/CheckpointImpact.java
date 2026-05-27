package com.aura.service.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CheckpointImpact {
    private Long checkpointId;
    private LocalDate checkpointDate;
    private String description;
    private long beforeTotalMentions;
    private long afterTotalMentions;
    private double beforePositiveRatio;
    private double afterPositiveRatio;
    private double positiveRatioChange;
    private double beforeNetSentiment;
    private double afterNetSentiment;
    private double netSentimentChange;
    private String impactDirection;
}
