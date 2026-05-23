package com.aura.service.dto;

import com.aura.service.entity.SentimentAlert;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateAlertRequest {

    @NotNull(message = "managedEntityId is required")
    private Long managedEntityId;

    @NotNull(message = "kind is required")
    private SentimentAlert.Kind kind;

    private double currentValue;

    private double baselineValue;

    private Long sourceMentionId;

    private String matchedAuthor;

    private String permalink;
}
