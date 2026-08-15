package com.aura.service.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MovieHealthResponse {
    private Long entityId;
    private String entityName;
    private double netSentimentScore;
    private double healthPercentage;
    private String healthLabel;
}
