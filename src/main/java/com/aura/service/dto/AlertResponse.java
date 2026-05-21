package com.aura.service.dto;

import com.aura.service.entity.SentimentAlert;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AlertResponse {
    private Long id;
    private Long managedEntityId;
    private String entityName;
    private SentimentAlert.Kind kind;
    private SentimentAlert.Status status;
    private Instant triggeredAt;
    private double currentValue;
    private double baselineValue;
    private Long sourceMentionId;
    private String matchedAuthor;
    private String permalink;
    private Instant ackedAt;
    private String ackedBy;
    private Instant dismissedAt;
    private String dismissedBy;
    private String dismissReason;
    private String reason;
}
