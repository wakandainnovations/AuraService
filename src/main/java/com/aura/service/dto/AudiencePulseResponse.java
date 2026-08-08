package com.aura.service.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AudiencePulseResponse {
    private Long entityId;
    private String entityName;
    private long totalMentions;
    private List<RegionBuzz> regions;
}
