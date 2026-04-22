package com.seple.ThingsBoard_Bot.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "customers")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Customer {

    @Id
    @Column(name = "customer_id", length = 64)
    private String customerId;

    @Column(name = "name", nullable = false, length = 256)
    private String name;

    @Column(name = "display_name", length = 256)
    private String displayName;

    @Column(name = "hierarchy_template", length = 32)
    private String hierarchyTemplate;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
