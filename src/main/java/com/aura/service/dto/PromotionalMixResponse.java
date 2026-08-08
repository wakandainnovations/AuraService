package com.aura.service.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PromotionalMixResponse {
    private Long entityId;
    private String entityName;
    private long totalPosts;
    private long promotionalCount;
    private long organicCount;
    private double promotionalSharePct;
}
