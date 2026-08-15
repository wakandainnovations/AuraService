package com.aura.service.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MovieSentimentResponse {
    private Long entityId;
    private String entityName;
    private long totalMentions;
    private double averageSentimentScore;
    private double positiveRatio;
}
