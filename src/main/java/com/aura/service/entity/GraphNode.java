package com.aura.service.entity;

import com.aura.service.enums.GraphNodeType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;

@Entity
@Table(name = "graph_nodes", indexes = {
        @Index(name = "idx_graph_nodes_type", columnList = "type"),
        @Index(name = "idx_graph_nodes_owner_id", columnList = "owner_id")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GraphNode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private GraphNodeType type;

    // Same nullable-at-the-DB, always-set-at-the-app-layer convention as ManagedEntity.owner (see
    // EntityOwnerBackfill) — lets ddl-auto=update add the column to an already-populated table.
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "owner_id")
    private User owner;

    // Arbitrary per-node-type data (e.g. a MOVIE node's title, an ACTOR node's name) stored as
    // Postgres jsonb rather than a fixed set of columns, since node shape varies by type.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> attributes;
}
