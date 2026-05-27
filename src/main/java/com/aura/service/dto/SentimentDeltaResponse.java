package com.aura.service.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SentimentDeltaResponse {
    private LocalDate fromDate;
    private LocalDate toDate;
    private String fromLabel;
    private String toLabel;
    private long fromTotalMentions;
    private long toTotalMentions;
    private long mentionsDelta;
    private double fromPositiveRatio;
    private double toPositiveRatio;
    private double positiveRatioDelta;
    private double fromNetSentiment;
    private double toNetSentiment;
    private double netSentimentDelta;
}
