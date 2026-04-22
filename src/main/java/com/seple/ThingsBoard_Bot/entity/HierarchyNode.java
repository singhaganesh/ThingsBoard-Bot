package com.seple.ThingsBoard_Bot.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "hierarchy_nodes")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HierarchyNode {

    @Id
    @Column(name = "node_id", length = 128)
    private String nodeId;

    @Column(name = "customer_id", nullable = false, length = 64)
    private String customerId;

    @Column(name = "parent_id", length = 128)
    private String parentId;

    @Column(name = "node_type", nullable = false, length = 32)
    private String nodeType;

    @Column(name = "node_level", nullable = false)
    private Integer nodeLevel;

    @Column(name = "display_name", nullable = false, length = 256)
    private String displayName;

    @Column(name = "is_leaf", nullable = false)
    private Boolean isLeaf;

    @Column(name = "tb_device_id")
    private UUID tbDeviceId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public enum NodeType {
        CLIENT,
        HO,
        FGMO,
        ZO,
        RO,
        LHO,
        RBO,
        CO,
        BRANCH
    }
}
