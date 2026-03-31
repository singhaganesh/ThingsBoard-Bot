package com.seple.ThingsBoard_Bot.util;

import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;

/**
 * Advanced Context Filter - Synchronized with Branch System Q&A Guide.
 */
@Slf4j
public class ContextFilterUtil {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final Set<String> IDENTITY_FIELDS = Set.of(
            "deviceName", "branchName", "branch_id", "deviceType", "gateway", "active", "status", "formattedBranchName"
    );

    private static final Set<String> SUB_SYSTEM_FIELDS = Set.of(
            "cctv", "ias", "bas", "fas", "timeLock", "accessControl",
            "cctvStatus", "iasStatus", "basStatus", "fireAlarmStatus", "fasStatus",
            "timeLockHealth", "accessControlStatus", "cameraLinkStatus", "tlStatus", "ias_status"
    );

    private static final Set<String> HARDWARE_FIELDS = Set.of(
            "cpu", "memory", "disk", "temperature", "battery_status_battery_voltage", 
            "ac_status_ac_voltage", "current_status_system_current", "gatewayStatus_battery_voltage",
            "battery_voltage", "ac_voltage"
    );

    private static final Set<String> ALARM_FIELDS = Set.of(
            "alarmCount", "errorCount", "HDD ERROR", "INTRUSION ALARM SYSTEM ACTIVATE",
            "FIRE ALARM SYSTEM ACTIVATE", "BATTERY LOW", "POWER OFF", "ticketStatus_NVR_OFF",
            "TIME LOCK SYSTEM OFF", "DVR/NVR OFF"
    );

    private static final Set<String> COMPLEX_FIELDS = Set.of(
            "rock_CAMERAdETAILS", "rock_HddINFO", "battery_status", "ac_status", "Dahua_SD_card_info", "rock_VIDEOdETAILS"
    );

    public static Map<String, Object> filterAttributes(Map<String, Object> rawData) {
        if (rawData == null || rawData.isEmpty()) return new HashMap<>();
        Map<String, Object> filtered = new HashMap<>();

        for (Map.Entry<String, Object> entry : rawData.entrySet()) {
            String fullKey = entry.getKey();
            Object value = entry.getValue();

            String actualKey = fullKey;
            String prefix = "";
            if (fullKey.contains(".")) {
                prefix = fullKey.substring(0, fullKey.lastIndexOf(".") + 1);
                actualKey = fullKey.substring(fullKey.lastIndexOf(".") + 1);
            }

            if (COMPLEX_FIELDS.contains(actualKey)) {
                processComplexField(prefix, actualKey, String.valueOf(value), filtered);
                continue;
            }

            String normalizedKey = normalizeKey(actualKey);
            String finalKey = prefix + normalizedKey;

            if (IDENTITY_FIELDS.contains(normalizedKey) || SUB_SYSTEM_FIELDS.contains(normalizedKey) || 
                HARDWARE_FIELDS.contains(normalizedKey) || ALARM_FIELDS.contains(normalizedKey) || actualKey.equals("SYSTEM_NOTE")) {
                
                Object finalVal = standardizeValue(normalizedKey, value);
                filtered.put(finalKey, finalVal);
            }
        }
        return filtered;
    }

    private static void processComplexField(String prefix, String key, String jsonStr, Map<String, Object> filtered) {
        try {
            if (jsonStr == null || jsonStr.equals("N/A") || jsonStr.isEmpty()) return;
            JsonNode node = objectMapper.readTree(jsonStr);
            
            if ((key.equals("rock_CAMERAdETAILS") || key.equals("cp_plus_camera_status")) && node.isArray()) {
                int total = node.size();
                int active = 0;
                for (JsonNode cam : node) {
                    String status = cam.has("Active Status") ? cam.get("Active Status").asText() : cam.get("camera_status").asText();
                    if ("Active".equalsIgnoreCase(status)) active++;
                }
                filtered.put(prefix + "cctv_camera_count", total);
                filtered.put(prefix + "cctv_online_count", active);
            } else if (key.equals("rock_HddINFO") && node.isArray()) {
                JsonNode hdd = node.get(0); // Primary slot
                if (hdd != null) {
                    filtered.put(prefix + "hdd_status", hdd.get("HDDStatus").asText());
                    filtered.put(prefix + "hdd_capacity", hdd.get("HDDcapacity").asText() + "TB");
                    filtered.put(prefix + "hdd_free", hdd.get("HDDfreeSpace").asText() + "GB");
                }
            } else if (key.equals("battery_status") || key.equals("ac_status")) {
                Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    filtered.put(prefix + field.getKey(), field.getValue().asText());
                }
            }
        } catch (Exception e) {
            log.warn("Parsing error for {}: {}", key, e.getMessage());
        }
    }

    private static Object standardizeValue(String key, Object value) {
        String valStr = String.valueOf(value);
        if (valStr.equalsIgnoreCase("true") || valStr.equalsIgnoreCase("On") || valStr.equalsIgnoreCase("Healthy")) return "Online";
        if (valStr.equalsIgnoreCase("false") || valStr.equalsIgnoreCase("Off") || valStr.equalsIgnoreCase("Fault") || valStr.equalsIgnoreCase("Inactive")) return "Offline";
        return value;
    }

    private static String normalizeKey(String key) {
        if (key.equals("cctvStatus") || key.equals("cameraLinkStatus")) return "cctv";
        if (key.equals("iasStatus") || key.equals("ias_status")) return "ias";
        if (key.equals("fasStatus") || key.equals("fireAlarmStatus")) return "fas";
        if (key.equals("timeLockHealth") || key.equals("tlStatus")) return "timeLock";
        if (key.equals("accessControlStatus")) return "accessControl";
        if (key.equals("battery_status_battery_voltage") || key.equals("gatewayStatus_battery_voltage")) return "battery_voltage";
        if (key.equals("ac_status_ac_voltage")) return "ac_voltage";
        if (key.equals("current_status_system_current")) return "system_current";
        return key;
    }
}
