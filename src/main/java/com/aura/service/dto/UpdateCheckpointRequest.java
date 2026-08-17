package com.aura.service.dto;

import com.aura.service.enums.CheckpointType;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateCheckpointRequest {

    private LocalDate checkpointDate;

    @Size(max = 20, message = "description must be at most 20 characters")
    private String description;

    /** Optional; leaves the existing type unchanged when omitted. */
    private CheckpointType checkpointType;
}
