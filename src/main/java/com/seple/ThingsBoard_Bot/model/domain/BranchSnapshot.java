package com.seple.ThingsBoard_Bot.model.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BranchSnapshot {
    private BranchIdentity identity;
    private GatewayStatus gateway;
    private PowerStatus power;
    private BranchSubsystems subsystems;
    private CctvStatus cctv;
    private AlertSummary alerts;
    private HardwareHealth hardware;

    @Builder.Default
    private List<String> rawSourceWarnings = new ArrayList<>();

    @Builder.Default
    private Map<String, Object> rawData = Map.of();
}
