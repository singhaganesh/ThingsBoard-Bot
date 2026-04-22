package com.seple.ThingsBoard_Bot.repository;

import com.seple.ThingsBoard_Bot.entity.DeviceEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DeviceEventRepository extends JpaRepository<DeviceEvent, Long> {

    @Query("SELECT e FROM DeviceEvent e WHERE e.tbMessageId = :messageId")
    Optional<DeviceEvent> findByTbMessageId(@Param("messageId") UUID messageId);

    boolean existsByTbMessageId(UUID tbMessageId);

    List<DeviceEvent> findByCustomerIdOrderByEventTimeDesc(String customerId, Pageable pageable);

    @Query("SELECT e FROM DeviceEvent e WHERE e.customerId = :customerId AND e.branchNodeId = :branchNodeId ORDER BY e.eventTime DESC")
    List<DeviceEvent> findByCustomerIdAndBranchNodeId(
            @Param("customerId") String customerId,
            @Param("branchNodeId") String branchNodeId,
            Pageable pageable);

    @Query("SELECT e FROM DeviceEvent e WHERE e.customerId = :customerId AND e.eventTime BETWEEN :startTime AND :endTime ORDER BY e.eventTime ASC")
    List<DeviceEvent> findByCustomerIdAndTimeRange(
            @Param("customerId") String customerId,
            @Param("startTime") Instant startTime,
            @Param("endTime") Instant endTime,
            Pageable pageable);

    @Query("SELECT COUNT(e) FROM DeviceEvent e WHERE e.customerId = :customerId AND e.field = :field AND e.newValue = :value")
    long countByCustomerIdAndFieldAndValue(
            @Param("customerId") String customerId,
            @Param("field") String field,
            @Param("value") String value);

    @Query(value = "SELECT * FROM device_events WHERE customer_id = :customerId AND event_time BETWEEN :startTime AND :endTime ORDER BY event_time ASC", nativeQuery = true)
    List<DeviceEvent> streamByCustomerIdAndTimeRange(
            @Param("customerId") String customerId,
            @Param("startTime") Instant startTime,
            @Param("endTime") Instant endTime);
}