package com.aura.service.dto;

import com.aura.service.enums.CheckpointStage;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Movie-facing view of a {@link com.aura.service.service.CheckpointStageCatalog.StageDefinition}. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CheckpointStageDefinitionResponse {
    private CheckpointStage stage;
    private int stageNumber;
    private String displayName;
    private String objective;
    private String checkpointQuestion;
    private String windowDescription;
    private boolean windowComputedFromRelease;
}
