package com.aura.service.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EntityStatsResponse {
    private long totalMentions;
    private double positiveSentiment;
    private double negativeSentiment;
    private double neutralSentiment;
    private double netSentimentScore;
}
