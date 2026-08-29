package com.aura.service.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CheckpointRecommendationsResponse {
    private Long entityId;
    private String entityName;
    private List<CheckpointRecommendation> recommendations;
}
