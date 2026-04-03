package com.seple.ThingsBoard_Bot.model.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PowerStatus {
    private Double batteryVoltage;
    private Double acVoltage;
    private Double systemCurrent;
    private Boolean batteryLow;
    private Boolean mainsOn;
    private String batteryVoltageSource;
}
