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
        SPIKE,
        INFLUENCER_NEGATIVE
    }

    public enum Status {
        OPEN,
        ACKNOWLEDGED,
        ACKED,
        DISMISSED,
        RESOLVED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "managed_entity_id", nullable = false)
    private Long managedEntityId;

    /**
     * The user whose {@link AlertRule} triggered this alert. Null for alerts
     * raised by the default fallback thresholds (no matching rule).
     */
    @Column(name = "owner_user_id")
    private Long ownerUserId;

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

    @Column(name = "source_mention_id")
    private Long sourceMentionId;

    @Column(name = "matched_author")
    private String matchedAuthor;

    @Column(name = "permalink")
    private String permalink;

    @Column(name = "acked_at")
    private Instant ackedAt;

    @Column(name = "acked_by")
    private String ackedBy;

    @Column(name = "dismissed_at")
    private Instant dismissedAt;

    @Column(name = "dismissed_by")
    private String dismissedBy;

    @Column(name = "dismiss_reason", columnDefinition = "TEXT")
    private String dismissReason;
}
