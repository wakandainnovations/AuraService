package com.aura.service.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EntitySentimentData {
    private String name;
    private List<TimeSeriesData> sentiments;
    private List<CheckpointMarker> checkpoints = new ArrayList<>();
}
