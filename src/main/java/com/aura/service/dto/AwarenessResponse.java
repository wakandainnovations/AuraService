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
    private long uniqueUsers;
    private String awarenessLevel;
}
