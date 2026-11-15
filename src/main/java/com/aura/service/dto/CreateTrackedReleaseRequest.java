package com.aura.service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

/** Admin-added release (one TMDB doesn't list). Keywords default to the title's hashtag + phrase forms. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateTrackedReleaseRequest {

    @NotBlank(message = "title is required")
    private String title;

    @NotNull(message = "releaseDate is required")
    private LocalDate releaseDate;

    private String language;

    private List<String> keywords;
}
