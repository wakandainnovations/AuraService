package com.aura.service.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class NarrativeNoveltyScore {
    private int premiseClarity;
    private int worldBuildingDistinctiveness;
    private int hookMemorability;
    private int conceptualCollisionRisk;
    private String rationale;
    private double noveltyScore;
}
