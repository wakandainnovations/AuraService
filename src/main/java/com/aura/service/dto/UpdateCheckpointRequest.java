package com.aura.service.dto;

import com.aura.service.enums.AnchorType;
import com.aura.service.enums.CheckpointType;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateCheckpointRequest {

    private LocalDate checkpointDate;

    @Size(max = 20, message = "description must be at most 20 characters")
    private String description;

    /** Optional; leaves the existing type unchanged when omitted. */
    private CheckpointType checkpointType;

    /** Optional; only valid on the ANCHOR_SEED stage checkpoint. Null leaves the existing selection
     *  unchanged; a non-null list (including empty) replaces it. */
    private List<AnchorType> selectedAnchors;
}
