package com.aura.service.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TimeSeriesData {
    private String date;
    private long positive;
    private long negative;
    private long neutral;
    private long total;
}
