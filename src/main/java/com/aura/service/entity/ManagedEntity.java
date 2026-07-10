package com.aura.service.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "managed_entities")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ManagedEntity {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false)
    private String name;
    
    @Column(nullable = false)
    private String type;

    // The user who created (and exclusively owns) this entity. Required at the application layer:
    // every create stamps the current user, and a startup backfill assigns any legacy null to the
    // seeded admin. The DB column is left nullable so that, under ddl-auto=update (no Flyway), the
    // column can be added to an already-populated managed_entities table and then backfilled —
    // a NOT NULL add would fail on existing rows before the backfill could run.
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "owner_id")
    private User owner;

    @Column
    private String director;
    
    @ElementCollection
    @CollectionTable(name = "entity_actors", joinColumns = @JoinColumn(name = "entity_id"))
    @Column(name = "actor")
    private List<String> actors = new ArrayList<>();
    
    @ElementCollection
    @CollectionTable(name = "entity_keywords", joinColumns = @JoinColumn(name = "entity_id"))
    private List<EntityKeyword> keywords = new ArrayList<>();
    
    @ManyToMany
    @JoinTable(
        name = "entity_competitors",
        joinColumns = @JoinColumn(name = "entity_id"),
        inverseJoinColumns = @JoinColumn(name = "competitor_id")
    )
    private List<ManagedEntity> competitors = new ArrayList<>();

    @Column(name = "release_date")
    private LocalDate releaseDate;

    @Column(name = "language")
    private String language;

    @Column(name = "industry")
    private String industry;

    @Column(name = "genre")
    private String genre;

    @Column(name = "synopsis", columnDefinition = "TEXT")
    private String synopsis;
}
