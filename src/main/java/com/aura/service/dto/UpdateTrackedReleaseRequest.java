package com.aura.service.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

/**
 * Partial update; null fields are left unchanged. {@code releaseDate} may only be changed on MANUAL
 * rows - TMDB rows take their date from TMDB on every discovery pass.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateTrackedReleaseRequest {
    private List<String> keywords;
    private Boolean excluded;
    private String excludedReason;
    private LocalDate releaseDate;
}
