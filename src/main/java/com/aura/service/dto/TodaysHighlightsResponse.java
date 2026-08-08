package com.aura.service.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TodaysHighlightsResponse {
    private Long entityId;
    private String entityName;
    private List<HighlightItem> highlights;
    private Instant generatedAt;
}
