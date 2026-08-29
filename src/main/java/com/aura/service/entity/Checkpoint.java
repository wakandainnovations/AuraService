package com.aura.service.entity;

import com.aura.service.enums.AnchorType;
import com.aura.service.enums.CheckpointStage;
import com.aura.service.enums.CheckpointType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * A single marketing-lifecycle event or milestone against a movie. A checkpoint is either a default
 * one seeded by {@link com.aura.service.service.CheckpointDefaultsService} ({@code isDefault=true},
 * {@code stage} set) or a free-form one a user adds themselves ({@code isDefault=false}, {@code stage}
 * null). {@code checkpointDate} is nullable: a default stage-1..5 (pre-release) row starts with no date
 * until the user supplies one (via the existing update endpoint); stage-6..9 (post-release) rows have
 * their date computed from the movie's releaseDate. Postgres treats multiple NULLs in the
 * {@code (managed_entity_id, checkpoint_date)} unique constraint as distinct, so several null-dated
 * default rows for the same movie coexist safely.
 */
@Entity
@Table(name = "checkpoints", uniqueConstraints =
        @UniqueConstraint(columnNames = {"managed_entity_id", "checkpoint_date"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Checkpoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "managed_entity_id", nullable = false)
    private ManagedEntity managedEntity;

    @Column(name = "checkpoint_date")
    private LocalDate checkpointDate;

    @Column(nullable = false, length = 20)
    private String description;

    // Nullable so that under ddl-auto=update (no Flyway) the column can be added to an already-
    // populated table; a startup backfill (CheckpointTypeBackfill) then sets any legacy null to
    // OTHER. On a fresh database it is always populated.
    @Enumerated(EnumType.STRING)
    @Column(name = "checkpoint_type", length = 30)
    private CheckpointType checkpointType;

    // Which of the 9 default lifecycle stages this checkpoint represents; null for a user-added
    // custom checkpoint. See CheckpointStageCatalog for each stage's full definition.
    @Enumerated(EnumType.STRING)
    @Column(name = "stage", length = 30)
    private CheckpointStage stage;

    // True for the 9 rows auto-seeded by CheckpointDefaultsService; false for user-added checkpoints.
    @Column(name = "is_default", nullable = false)
    @Builder.Default
    private boolean isDefault = false;

    // Informational upper bound of a computed window (stage 6-9 rows only); checkpointDate holds the
    // window's start. Null for stage 1-5 rows and user-added custom checkpoints.
    @Column(name = "window_end_date")
    private LocalDate windowEndDate;

    // Only meaningful on the ANCHOR_SEED stage row: which of the 4 AnchorTypeCatalog options the user
    // has picked for this movie. Empty on every other checkpoint.
    @ElementCollection
    @CollectionTable(name = "checkpoint_selected_anchors", joinColumns = @JoinColumn(name = "checkpoint_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "anchor_type")
    @Builder.Default
    private List<AnchorType> selectedAnchors = new ArrayList<>();
}
