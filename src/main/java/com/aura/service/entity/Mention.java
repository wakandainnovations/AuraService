package com.aura.service.entity;

import com.aura.service.enums.Platform;
import com.aura.service.enums.Sentiment;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

@Entity
@Table(name = "mentions")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Mention {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The entities this post is attributed to. A single post (one {@code mentions} row, unique by
     * {@code postId}) can be relevant to several entities that share a keyword — e.g. two users each
     * tracking the same movie. The link is held in the {@code mention_entities} join table rather than a
     * single {@code managed_entity_id} column, so each linked entity's dashboards count the post.
     */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "mention_entities",
            joinColumns = @JoinColumn(name = "mention_id"),
            inverseJoinColumns = @JoinColumn(name = "managed_entity_id"))
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Set<ManagedEntity> managedEntities = new LinkedHashSet<>();
    
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Platform platform;
    
    @Column(unique = true, nullable = false)
    private String postId;
    
    @Column(columnDefinition = "TEXT")
    private String content;
    
    @Column
    private String author;
    
    @Column(nullable = false)
    private Instant postDate;
    
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Sentiment sentiment;
    
    @Column
    private String permalink;

    @Column(name = "sentiment_score")
    private Short sentimentScore;

    /** Links this post to {@code entity}, initializing the set if needed. */
    public void addManagedEntity(ManagedEntity entity) {
        if (managedEntities == null) {
            managedEntities = new LinkedHashSet<>();
        }
        managedEntities.add(entity);
    }

    /**
     * A single representative entity for the post, used by contexts that only need <em>an</em> entity
     * for display (e.g. echoing an entity id in a response or an alert). For access control prefer
     * resolving the entity the current user actually owns rather than this arbitrary first element.
     */
    public ManagedEntity getPrimaryManagedEntity() {
        if (managedEntities == null || managedEntities.isEmpty()) {
            return null;
        }
        return managedEntities.iterator().next();
    }
}
