package com.aura.service.entity;

import com.aura.service.enums.GraphRelationType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "graph_edges", indexes = {
        @Index(name = "idx_graph_edges_from_node_id", columnList = "from_node_id"),
        @Index(name = "idx_graph_edges_to_node_id", columnList = "to_node_id"),
        @Index(name = "idx_graph_edges_relation_type", columnList = "relation_type")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GraphEdge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Plain ids rather than @ManyToOne GraphNode references: an edge table is looked up and
    // traversed by id in bulk (e.g. "all edges from node X"), and both endpoints are always
    // GraphNode regardless of the nodes' own `type`, so there's no need to eagerly load either side.
    @Column(name = "from_node_id", nullable = false)
    private Long fromNodeId;

    @Column(name = "to_node_id", nullable = false)
    private Long toNodeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "relation_type", nullable = false)
    private GraphRelationType relationType;

    @Column(nullable = false)
    private Instant timestamp;

    // Optional strength/confidence of this edge (e.g. sentiment-derived weight for POSTED_ABOUT,
    // a match confidence for MENTIONED); null where a relation type has no such notion.
    @Column
    private Double weight;
}
