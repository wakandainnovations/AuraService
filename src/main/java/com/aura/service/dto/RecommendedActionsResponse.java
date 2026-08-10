package com.aura.service.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RecommendedActionsResponse {
    private Long entityId;
    private String entityName;
    private Integer daysToRelease;
    private List<RecommendedActionItem> actions;
    private Instant generatedAt;
}
