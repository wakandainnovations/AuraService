package com.aura.service.dto;

import com.aura.service.enums.ReviewAspectCategory;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class OverrideReviewAspectRequest {

    @NotNull
    private ReviewAspectCategory category;

    /** Optional free-text note on why the LLM's classification was wrong, shown in the action history. */
    private String reason;
}
