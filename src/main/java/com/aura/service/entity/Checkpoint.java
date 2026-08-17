package com.aura.service.entity;

import com.aura.service.enums.CheckpointType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

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

    @Column(name = "checkpoint_date", nullable = false)
    private LocalDate checkpointDate;

    @Column(nullable = false, length = 20)
    private String description;

    // Nullable so that under ddl-auto=update (no Flyway) the column can be added to an already-
    // populated table; a startup backfill (CheckpointTypeBackfill) then sets any legacy null to
    // OTHER. On a fresh database it is always populated.
    @Enumerated(EnumType.STRING)
    @Column(name = "checkpoint_type", length = 30)
    private CheckpointType checkpointType;
}
