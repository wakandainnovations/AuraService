package com.aura.service.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

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

    @Column
    private String title;

    @Column(name = "plan_text", columnDefinition = "TEXT", nullable = false)
    private String planText;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "crisis_plan_tags", joinColumns = @JoinColumn(name = "crisis_plan_id"))
    @Column(name = "tag")
    @Builder.Default
    private List<String> tags = new ArrayList<>();

    @Column(name = "is_favorite", nullable = false, columnDefinition = "boolean not null default false")
    @Builder.Default
    private boolean isFavorite = false;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
