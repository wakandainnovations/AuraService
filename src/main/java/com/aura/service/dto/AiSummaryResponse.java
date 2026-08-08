package com.aura.service.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AiSummaryResponse {
    private Long entityId;
    private String entityName;
    private String summary;
    private Instant generatedAt;
}
