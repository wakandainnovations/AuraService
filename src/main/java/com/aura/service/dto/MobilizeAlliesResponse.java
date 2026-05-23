package com.aura.service.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MobilizeAlliesResponse {
    private MentionResponse mention;
    private List<AllyRecommendation> allies;
}
