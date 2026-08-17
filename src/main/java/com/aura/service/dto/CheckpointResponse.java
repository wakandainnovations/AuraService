package com.aura.service.dto;

import com.aura.service.enums.CheckpointType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

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
}
