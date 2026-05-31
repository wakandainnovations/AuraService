package com.aura.service.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "abuse_reports")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AbuseReport {

    public enum Category {
        HARASSMENT,
        MISINFORMATION,
        IMPERSONATION,
        OTHER
    }

    public enum Status {
        SUBMITTED,
        UPHELD,
        REJECTED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "mention_id", nullable = false)
    private Long mentionId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Category category;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "external_ref")
    private String externalRef;

    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;

    /** Set when the moderation backend reaches a terminal {@link Status} (UPHELD/REJECTED). */
    @Column(name = "resolved_at")
    private Instant resolvedAt;
}
