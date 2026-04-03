package com.seple.ThingsBoard_Bot.model.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlertSummary {
    private int alarmCount;
    private int errorCount;
    private boolean nvrOff;
    private boolean hddError;
    private boolean cameraDisconnect;
    private boolean cameraTamper;
    private boolean intrusionActivate;
    private boolean fireActivate;
    private boolean powerOff;
    private boolean batteryLow;
}
