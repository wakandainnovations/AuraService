package com.aura.service.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RegionBuzz {
    private int rank;
    private String region;
    private long mentionCount;
    private double sharePct;
}
