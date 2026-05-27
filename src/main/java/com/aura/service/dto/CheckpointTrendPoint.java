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
public class CheckpointTrendPoint {
    private LocalDate checkpointDate;
    private String description;
    private long cumulativeMentions;
    private long periodMentions;
    private double positiveRatio;
    private double netSentiment;
    private Double positiveRatioChangeFromPrevious;
    private Double netSentimentChangeFromPrevious;
}
