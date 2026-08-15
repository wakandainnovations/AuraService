package com.aura.service.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AwarenessResponse {
    private Long entityId;
    private String entityName;
    private long totalViews;
    private String awarenessLevel;
    private int comparedMovieCount;
}
