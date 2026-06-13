package com.aura.service.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateEntityRequest {
    
    @NotBlank(message = "Name is required")
    private String name;
    
    private String director;
    
    private List<String> actors = new ArrayList<>();

    private List<KeywordDto> keywords = new ArrayList<>();

    private LocalDate releaseDate;

    private String industry;

    private List<String> genre = new ArrayList<>();
}
