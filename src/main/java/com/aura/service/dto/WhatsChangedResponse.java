package com.aura.service.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WhatsChangedResponse {

    @JsonProperty("sentiment_score_delta")
    private Double sentimentScoreDelta;

    @JsonProperty("new_mentions_count")
    private Long newMentionsCount;

    @JsonProperty("new_negative_count")
    private Long newNegativeCount;

    @JsonProperty("new_super_spreader_count")
    private Long newSuperSpreaderCount;

    @JsonProperty("competitor_delta")
    private Map<String, Double> competitorDelta;
}
