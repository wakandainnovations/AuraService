package com.aura.service.service;

import com.aura.service.dto.CheckpointRecommendation;
import com.aura.service.dto.CheckpointRecommendationsResponse;
import com.aura.service.entity.Checkpoint;
import com.aura.service.entity.ManagedEntity;
import com.aura.service.enums.CheckpointStage;
import com.aura.service.repository.CheckpointRepository;
import com.aura.service.repository.MentionRepository;
import com.aura.service.service.CheckpointStageCatalog.StageDefinition;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Rule-based (never LLM-generated) v1 recommendations against the default lifecycle checkpoints:
 * <ul>
 *   <li>INSUFFICIENT_ANCHORS - the ANCHOR_SEED stage has fewer than {@link #MIN_ANCHORS} anchors
 *       selected, per the product rule that a movie needs 2-3 anchors to gain traction.</li>
 *   <li>BELOW_PEER_TRACTION - a default checkpoint's mention volume in a window around its date is
 *       meaningfully below the average of the entity's configured competitors' volume in the same
 *       window. Skipped entirely when the entity has no competitors (no valid baseline) or the peer
 *       average is below {@link #MIN_PEER_SAMPLE} (avoids flagging against noise).</li>
 * </ul>
 * Deliberately separate from {@link RecommendedActionCandidateServiceImpl}, which is scoped to
 * box-office-factor candidates and not a fit for this simpler, checkpoint-specific rule set.
 */
@Service
@RequiredArgsConstructor
public class CheckpointRecommendationService {

    private static final int MIN_ANCHORS = 2;
    private static final int TRACTION_WINDOW_DAYS = 7;
    private static final double BELOW_PEER_THRESHOLD = 0.5;
    private static final long MIN_PEER_SAMPLE = 5;

    private final CheckpointRepository checkpointRepository;
    private final MentionRepository mentionRepository;
    private final EntityAccessService entityAccessService;

    public CheckpointRecommendationsResponse getRecommendations(Long entityId) {
        ManagedEntity entity = entityAccessService.assertOwnedByCurrentUser(entityId);

        List<Checkpoint> defaults = checkpointRepository.findByManagedEntityIdAndIsDefaultTrue(entityId);

        List<CheckpointRecommendation> recommendations = new ArrayList<>();
        anchorRule(defaults).ifPresent(recommendations::add);
        recommendations.addAll(peerTractionRules(entity, defaults));

        return CheckpointRecommendationsResponse.builder()
                .entityId(entity.getId())
                .entityName(entity.getName())
                .recommendations(recommendations)
                .build();
    }

    private Optional<CheckpointRecommendation> anchorRule(List<Checkpoint> defaults) {
        Checkpoint anchorCheckpoint = defaults.stream()
                .filter(c -> c.getStage() == CheckpointStage.ANCHOR_SEED)
                .findFirst()
                .orElse(null);
        if (anchorCheckpoint == null) {
            return Optional.empty();
        }

        int selected = anchorCheckpoint.getSelectedAnchors().size();
        if (selected >= MIN_ANCHORS) {
            return Optional.empty();
        }

        StageDefinition def = CheckpointStageCatalog.byStage(CheckpointStage.ANCHOR_SEED);
        String message = String.format(
                "Only %d anchor(s) selected for %s - select at least %d-3 (of Casting/Influencer, "
                        + "Physical/Engineering Asset, Established IP/Director, Viral Behind-the-Scenes) "
                        + "to build tribal ownership before wide awareness.",
                selected, def.displayName(), MIN_ANCHORS);

        return Optional.of(CheckpointRecommendation.builder()
                .stage(CheckpointStage.ANCHOR_SEED)
                .checkpointId(anchorCheckpoint.getId())
                .ruleType("INSUFFICIENT_ANCHORS")
                .message(message)
                .selectedAnchorCount(selected)
                .requiredAnchorCount(MIN_ANCHORS)
                .build());
    }

    private List<CheckpointRecommendation> peerTractionRules(ManagedEntity entity, List<Checkpoint> defaults) {
        List<ManagedEntity> competitors = entity.getCompetitors();
        if (competitors == null || competitors.isEmpty()) {
            return List.of();
        }

        Map<CheckpointStage, StageDefinition> catalog = CheckpointStageCatalog.all();
        List<CheckpointRecommendation> result = new ArrayList<>();

        for (Checkpoint checkpoint : defaults) {
            LocalDate date = checkpoint.getCheckpointDate();
            if (date == null || checkpoint.getStage() == null) {
                continue;
            }

            Instant start = date.minusDays(TRACTION_WINDOW_DAYS).atStartOfDay(ZoneOffset.UTC).toInstant();
            Instant end = date.plusDays(TRACTION_WINDOW_DAYS).atStartOfDay(ZoneOffset.UTC).toInstant().minusNanos(1);

            long selfCount = mentionRepository.countByManagedEntityIdAndPostDateBetween(entity.getId(), start, end);

            long peerTotal = 0;
            long topPeerCount = -1;
            String topPeerName = null;
            for (ManagedEntity competitor : competitors) {
                long peerCount = mentionRepository.countByManagedEntityIdAndPostDateBetween(
                        competitor.getId(), start, end);
                peerTotal += peerCount;
                if (peerCount > topPeerCount) {
                    topPeerCount = peerCount;
                    topPeerName = competitor.getName();
                }
            }
            double peerAverage = (double) peerTotal / competitors.size();

            if (peerAverage < MIN_PEER_SAMPLE || selfCount >= BELOW_PEER_THRESHOLD * peerAverage) {
                continue;
            }

            StageDefinition def = catalog.get(checkpoint.getStage());
            StageDefinition nextDef = nextStage(catalog, def);
            String peerLabel = topPeerName != null ? topPeerName : "peer movies";
            String message = nextDef != null
                    ? String.format(
                            "Compared to %s, there was not enough buzz for this %s - work on %s to build traction.",
                            peerLabel, def.displayName(), nextDef.displayName())
                    : String.format(
                            "Compared to %s, there was not enough buzz for this %s.",
                            peerLabel, def.displayName());

            result.add(CheckpointRecommendation.builder()
                    .stage(checkpoint.getStage())
                    .checkpointId(checkpoint.getId())
                    .ruleType("BELOW_PEER_TRACTION")
                    .message(message)
                    .selfMentionCount(selfCount)
                    .peerAverageMentionCount(peerAverage)
                    .peerEntityNames(competitors.stream().map(ManagedEntity::getName).toList())
                    .build());
        }

        return result;
    }

    private StageDefinition nextStage(Map<CheckpointStage, StageDefinition> catalog, StageDefinition current) {
        return catalog.values().stream()
                .filter(def -> def.stageNumber() == current.stageNumber() + 1)
                .findFirst()
                .orElse(null);
    }
}
