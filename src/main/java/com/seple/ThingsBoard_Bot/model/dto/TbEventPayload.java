package com.seple.ThingsBoard_Bot.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TbEventPayload {

    private String deviceName;
    private String deviceId;
    private String customerId;
    private String branchName;
    private String tbMessageId;
    private String logType;
    private String field;
    private String prevValue;
    private String newValue;
    private Instant eventTime;
    private Instant receivedAt;
    private Map<String, Object> rawPayload;
    private Map<String, Object> attributes;
    private Map<String, Object> telemetry;
}