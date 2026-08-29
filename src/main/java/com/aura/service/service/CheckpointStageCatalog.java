package com.aura.service.service;

import com.aura.service.enums.CheckpointStage;
import com.aura.service.enums.CheckpointType;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Single source of truth for the 9 default marketing-lifecycle stages seeded onto every movie by
 * {@link CheckpointDefaultsService}. Stages 1-5 have no formula-derivable window, so their date must
 * be supplied by the user (via the existing checkpoint update endpoint); stages 6-9 have their window
 * computed from the movie's releaseDate via {@code releaseOffsetStartDays}/{@code releaseOffsetEndDays}.
 *
 * <p>Objective/checkpoint-question/window-description text for stages 1-5 comes verbatim from product.
 * Stages 6-9's checkpoint questions are draft copy pending a marketing content pass - structurally
 * correct (they mirror each window's stated objective) but not product-authored text.
 */
public final class CheckpointStageCatalog {

    public record StageDefinition(
            CheckpointStage stage,
            int stageNumber,
            String displayName,
            String objective,
            String checkpointQuestion,
            String windowDescription,
            boolean windowComputedFromRelease,
            Integer releaseOffsetStartDays,
            Integer releaseOffsetEndDays,
            CheckpointType defaultCheckpointType) {
    }

    private static final Map<CheckpointStage, StageDefinition> CATALOG = buildCatalog();

    private CheckpointStageCatalog() {
    }

    public static Map<CheckpointStage, StageDefinition> all() {
        return CATALOG;
    }

    public static StageDefinition byStage(CheckpointStage stage) {
        return CATALOG.get(stage);
    }

    private static Map<CheckpointStage, StageDefinition> buildCatalog() {
        Map<CheckpointStage, StageDefinition> catalog = new LinkedHashMap<>();
        addManual(catalog, CheckpointStage.ANCHOR_SEED, 1, "Pre-Announcement",
                "Install tribal ownership before mass awareness",
                "Does at least one tribe have a concrete reason to claim this film as theirs?",
                "Pre-announcement to first reveal", CheckpointType.OTHER);
        addManual(catalog, CheckpointStage.TENSION_CURIOSITY, 2, "Teaser Release",
                "Open an information gap without resolving it",
                "Are people asking questions rather than just watching?",
                "Teaser", CheckpointType.TEASER);
        addManual(catalog, CheckpointStage.AMPLIFICATION, 3, "Trailer Release",
                "Convert private curiosity into public, imitable behavior; plant recurring triggers",
                "Has the reveal itself become a shareable moment?",
                "Trailer + star reveal", CheckpointType.TRAILER);
        addManual(catalog, CheckpointStage.STORY_SEEDING, 4, "Press Cycle",
                "Give the seed tribe narrative material to retell",
                "Could a fan retell this as an anecdote, not a tagline?",
                "Press cycle, weeks pre-release", CheckpointType.PRESS_MEET);
        addManual(catalog, CheckpointStage.PROOF_FOMO, 5, "Pre-Release Buzz",
                "Activate second-order curiosity via visible external reactions",
                "Is there evidence of others' reactions circulating, not just studio content?",
                "Final week pre-release", CheckpointType.OTHER);
        addComputed(catalog, CheckpointStage.THEATRICAL_WINDOW, 6, "Theatrical Window",
                "Maximize FOMO, zeitgeist, and urgency",
                "Does messaging still say \"now, before it's gone\"?",
                "Day 1 to day 17-45", 1, 45, CheckpointType.OTHER);
        addComputed(catalog, CheckpointStage.PVOD_WINDOW, 7, "PVOD Window",
                "Monetize early adopters; flip message to convenience",
                "Has messaging flipped from scarcity to convenience?",
                "Day 30 to day 60", 30, 60, CheckpointType.OTHER);
        addComputed(catalog, CheckpointStage.SVOD_WINDOW, 8, "SVOD Window",
                "Maximize completion rate via algorithmic discovery",
                "Is effort going into metadata/thumbnail/algorithm rather than word-of-mouth?",
                "Day 45 to day 90+", 45, 90, CheckpointType.OTHER);
        addComputed(catalog, CheckpointStage.LINEAR_TV_AVOD, 9, "Linear TV / AVOD",
                "Extract long-tail catalog value via low-cost resurfacing",
                "Is there a quotable/meme asset that can resurface without new spend?",
                "Year 1 to 3+", 365, 1095, CheckpointType.OTHER);
        return Collections.unmodifiableMap(catalog);
    }

    private static void addManual(Map<CheckpointStage, StageDefinition> catalog, CheckpointStage stage,
            int stageNumber, String displayName, String objective, String checkpointQuestion,
            String windowDescription, CheckpointType defaultCheckpointType) {
        catalog.put(stage, new StageDefinition(stage, stageNumber, displayName, objective, checkpointQuestion,
                windowDescription, false, null, null, defaultCheckpointType));
    }

    private static void addComputed(Map<CheckpointStage, StageDefinition> catalog, CheckpointStage stage,
            int stageNumber, String displayName, String objective, String checkpointQuestion,
            String windowDescription, int releaseOffsetStartDays, int releaseOffsetEndDays,
            CheckpointType defaultCheckpointType) {
        catalog.put(stage, new StageDefinition(stage, stageNumber, displayName, objective, checkpointQuestion,
                windowDescription, true, releaseOffsetStartDays, releaseOffsetEndDays, defaultCheckpointType));
    }
}
