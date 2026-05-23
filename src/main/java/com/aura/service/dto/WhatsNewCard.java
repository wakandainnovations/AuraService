package com.aura.service.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WhatsNewCard {

    @JsonProperty("kind")
    private String kind;

    @JsonProperty("headline")
    private String headline;

    @JsonProperty("value")
    private Double value;

    @JsonProperty("evidence_mention_ids")
    private List<Long> evidenceMentionIds;
}
