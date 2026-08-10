package com.aura.service.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AudiencePulseAspectsResponse {
    private Long entityId;
    private String entityName;
    private List<String> peopleLove;
    private List<String> peopleConcerned;
    private Instant generatedAt;
}
