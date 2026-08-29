package com.aura.service.service;

import com.aura.service.service.CheckpointStageCatalog.StageDefinition;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CheckpointStageCatalogTest {

    @Test
    void hasAllNineStages() {
        assertThat(CheckpointStageCatalog.all()).hasSize(9);
    }

    @Test
    void everyStageHasCompleteMetadataAndDisplayNameFitsDescriptionField() {
        for (StageDefinition def : CheckpointStageCatalog.all().values()) {
            assertThat(def.stage()).isNotNull();
            assertThat(def.stageNumber()).isBetween(1, 9);
            assertThat(def.displayName()).isNotBlank();
            assertThat(def.displayName().length()).isLessThanOrEqualTo(20);
            assertThat(def.objective()).isNotBlank();
            assertThat(def.checkpointQuestion()).isNotBlank();
            assertThat(def.windowDescription()).isNotBlank();
            assertThat(def.defaultCheckpointType()).isNotNull();
        }
    }

    @Test
    void stagesOneThroughFiveNeedAManualDate() {
        for (int stageNumber = 1; stageNumber <= 5; stageNumber++) {
            StageDefinition def = byStageNumber(stageNumber);
            assertThat(def.windowComputedFromRelease()).isFalse();
            assertThat(def.releaseOffsetStartDays()).isNull();
            assertThat(def.releaseOffsetEndDays()).isNull();
        }
    }

    @Test
    void stagesSixThroughNineHaveAComputedWindow() {
        for (int stageNumber = 6; stageNumber <= 9; stageNumber++) {
            StageDefinition def = byStageNumber(stageNumber);
            assertThat(def.windowComputedFromRelease()).isTrue();
            assertThat(def.releaseOffsetStartDays()).isNotNull();
            assertThat(def.releaseOffsetEndDays()).isNotNull();
            assertThat(def.releaseOffsetEndDays()).isGreaterThan(def.releaseOffsetStartDays());
        }
    }

    private StageDefinition byStageNumber(int stageNumber) {
        return CheckpointStageCatalog.all().values().stream()
                .filter(def -> def.stageNumber() == stageNumber)
                .findFirst()
                .orElseThrow();
    }
}
