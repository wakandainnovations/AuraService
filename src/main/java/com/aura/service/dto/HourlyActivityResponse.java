package com.aura.service.dto;

import com.aura.service.enums.TimePeriod;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class HourlyActivityResponse {
    private Long entityId;
    private String entityName;
    private TimePeriod period;
    private Instant startDate;
    private Instant endDate;
    private String language;
    private String industry;
    private String state;
    private long totalActiveUsers;
    private Map<Integer, Long> hourlyDistribution;
}
