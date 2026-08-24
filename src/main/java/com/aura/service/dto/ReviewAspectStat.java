package com.aura.service.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReviewAspectStat {
    private int rank;
    private String category;
    private long totalPosts;
    private Double averageSentimentScore;
    private double sharePct;
}
