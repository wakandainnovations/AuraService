package com.aura.service.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BuzzResponse {
    private Long entityId;
    private String entityName;
    private long mentionsToday;
    private long mentionsYesterday;
    private long mentionsChange;
    private double mentionsChangePct;
}
