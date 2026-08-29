package com.aura.service.dto;

import com.aura.service.enums.AnchorType;
import com.aura.service.enums.CheckpointStage;
import com.aura.service.enums.CheckpointType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CheckpointResponse {
    private Long id;
    private Long entityId;
    private String entityName;
    private LocalDate checkpointDate;
    private String description;
    private CheckpointType checkpointType;
    private CheckpointStage stage;
    private boolean isDefault;
    private LocalDate windowEndDate;
    private List<AnchorType> selectedAnchors;
}
