package com.aura.service.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(
        name = "user_entity_views",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_user_entity_views_user_entity",
                columnNames = {"user_id", "entity_id"}
        )
)
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserEntityView {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "entity_id", nullable = false)
    private Long entityId;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;
}
