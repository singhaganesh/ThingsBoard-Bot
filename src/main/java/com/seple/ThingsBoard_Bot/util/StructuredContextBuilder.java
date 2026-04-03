package com.seple.ThingsBoard_Bot.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seple.ThingsBoard_Bot.model.domain.BranchSnapshot;

@Component
public class StructuredContextBuilder {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public String build(List<BranchSnapshot> snapshots, BranchSnapshot targetBranch) throws JsonProcessingException {
        Map<String, Object> context = new LinkedHashMap<>();
        if (targetBranch != null) {
            context.put("focus_branch", compactBranch(targetBranch));
        } else {
            List<Map<String, Object>> branches = new ArrayList<>();
            for (BranchSnapshot snapshot : snapshots) {
                branches.add(compactBranch(snapshot));
            }
            context.put("branches", branches);
        }
        return objectMapper.writeValueAsString(context);
    }

    private Map<String, Object> compactBranch(BranchSnapshot snapshot) {
        Map<String, Object> branch = new LinkedHashMap<>();
        branch.put("branch_name", snapshot.getIdentity().getBranchName());
        branch.put("technical_id", snapshot.getIdentity().getTechnicalId());
        branch.put("gateway", snapshot.getGateway().getState());
        branch.put("battery_voltage", snapshot.getPower().getBatteryVoltage());
        branch.put("ac_voltage", snapshot.getPower().getAcVoltage());
        branch.put("system_current", snapshot.getPower().getSystemCurrent());
        branch.put("alarm_count", snapshot.getAlerts().getAlarmCount());
        branch.put("error_count", snapshot.getAlerts().getErrorCount());
        branch.put("subsystems", Map.of(
                "cctv", snapshot.getSubsystems().getCctv().getState(),
                "ias", snapshot.getSubsystems().getIas().getState(),
                "bas", snapshot.getSubsystems().getBas().getState(),
                "fas", snapshot.getSubsystems().getFas().getState(),
                "timeLock", snapshot.getSubsystems().getTimeLock().getState(),
                "accessControl", snapshot.getSubsystems().getAccessControl().getState()
        ));
        if (snapshot.getCctv().getCameraCount() != null) {
            branch.put("cctv_online_count", snapshot.getCctv().getOnlineCameraCount());
            branch.put("cctv_total_count", snapshot.getCctv().getCameraCount());
        }
        return branch;
    }
}
