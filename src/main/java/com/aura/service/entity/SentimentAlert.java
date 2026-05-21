package com.aura.service.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "sentiment_alerts")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SentimentAlert {

    public enum Kind {
        SPIKE
    }

    public enum Status {
        OPEN,
        ACKNOWLEDGED,
        RESOLVED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "managed_entity_id", nullable = false)
    private Long managedEntityId;

    @Column(name = "triggered_at", nullable = false)
    private Instant triggeredAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Kind kind;

    @Column(name = "current_value", nullable = false)
    private double currentValue;

    @Column(name = "baseline_value", nullable = false)
    private double baselineValue;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;
}
