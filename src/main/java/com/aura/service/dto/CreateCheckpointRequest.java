package com.aura.service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateCheckpointRequest {

    @NotNull(message = "entityId is required")
    private Long entityId;

    @NotNull(message = "checkpointDate is required")
    private LocalDate checkpointDate;

    @NotBlank(message = "description is required")
    @Size(max = 20, message = "description must be at most 20 characters")
    private String description;
}
