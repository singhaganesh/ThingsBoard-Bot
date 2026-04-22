package com.seple.ThingsBoard_Bot.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "device_events")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeviceEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false, length = 64)
    private String customerId;

    @Column(name = "branch_node_id", nullable = false, length = 128)
    private String branchNodeId;

    @Column(name = "tb_message_id")
    private UUID tbMessageId;

    @Column(name = "log_type", length = 64)
    private String logType;

    @Column(name = "field", length = 64)
    private String field;

    @Column(name = "prev_value", length = 64)
    private String prevValue;

    @Column(name = "new_value", length = 64)
    private String newValue;

    @Column(name = "event_time", nullable = false)
    private Instant eventTime;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_payload", columnDefinition = "jsonb")
    private Map<String, Object> rawPayload;

    @PrePersist
    public void prePersist() {
        if (receivedAt == null) {
            receivedAt = Instant.now();
        }
        if (eventTime == null) {
            eventTime = Instant.now();
        }
    }
}
