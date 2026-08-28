package com.aura.service.dto;

import com.aura.service.enums.ReviewAspectCategory;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class OverrideReviewAspectResponse {
    private MentionResponse mention;
    private Long overrideId;
    private ReviewAspectCategory previousCategory;
    private ReviewAspectCategory newCategory;
    private Instant createdAt;
}
