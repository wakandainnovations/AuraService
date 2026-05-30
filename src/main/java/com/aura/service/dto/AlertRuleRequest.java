package com.aura.service.dto;

import com.aura.service.entity.SentimentAlert;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Create/update payload for an alert rule. The owning user is taken from the
 * authenticated principal, never from the request body.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AlertRuleRequest {

    /** Nullable: omit to make the rule apply to every entity the user watches. */
    private Long entityId;

    @NotNull(message = "kind is required")
    private SentimentAlert.Kind kind;

    @PositiveOrZero(message = "threshold must be zero or positive")
    private double threshold;

    private List<String> channels = new ArrayList<>();

    private boolean enabled = true;
}
