package com.seple.ThingsBoard_Bot.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Entity
@Table(name = "branch_ancestor_paths")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BranchAncestorPath {

    @Id
    @Column(name = "branch_node_id", length = 128)
    private String branchNodeId;

    @Column(name = "customer_id", nullable = false, length = 64)
    private String customerId;

    @Column(name = "ancestor_path", columnDefinition = "VARCHAR(128)[]")
    private String[] ancestorPath;

    @Column(name = "path_depth", nullable = false)
    private Integer pathDepth;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    public void prePersist() {
        updatedAt = Instant.now();
    }

    public List<String> getAncestorList() {
        return ancestorPath != null ? List.of(ancestorPath) : List.of();
    }
}
