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
}
