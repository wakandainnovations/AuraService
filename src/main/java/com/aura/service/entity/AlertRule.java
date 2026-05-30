package com.aura.service.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * A user-owned rule that configures when sentiment alerts fire for that user.
 * <p>
 * {@code entityId} is nullable: a rule with no entity acts as a wildcard that
 * applies to every entity the user watches. {@code threshold} is interpreted
 * per {@link SentimentAlert.Kind} — for {@code SPIKE} it is the minimum absolute
 * rise in negative-sentiment ratio over baseline (e.g. {@code 0.10}); for
 * {@code INFLUENCER_NEGATIVE} it is unused (the alert is presence-based).
 */
@Entity
@Table(name = "alert_rules")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AlertRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "entity_id")
    private Long entityId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SentimentAlert.Kind kind;

    @Column(nullable = false)
    private double threshold;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "alert_rule_channels", joinColumns = @JoinColumn(name = "alert_rule_id"))
    @Column(name = "channel")
    @Builder.Default
    private List<String> channels = new ArrayList<>();

    @Column(nullable = false)
    @Builder.Default
    private boolean enabled = true;
}
