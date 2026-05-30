package com.aura.service.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "reply_templates")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReplyTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String body;

    @Column
    private String tone;

    @Column(name = "use_count", nullable = false)
    private int useCount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
