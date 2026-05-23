package com.aura.service.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "crisis_plans")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CrisisPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "entity_id", nullable = false)
    private Long entityId;

    @Column(name = "mention_id", nullable = false)
    private Long mentionId;

    @Column(name = "plan_text", columnDefinition = "TEXT", nullable = false)
    private String planText;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
