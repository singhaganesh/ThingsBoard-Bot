package com.seple.ThingsBoard_Bot.service.normalization;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seple.ThingsBoard_Bot.model.domain.AlertSummary;
import com.seple.ThingsBoard_Bot.model.domain.BranchIdentity;
import com.seple.ThingsBoard_Bot.model.domain.BranchSnapshot;
import com.seple.ThingsBoard_Bot.model.domain.BranchSubsystems;
import com.seple.ThingsBoard_Bot.model.domain.CctvStatus;
import com.seple.ThingsBoard_Bot.model.domain.GatewayStatus;
import com.seple.ThingsBoard_Bot.model.domain.HardwareHealth;
import com.seple.ThingsBoard_Bot.model.domain.NormalizedState;
import com.seple.ThingsBoard_Bot.model.domain.PowerStatus;
import com.seple.ThingsBoard_Bot.model.domain.SubsystemStatus;

@Component
public class BranchSnapshotMapper {

    private final FieldPrecedenceResolver precedenceResolver;
    private final ValueNormalizer valueNormalizer;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public BranchSnapshotMapper(FieldPrecedenceResolver precedenceResolver, ValueNormalizer valueNormalizer) {
        this.precedenceResolver = precedenceResolver;
        this.valueNormalizer = valueNormalizer;
    }

    public BranchSnapshot map(Map<String, Object> raw) {
        List<String> warnings = new ArrayList<>();

        BranchIdentity identity = buildIdentity(raw);
        GatewayStatus gateway = buildGateway(raw, warnings);
        PowerStatus power = buildPower(raw, warnings);
        BranchSubsystems subsystems = BranchSubsystems.builder()
                .cctv(buildSubsystem("CCTV", raw, "cctv", "cctvStatus", "cameraLinkStatus"))
                .ias(buildSubsystem("IAS", raw, "ias", "iasStatus", "ias_status"))
                .bas(buildSubsystem("BAS", raw, "bas", "basStatus"))
                .fas(buildSubsystem("FAS", raw, "fas", "fasStatus", "fireAlarmStatus"))
                .timeLock(buildSubsystem("Time Lock", raw, "timeLock", "tlStatus", "timeLockHealth"))
                .accessControl(buildSubsystem("Access Control", raw, "accessControl", "accessControlStatus"))
                .build();

        return BranchSnapshot.builder()
                .identity(identity)
                .gateway(gateway)
                .power(power)
                .subsystems(subsystems)
                .cctv(buildCctv(raw, subsystems.getCctv()))
                .alerts(buildAlerts(raw))
                .hardware(buildHardware(raw))
                .rawSourceWarnings(warnings)
                .rawData(raw)
                .build();
    }

    private BranchIdentity buildIdentity(Map<String, Object> raw) {
        String branchName = choose(raw, "branchName", "formattedBranchName", "device_name", "deviceName");
        if (branchName != null) {
            branchName = branchName.toUpperCase().trim();
        }
        
        String technicalId = choose(raw, "device_name", "deviceName", "formattedBranchName", "branchName");
        String deviceId = stringValue(raw.get("device_id"));
        String branchId = stringValue(raw.get("branch_id"));

        Set<String> aliasSet = new HashSet<>();
        addAlias(aliasSet, branchName);
        addAlias(aliasSet, technicalId);
        addAlias(aliasSet, stringValue(raw.get("deviceName")));
        addAlias(aliasSet, stringValue(raw.get("formattedBranchName")));
        addAlias(aliasSet, stringValue(raw.get("branchName")));

        return BranchIdentity.builder()
                .branchName(branchName)
                .technicalId(technicalId)
                .deviceId(deviceId)
                .branchId(branchId)
                .aliases(new ArrayList<>(aliasSet))
                .build();
    }

    private GatewayStatus buildGateway(Map<String, Object> raw, List<String> warnings) {
        FieldPrecedenceResolver.ResolvedField resolved = precedenceResolver.resolveGatewayState(raw);
        if (raw.containsKey("gateway") && raw.containsKey("status")) {
            String gateway = stringValue(raw.get("gateway"));
            String status = stringValue(raw.get("status"));
            if (!gateway.equalsIgnoreCase(status)) {
                warnings.add("Gateway/status conflict: gateway=" + gateway + ", status=" + status);
            }
        }

        return GatewayStatus.builder()
                .state(resolved.state())
                .health(stringValue(raw.get("gwHealth")))
                .active(valueNormalizer.toBoolean(stringValue(raw.get("active"))))
                .sourceFieldUsed(resolved.sourceField())
                .build();
    }

    private PowerStatus buildPower(Map<String, Object> raw, List<String> warnings) {
        FieldPrecedenceResolver.ResolvedMetric battery = precedenceResolver.resolveBatteryVoltage(raw);
        FieldPrecedenceResolver.ResolvedMetric ac = precedenceResolver.resolveAcVoltage(raw);
        FieldPrecedenceResolver.ResolvedMetric current = precedenceResolver.resolveSystemCurrent(raw);

        Double batteryStatusVoltage = valueNormalizer.toDouble(raw.get("battery_status_battery_voltage"));
        Double gatewayVoltage = valueNormalizer.toDouble(raw.get("gatewayStatus_battery_voltage"));
        if (batteryStatusVoltage != null && gatewayVoltage != null && !batteryStatusVoltage.equals(gatewayVoltage)) {
            warnings.add("Battery voltage conflict: battery_status_battery_voltage=" + batteryStatusVoltage
                    + ", gatewayStatus_battery_voltage=" + gatewayVoltage);
        }

        return PowerStatus.builder()
                .batteryVoltage(battery.value())
                .batteryVoltageSource(battery.sourceField())
                .acVoltage(ac.value())
                .systemCurrent(current.value())
                .batteryLow(valueNormalizer.toBoolean(stringValue(raw.get("BATTERY LOW"))))
                .mainsOn(valueNormalizer.toBoolean(stringValue(raw.get("MAINS ON"))))
                .build();
    }

    private SubsystemStatus buildSubsystem(String systemName, Map<String, Object> raw, String primaryField, String... fallbacks) {
        FieldPrecedenceResolver.ResolvedField resolved = precedenceResolver.resolveSubsystemState(raw, primaryField, fallbacks);
        NormalizedState state = resolved.state();
        boolean installed = state != NormalizedState.NOT_INSTALLED && state != NormalizedState.UNKNOWN;

        if (!installed) {
            String primaryValue = stringValue(raw.get(primaryField));
            if ("N/A".equalsIgnoreCase(primaryValue) || "null".equalsIgnoreCase(primaryValue)) {
                state = NormalizedState.NOT_INSTALLED;
            }
        }

        String health = null;
        if ("timeLock".equals(primaryField)) {
            health = stringValue(raw.get("timeLockHealth"));
        } else if ("accessControl".equals(primaryField)) {
            health = stringValue(raw.get("accessControlStatus"));
        } else if ("fas".equals(primaryField)) {
            health = choose(raw, "fasStatus", "fireAlarmStatus");
        } else if ("ias".equals(primaryField)) {
            health = choose(raw, "iasStatus", "ias_status");
        } else if ("cctv".equals(primaryField)) {
            health = choose(raw, "cctvStatus", "cameraLinkStatus");
        } else if ("bas".equals(primaryField)) {
            health = stringValue(raw.get("basStatus"));
        }

        return SubsystemStatus.builder()
                .systemName(systemName)
                .state(state)
                .installed(installed)
                .rawValue(resolved.rawValue())
                .health(health)
                .sourceFieldUsed(resolved.sourceField())
                .build();
    }

    private CctvStatus buildCctv(Map<String, Object> raw, SubsystemStatus cctvSubsystem) {
        Integer total = null;
        Integer online = null;
        String hddStatus = null;

        String cameraDetails = stringValue(raw.get("rock_CAMERAdETAILS"));
        if (cameraDetails != null && cameraDetails.startsWith("[")) {
            try {
                JsonNode node = objectMapper.readTree(cameraDetails);
                if (node.isArray()) {
                    int totalCount = 0;
                    int onlineCount = 0;
                    for (JsonNode camera : node) {
                        if (camera == null || camera.isNull() || !camera.isObject()) {
                            continue;
                        }
                        totalCount++;
                        String status = camera.has("status") ? camera.path("status").asText() : camera.path("Active Status").asText();
                        if ("active".equalsIgnoreCase(status) || "online".equalsIgnoreCase(status)) {
                            onlineCount++;
                        }
                    }
                    total = totalCount;
                    online = onlineCount;
                }
            } catch (Exception ignored) {
            }
        }

        String hddInfo = stringValue(raw.get("rock_HddINFO"));
        if (hddInfo != null && hddInfo.startsWith("[")) {
            try {
                JsonNode node = objectMapper.readTree(hddInfo);
                if (node.isArray() && !node.isEmpty()) {
                    JsonNode first = node.get(0);
                    if (first != null && first.isObject()) {
                        hddStatus = first.path("HDDStatus").asText(null);
                    }
                }
            } catch (Exception ignored) {
            }
        }

        return CctvStatus.builder()
                .state(cctvSubsystem != null ? cctvSubsystem.getState() : NormalizedState.UNKNOWN)
                .cameraCount(total)
                .onlineCameraCount(online)
                .hasDisconnect(booleanFlag(raw, "CAMERA DISCONNECT"))
                .hasTamper(booleanFlag(raw, "CAMERA TAMPER"))
                .hddStatus(hddStatus)
                .build();
    }

    private AlertSummary buildAlerts(Map<String, Object> raw) {
        return AlertSummary.builder()
                .alarmCount(valueNormalizer.toInt(raw.get("alarmCount"), 0))
                .errorCount(valueNormalizer.toInt(raw.get("errorCount"), 0))
                .nvrOff(booleanFlag(raw, "DVR/NVR OFF") || booleanFlag(raw, "ticketStatus_NVR_OFF"))
                .hddError(booleanFlag(raw, "HDD ERROR"))
                .cameraDisconnect(booleanFlag(raw, "CAMERA DISCONNECT"))
                .cameraTamper(booleanFlag(raw, "CAMERA TAMPER"))
                .intrusionActivate(booleanFlag(raw, "INTRUSION ALARM SYSTEM ACTIVATE"))
                .fireActivate(booleanFlag(raw, "FIRE ALARM SYSTEM ACTIVATE"))
                .powerOff(booleanFlag(raw, "POWER OFF"))
                .batteryLow(booleanFlag(raw, "BATTERY LOW"))
                .build();
    }

    private HardwareHealth buildHardware(Map<String, Object> raw) {
        return HardwareHealth.builder()
                .cpu(valueNormalizer.toDouble(raw.get("cpu")) )
                .memory(valueNormalizer.toDouble(raw.get("memory")))
                .disk(valueNormalizer.toDouble(raw.get("disk")))
                .temperature(valueNormalizer.toDouble(raw.get("temperature")))
                .build();
    }

    private boolean booleanFlag(Map<String, Object> raw, String key) {
        Boolean value = valueNormalizer.toBoolean(stringValue(raw.get(key)));
        return Boolean.TRUE.equals(value);
    }

    private String choose(Map<String, Object> raw, String... keys) {
        for (String key : keys) {
            String value = stringValue(raw.get(key));
            if (value != null && !value.isBlank() && !"null".equalsIgnoreCase(value)) {
                return value;
            }
        }
        return null;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private void addAlias(Set<String> aliasSet, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        String trimmed = value.trim();
        aliasSet.add(trimmed);
        aliasSet.add(trimmed.replace("BOI-", ""));
        aliasSet.add(trimmed.replace("BOI-", "").replace("-", " "));
        aliasSet.add(trimmed.replace("BRANCH ", ""));
        aliasSet.add(trimmed.replace("BRANCH ", "").replace("-", " "));
        aliasSet.add(trimmed.replace(" ", ""));
    }
}
