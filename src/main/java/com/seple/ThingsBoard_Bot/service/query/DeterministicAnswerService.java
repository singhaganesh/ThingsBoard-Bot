package com.seple.ThingsBoard_Bot.service.query;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seple.ThingsBoard_Bot.model.domain.BranchSnapshot;
import com.seple.ThingsBoard_Bot.model.domain.NormalizedState;
import com.seple.ThingsBoard_Bot.model.domain.SubsystemStatus;

@Service
public class DeterministicAnswerService {

    private final AnswerTemplateService answerTemplateService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DeterministicAnswerService(AnswerTemplateService answerTemplateService) {
        this.answerTemplateService = answerTemplateService;
    }

    public String answer(ResolvedQuery query, List<BranchSnapshot> snapshots) {
        return switch (query.getIntent()) {
            case GLOBAL_OVERVIEW -> answerGlobalOverview(snapshots);
            case GATEWAY_STATUS -> query.getTargetBranch() != null
                    ? answerTemplateService.renderGatewayStatus(query.getTargetBranch(), formatState(query.getTargetBranch().getGateway().getState()))
                    : null;
            case BATTERY_VOLTAGE -> query.getTargetBranch() != null
                    ? answerTemplateService.renderMetric(query.getTargetBranch(), "Battery Voltage Reading",
                            query.getTargetBranch().getPower().getBatteryVoltage(), "V DC")
                    : null;
            case BATTERY_LOW_STATUS -> query.getTargetBranch() != null
                    ? answerBatteryLowStatus(query.getTargetBranch())
                    : null;
            case AC_VOLTAGE -> query.getTargetBranch() != null
                    ? answerTemplateService.renderMetric(query.getTargetBranch(), "AC Input Voltage",
                            query.getTargetBranch().getPower().getAcVoltage(), "V AC")
                    : null;
            case SYSTEM_CURRENT -> query.getTargetBranch() != null
                    ? answerTemplateService.renderMetric(query.getTargetBranch(), "System Current",
                            query.getTargetBranch().getPower().getSystemCurrent(), " Amp")
                    : null;
            case ACTIVE_DEVICES -> query.getTargetBranch() != null
                    ? answerTemplateService.renderActiveDevices(query.getTargetBranch(), activeSystems(query.getTargetBranch()))
                    : null;
            case FAULT_DEVICES -> query.getTargetBranch() != null
                    ? answerFaultDevices(query.getTargetBranch())
                    : null;
            case OFFLINE_DEVICES -> query.getTargetBranch() != null
                    ? answerOfflineDevices(query.getTargetBranch())
                    : null;
            case CONNECTED_DEVICES -> query.getTargetBranch() != null
                    ? answerConnectedDevices(query.getTargetBranch())
                    : null;
            case NETWORK_STATUS -> query.getTargetBranch() != null
                    ? answerNetworkStatus(query.getTargetBranch())
                    : null;
            case CCTV_STATUS -> query.getTargetBranch() != null
                    ? answerTemplateService.renderCctvStatus(query.getTargetBranch(),
                            query.getTargetBranch().getCctv().getOnlineCameraCount(),
                            query.getTargetBranch().getCctv().getCameraCount())
                    : null;
            case CCTV_HDD_INFO -> query.getTargetBranch() != null
                    ? answerCctvHddInfo(query.getTargetBranch())
                    : null;
            case CCTV_RECORDING_INFO -> query.getTargetBranch() != null
                    ? answerCctvRecordingInfo(query.getTargetBranch())
                    : null;
            case ALARM_STATUS -> query.getTargetBranch() != null
                    ? answerTemplateService.renderAlertStatus(query.getTargetBranch(), "Alarm Count",
                            query.getTargetBranch().getAlerts().getAlarmCount())
                    : null;
            case ERROR_STATUS -> query.getTargetBranch() != null
                    ? answerTemplateService.renderAlertStatus(query.getTargetBranch(), "Error Count",
                            query.getTargetBranch().getAlerts().getErrorCount())
                    : null;
            case SUBSYSTEM_STATUS -> query.getTargetBranch() != null
                    ? answerSubsystemStatus(query.getTargetBranch(), query.getTargetSystem())
                    : null;
            case DOOR_STATUS -> query.getTargetBranch() != null
                    ? answerDoorStatus(query.getTargetBranch(), query.getTargetSystem())
                    : null;
            case ACCESS_CONTROL_USER_COUNT -> query.getTargetBranch() != null
                    ? answerAccessControlUserCount(query.getTargetBranch())
                    : null;
            case ACCESS_CONTROL_DEVICE_INFO -> query.getTargetBranch() != null
                    ? answerAccessControlDeviceInfo(query.getTargetBranch())
                    : null;
            case FAULT_REASON -> query.getTargetBranch() != null
                    ? answerFaultReason(query.getTargetBranch())
                    : null;
            case CAMERA_DISCONNECT_HISTORY -> query.getTargetBranch() != null
                    ? answerCameraDisconnectHistory(query)
                    : null;
            case GENERAL_LLM -> null;
        };
    }

    private String answerGlobalOverview(List<BranchSnapshot> snapshots) {
        List<String> online = new ArrayList<>();
        List<String> offline = new ArrayList<>();
        for (BranchSnapshot snapshot : snapshots) {
            if (snapshot.getGateway().getState() == NormalizedState.ONLINE) {
                online.add(snapshot.getIdentity().getBranchName());
            } else {
                offline.add(snapshot.getIdentity().getBranchName());
            }
        }
        return answerTemplateService.renderGlobalOverview(online, offline);
    }

    private String answerSubsystemStatus(BranchSnapshot branch, String targetSystem) {
        if (targetSystem == null) {
            return null;
        }

        SubsystemStatus subsystem = switch (targetSystem) {
            case "ias" -> branch.getSubsystems().getIas();
            case "bas" -> branch.getSubsystems().getBas();
            case "fas" -> branch.getSubsystems().getFas();
            case "timeLock" -> branch.getSubsystems().getTimeLock();
            case "accessControl" -> branch.getSubsystems().getAccessControl();
            case "cctv" -> branch.getSubsystems().getCctv();
            default -> null;
        };

        if (subsystem == null) {
            return null;
        }

        return answerTemplateService.renderSubsystemStatus(branch, subsystem.getSystemName(),
                formatState(subsystem.getState()));
    }

    private String answerFaultReason(BranchSnapshot branch) {
        Map<String, Object> raw = branch.getRawData();
        List<String> reasons = new ArrayList<>();

        if (isTrue(raw.get("ticketStatus_FAS_FAULT")) || isTrue(raw.get("fireAlarmSystem_fault"))
                || isTrue(raw.get("fire_alarm_system_fault"))) {
            reasons.add("fire alarm fault indicator is active");
        }
        if (isTrue(raw.get("ticketStatus_IAS_FAULT")) || isTrue(raw.get("intrusion_alarm_system_fault"))) {
            reasons.add("intrusion alarm fault indicator is active");
        }
        if (isTrue(raw.get("ticketStatus_NVR_OFF")) || isTrue(raw.get("DVR/NVR OFF"))) {
            reasons.add("NVR/DVR off indicator is active");
        }
        if (isTrue(raw.get("HDD ERROR")) || isTrue(raw.get("ticketStatus_HDD_ERROR"))) {
            reasons.add("HDD error indicator is active");
        }
        if (isTrue(raw.get("CAMERA DISCONNECT"))) {
            reasons.add("camera disconnect indicator is active");
        }

        if (reasons.isEmpty()) {
            return "**For Branch " + branchName(branch)
                    + ", no modeled fault reason is currently available.**";
        }

        return "**For Branch " + branchName(branch) + ", there is a fault indication because "
                + String.join(", ", reasons) + ".**";
    }

    private String answerCameraDisconnectHistory(ResolvedQuery query) {
        BranchSnapshot branch = query.getTargetBranch();
        Map<String, Object> raw = branch.getRawData();
        boolean historical = query.getOriginalQuestion() == null
                || containsAny(query.getOriginalQuestion(), "history", "historical");
        List<String> channelsWithHistory = new ArrayList<>();
        List<String> channelsDisconnectedNow = new ArrayList<>();
        for (int i = 1; i <= 16; i++) {
            String key = "cameraDisconnectCH" + i + "_history";
            Object value = raw.get(key);
            if (value == null) {
                value = raw.get("cameraStatus_CAMERA DISCONNECT CH " + i);
                if (isTrue(value)) {
                    channelsDisconnectedNow.add("Channel " + i);
                }
                continue;
            }
            if (hasHistoryEntries(String.valueOf(value))) {
                channelsWithHistory.add("Channel " + i);
            }
        }

        Integer disconnectCount = toInt(raw.get("camera_disconnect_count"));
        if (disconnectCount != null && disconnectCount > 0 && channelsDisconnectedNow.isEmpty()) {
            channelsDisconnectedNow.add(disconnectCount + " camera(s)");
        }

        if (historical) {
            if (channelsWithHistory.isEmpty()) {
                return "**For Branch " + branchName(branch)
                        + ", No historical camera disconnects found.**";
            }
            return "**For Branch " + branchName(branch) + ", Historical camera disconnects found: "
                    + String.join(", ", channelsWithHistory) + ".**";
        }

        if (!channelsDisconnectedNow.isEmpty()) {
            return "**For Branch " + branchName(branch)
                    + ", CCTV Disconnect Status is: " + String.join(", ", channelsDisconnectedNow) + " disconnected.**";
        }
        if (disconnectCount != null && disconnectCount == 0) {
            return "**For Branch " + branchName(branch)
                    + ", CCTV Disconnect Status is: No disconnected cameras detected.**";
        }
        if (channelsWithHistory.isEmpty()) {
            return "**For Branch " + branchName(branch)
                    + ", No historical camera disconnects found.**";
        }
        return "**For Branch " + branchName(branch)
                + ", CCTV Disconnect Status is: No disconnected cameras detected.**";
    }

    private boolean hasHistoryEntries(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return false;
        }
        try {
            JsonNode node = objectMapper.readTree(rawJson);
            return node.isArray() && !node.isEmpty();
        } catch (Exception ignored) {
            return !"[]".equals(rawJson.trim());
        }
    }

    private boolean isTrue(Object value) {
        if (value == null) {
            return false;
        }
        String text = String.valueOf(value).trim();
        return "true".equalsIgnoreCase(text) || "1".equals(text);
    }

    private List<String> activeSystems(BranchSnapshot branch) {
        List<String> active = new ArrayList<>();
        maybeAdd(active, branch.getSubsystems().getCctv(), "CCTV DVR");
        maybeAdd(active, branch.getSubsystems().getIas(), "IAS Panel");
        maybeAdd(active, branch.getSubsystems().getBas(), "BAS Panel");
        maybeAdd(active, branch.getSubsystems().getFas(), "FAS Panel");
        maybeAdd(active, branch.getSubsystems().getTimeLock(), "Time Lock");
        maybeAdd(active, branch.getSubsystems().getAccessControl(), "Access Control Controller");
        return active;
    }

    private void maybeAdd(List<String> active, SubsystemStatus status, String label) {
        if (status != null && status.getState() == NormalizedState.ONLINE) {
            active.add(label);
        }
    }

    private String answerBatteryLowStatus(BranchSnapshot branch) {
        Boolean batteryLow = resolveBoolean(branch.getRawData(), "BATTERY LOW", "gatewayStatus_BATTERY LOW",
                "system_status_statusbox_battery_low", "ticketStatus_BATTERY_LOW");
        if (Boolean.TRUE.equals(batteryLow)) {
            return "**For Branch " + branchName(branch)
                    + ", Battery Low Status is WARNING ACTIVE.**";
        }
        if (Boolean.FALSE.equals(batteryLow)) {
            return "**For Branch " + branchName(branch)
                    + ", Battery Low Status is NORMAL. No low battery warning is active.**";
        }
        return "**For Branch " + branchName(branch) + ", Battery Low Status is N/A.**";
    }

    private String answerFaultDevices(BranchSnapshot branch) {
        List<String> faultDevices = systemsByState(branch, NormalizedState.FAULT);
        if (!faultDevices.isEmpty()) {
            return "**For Branch " + branchName(branch) + ", Fault Devices ("
                    + faultDevices.size() + "): " + String.join(", ", faultDevices) + ".**";
        }

        List<String> branchLevelIndicators = branchLevelFaultIndicators(branch);
        if (!branchLevelIndicators.isEmpty()) {
            return "**For Branch " + branchName(branch)
                    + ", no specific fault device is deterministically identified"
                    + ". Branch-level fault indicators are present: " + String.join(", ", branchLevelIndicators) + ".**";
        }

        return "**For Branch " + branchName(branch)
                + ", no fault devices are currently identified.**";
    }

    private String answerOfflineDevices(BranchSnapshot branch) {
        List<String> offlineDevices = systemsByState(branch, NormalizedState.OFFLINE);
        if (offlineDevices.isEmpty()) {
            return "**For Branch " + branchName(branch)
                    + ", no offline devices are currently identified.**";
        }
        return "**For Branch " + branchName(branch) + ", Offline Devices ("
                + offlineDevices.size() + "): " + String.join(", ", offlineDevices) + ".**";
    }

    private String answerConnectedDevices(BranchSnapshot branch) {
        List<String> connectedDevices = installedSystems(branch);
        if (connectedDevices.isEmpty()) {
            return "**For Branch " + branchName(branch)
                    + ", no connected devices are currently identified.**";
        }
        return "**For Branch " + branchName(branch) + ", Connected Devices ("
                + connectedDevices.size() + "): " + String.join(", ", connectedDevices) + ".**";
    }

    private String answerNetworkStatus(BranchSnapshot branch) {
        Boolean network = resolveBoolean(branch.getRawData(), "NETWORK", "gatewayStatus_NETWORK");
        String operator = firstNonBlank(branch.getRawData(), "system_status_statusbox_network", "networkOperator");

        if (Boolean.TRUE.equals(network)) {
            if (operator != null) {
                return "**For Branch " + branchName(branch)
                        + ", Network Status: ON. Network Operator: " + operator + ".**";
            }
            return "**For Branch " + branchName(branch) + ", Network Status: ON.**";
        }
        if (Boolean.FALSE.equals(network)) {
            return "**For Branch " + branchName(branch) + ", Network Status: OFFLINE.**";
        }
        if (operator != null) {
            return "**For Branch " + branchName(branch)
                    + ", Network Status: ON. Network Operator: " + operator + ".**";
        }
        return "**For Branch " + branchName(branch) + ", Network Status: N/A.**";
    }

    private String answerCctvHddInfo(BranchSnapshot branch) {
        Object rawInfo = branch.getRawData().get("rock_HddINFO");
        if (rawInfo == null) {
            return "**For Branch " + branchName(branch)
                    + ", CCTV HDD Information is not available.**";
        }

        try {
            JsonNode node = objectMapper.readTree(String.valueOf(rawInfo));
            if (!node.isArray() || node.isEmpty()) {
                return "**For Branch " + branchName(branch)
                        + ", CCTV HDD Information is not available.**";
            }

            List<String> slots = new ArrayList<>();
            for (JsonNode entry : node) {
                if (!entry.isObject()) {
                    continue;
                }
                String slot = entry.path("HDDSlots").asText("N/A");
                String status = entry.path("HDDStatus").asText("N/A");
                String capacity = entry.path("HDDcapacity").asText("N/A");
                String free = entry.path("HDDfreeSpace").asText("N/A");
                slots.add("Slot " + slot + ": " + status + ", Capacity " + capacity + " TB, Free " + free + " TB");
            }

            if (slots.isEmpty()) {
                return "**For Branch " + branchName(branch)
                        + ", CCTV HDD Information is not available.**";
            }

            return "**For Branch " + branchName(branch)
                    + ", CCTV HDD Information is: " + String.join("; ", slots) + ".**";
        } catch (Exception ignored) {
            return "**For Branch " + branchName(branch)
                    + ", CCTV HDD Information is not available.**";
        }
    }

    private String answerCctvRecordingInfo(BranchSnapshot branch) {
        Object rawInfo = branch.getRawData().get("rock_VIDEOdETAILS");
        if (rawInfo == null) {
            rawInfo = branch.getRawData().get("Hikvision_NVR_CameraRecInfo");
        }
        if (rawInfo == null) {
            return "**For Branch " + branchName(branch)
                    + ", CCTV Recording Information is not available.**";
        }

        try {
            JsonNode node = objectMapper.readTree(String.valueOf(rawInfo));
            if (!node.isArray() || node.isEmpty()) {
                return "**For Branch " + branchName(branch)
                        + ", CCTV Recording Information is not available.**";
            }

            int withRecording = 0;
            List<String> noRecordingChannels = new ArrayList<>();
            for (JsonNode entry : node) {
                if (!entry.isObject()) {
                    continue;
                }
                int duration = entry.path("total_duration").asInt(0);
                String channel = entry.has("channel_no") ? entry.path("channel_no").asText("") : entry.path("camera_id").asText("");
                if (duration > 0) {
                    withRecording++;
                } else if (channel != null && !channel.isBlank() && !"N/A".equalsIgnoreCase(channel)) {
                    noRecordingChannels.add(channel);
                }
            }

            StringBuilder builder = new StringBuilder("**For Branch ")
                    .append(branchName(branch))
                    .append(", CCTV Recording Information is: ");
            builder.append(withRecording).append(" channel(s) have recording data");
            if (!noRecordingChannels.isEmpty()) {
                builder.append("; no recording data for channel(s) ")
                        .append(String.join(", ", noRecordingChannels));
            }
            builder.append(".**");
            return builder.toString();
        } catch (Exception ignored) {
            return "**For Branch " + branchName(branch)
                    + ", CCTV Recording Information is not available.**";
        }
    }

    private String answerDoorStatus(BranchSnapshot branch, String targetSystem) {
        if ("timeLock".equals(targetSystem)) {
            String door = firstNonBlank(branch.getRawData(), "timeLockDoor");
            if (door != null) {
                return "**For Branch " + branchName(branch)
                        + ", Time Lock Door Status is " + door.toUpperCase() + ".**";
            }
            return "**For Branch " + branchName(branch)
                    + ", Time Lock Door Status is not available.**";
        }
        if ("accessControl".equals(targetSystem)) {
            String door = firstNonBlank(branch.getRawData(), "accessControlDoor");
            if (door != null) {
                return "**For Branch " + branchName(branch)
                        + ", Access Control Door Status is " + door.toUpperCase() + ".**";
            }
            return "**For Branch " + branchName(branch)
                    + ", Access Control Door Status is not available.**";
        }
        return "**For Branch " + branchName(branch) + ", Door status is not available.**";
    }

    private String answerAccessControlUserCount(BranchSnapshot branch) {
        Integer userCount = firstInteger(branch.getRawData(),
                "accessControlTotalUsers", "totalUsers", "registeredUsers", "accessControlUserCount", "userCount");
        if (userCount != null) {
            return "**For Branch " + branchName(branch)
                    + ", Access Control User Count is " + userCount + ".**";
        }

        return "**For Branch " + branchName(branch)
                + ", access control user count is not available in current branch data. Current status is "
                + formatState(branch.getSubsystems().getAccessControl().getState()) + ".**";
    }

    private String answerAccessControlDeviceInfo(BranchSnapshot branch) {
        String model = firstNonBlank(branch.getRawData(), "accessControlModel", "biometricModel", "acsModel");
        String firmware = firstNonBlank(branch.getRawData(), "accessControlFirmware", "biometricFirmware",
                "accessControlFirmwareVersion");
        String ip = firstNonBlank(branch.getRawData(), "accessControlIp", "biometricIp", "accessControlIP");
        String door = firstNonBlank(branch.getRawData(), "accessControlDoor");
        String status = formatState(branch.getSubsystems().getAccessControl().getState());

        if (model == null && firmware == null && ip == null) {
            String suffix = door != null ? " Door: " + door.toUpperCase() + "." : "";
            return "**For Branch " + branchName(branch)
                    + ", access control device information is not available in current branch data. Status is "
                    + status + "." + suffix + "**";
        }

        List<String> parts = new ArrayList<>();
        parts.add("Status: " + status);
        if (model != null) {
            parts.add("Model: " + model);
        }
        if (firmware != null) {
            parts.add("Firmware: " + firmware);
        }
        if (ip != null) {
            parts.add("IP: " + ip);
        }
        if (door != null) {
            parts.add("Door: " + door.toUpperCase());
        }
        return "**For Branch " + branchName(branch)
                + ", Access Control Device Info is: " + String.join(", ", parts) + ".**";
    }

    private List<String> systemsByState(BranchSnapshot branch, NormalizedState targetState) {
        List<String> systems = new ArrayList<>();
        subsystemMap(branch).forEach((label, status) -> {
            if (status != null && status.getState() == targetState) {
                systems.add(label);
            }
        });
        return systems;
    }

    private List<String> installedSystems(BranchSnapshot branch) {
        List<String> systems = new ArrayList<>();
        subsystemMap(branch).forEach((label, status) -> {
            if (status != null && status.isInstalled()) {
                systems.add(label);
            }
        });
        return systems;
    }

    private Map<String, SubsystemStatus> subsystemMap(BranchSnapshot branch) {
        Map<String, SubsystemStatus> systems = new LinkedHashMap<>();
        systems.put("CCTV DVR", branch.getSubsystems().getCctv());
        systems.put("IAS Panel", branch.getSubsystems().getIas());
        systems.put("BAS Panel", branch.getSubsystems().getBas());
        systems.put("FAS Panel", branch.getSubsystems().getFas());
        systems.put("Time Lock", branch.getSubsystems().getTimeLock());
        systems.put("Access Control Controller", branch.getSubsystems().getAccessControl());
        return systems;
    }

    private List<String> branchLevelFaultIndicators(BranchSnapshot branch) {
        Map<String, Object> raw = branch.getRawData();
        List<String> indicators = new ArrayList<>();
        if (isTrue(raw.get("ticketStatus_FAS_FAULT")) || isTrue(raw.get("fireAlarmSystem_fault"))
                || isTrue(raw.get("fire_alarm_system_fault"))) {
            indicators.add("Fire Alarm Fault");
        }
        if (isTrue(raw.get("ticketStatus_IAS_FAULT")) || isTrue(raw.get("intrusion_alarm_system_fault"))
                || isTrue(raw.get("INTRUSION ALARM SYSTEM FAULT"))) {
            indicators.add("Intrusion Alarm Fault");
        }
        if (isTrue(raw.get("DVR/NVR OFF")) || isTrue(raw.get("ticketStatus_NVR_OFF"))) {
            indicators.add("NVR/DVR Off");
        }
        if (isTrue(raw.get("HDD ERROR")) || isTrue(raw.get("ticketStatus_HDD_ERROR"))) {
            indicators.add("HDD Error");
        }
        return indicators;
    }

    private Boolean resolveBoolean(Map<String, Object> raw, String... keys) {
        for (String key : keys) {
            Object value = raw.get(key);
            if (value == null) {
                continue;
            }
            String text = String.valueOf(value).trim();
            if (text.isBlank() || "N/A".equalsIgnoreCase(text) || "null".equalsIgnoreCase(text)) {
                continue;
            }
            if ("true".equalsIgnoreCase(text) || "1".equals(text) || "on".equalsIgnoreCase(text)
                    || "healthy".equalsIgnoreCase(text) || "online".equalsIgnoreCase(text)) {
                return true;
            }
            if ("false".equalsIgnoreCase(text) || "0".equals(text) || "off".equalsIgnoreCase(text)
                    || "offline".equalsIgnoreCase(text) || "inactive".equalsIgnoreCase(text)) {
                return false;
            }
        }
        return null;
    }

    private String firstNonBlank(Map<String, Object> raw, String... keys) {
        for (String key : keys) {
            Object value = raw.get(key);
            if (value == null) {
                continue;
            }
            String text = String.valueOf(value).trim();
            if (!text.isBlank() && !"null".equalsIgnoreCase(text) && !"N/A".equalsIgnoreCase(text)) {
                return text;
            }
        }
        return null;
    }

    private Integer firstInteger(Map<String, Object> raw, String... keys) {
        for (String key : keys) {
            Integer value = toInt(raw.get(key));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private Integer toInt(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private boolean containsAny(String text, String... fragments) {
        if (text == null) {
            return false;
        }
        String normalized = text.toLowerCase();
        for (String fragment : fragments) {
            if (normalized.contains(fragment.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private String formatState(NormalizedState state) {
        return switch (state) {
            case ONLINE -> "ONLINE";
            case OFFLINE -> "OFFLINE";
            case FAULT -> "FAULT";
            case NOT_INSTALLED -> "NOT INSTALLED";
            case UNKNOWN -> "UNKNOWN";
        };
    }

    private String branchName(BranchSnapshot branch) {
        if (branch == null || branch.getIdentity() == null || branch.getIdentity().getBranchName() == null) {
            return "Unknown";
        }
        String display = branch.getIdentity().getBranchName()
                .replaceFirst("(?i)^BRANCH\\s+", "")
                .trim();
        String technicalId = branch.getIdentity().getTechnicalId();
        if ("TR".equalsIgnoreCase(display) && technicalId != null
                && technicalId.toUpperCase().contains("TRENDZ")) {
            return "TRENDZ";
        }
        return display;
    }
}
