package com.aura.service.enums;

/**
 * The 9 default marketing-lifecycle stages seeded onto every movie. Stages 1-5 (pre-release) need a
 * user-supplied date; stages 6-9 (post-release) have their window computed from the movie's
 * releaseDate. See {@link com.aura.service.service.CheckpointStageCatalog} for the full definition
 * of each stage (objective, checkpoint question, window description).
 */
public enum CheckpointStage {
    ANCHOR_SEED,
    TENSION_CURIOSITY,
    AMPLIFICATION,
    STORY_SEEDING,
    PROOF_FOMO,
    THEATRICAL_WINDOW,
    PVOD_WINDOW,
    SVOD_WINDOW,
    LINEAR_TV_AVOD
}
