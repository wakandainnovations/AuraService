package com.aura.service.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AllyRecommendation {
    private String globalUserId;
    private String primaryPlatform;
    private String influenceTier;
    private String suggestedDm;
}
