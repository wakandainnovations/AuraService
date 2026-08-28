package com.aura.service.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Shared request shape for {@code override-topic-category} and {@code override-author-type}. Unlike
 * {@link OverrideReviewAspectRequest}, {@code category} is a plain string, not an enum — the taxonomy
 * it corrects is owned upstream, outside this codebase, so AuraService has no fixed value set to
 * validate against beyond "not blank."
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OverrideCategoryRequest {

    @NotBlank
    private String category;

    /** Optional free-text note on why the prior classification was wrong, shown in the action history. */
    private String reason;
}
