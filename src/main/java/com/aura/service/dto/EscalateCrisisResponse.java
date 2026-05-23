package com.aura.service.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EscalateCrisisResponse {
    private MentionResponse mention;
    private Long planId;
    private String plan;
}
