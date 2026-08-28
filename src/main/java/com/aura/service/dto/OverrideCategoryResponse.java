package com.aura.service.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** Shared response shape for {@code override-topic-category} and {@code override-author-type}. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OverrideCategoryResponse {
    private MentionResponse mention;
    private Long overrideId;
    private String previousCategory;
    private String newCategory;
    private Instant createdAt;
}
