package com.aura.service.dto;

import com.aura.service.entity.SentimentAlert;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AlertRuleResponse {
    private Long id;
    private Long userId;
    private Long entityId;
    private SentimentAlert.Kind kind;
    private double threshold;
    private List<String> channels;
    private boolean enabled;
}
