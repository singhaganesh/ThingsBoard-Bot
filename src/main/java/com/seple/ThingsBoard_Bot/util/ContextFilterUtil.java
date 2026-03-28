package com.seple.ThingsBoard_Bot.util;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import lombok.extern.slf4j.Slf4j;

/**
 * Filters device attributes to reduce token count before sending to OpenAI.
 * <p>
 * Raw device data can have ~200+ keys and ~17,000 tokens.
 * After filtering, we keep ~20-30 relevant keys and ~3,000-4,000 tokens.
 * </p>
 */
@Slf4j
public class ContextFilterUtil {

    // Maximum allowed length for a single attribute value
    private static final int MAX_VALUE_LENGTH = 300;

    // Keys to ALWAYS keep (most useful for chatbot Q&A)
    private static final Set<String> IMPORTANT_KEYS = Set.of(
            // Device identity
            "deviceName", "branchName", "customer_title", "zoName", "nbgName", "branch_id",
            "deviceType", "gateway",

            // System status
            "active", "status", "gwHealth", "gwStatus", "system_status",
            "SYSTEM ON", "MAINS ON", "BATTERY ON", "BATTERY LOW", "BATTERY REVERSE",
            "NETWORK", "POWER OFF",

            // Battery & power
            "battery_status", "ac_status", "current_status",

            // Subsystem health/status
            "iasHealth", "iasStatus", "iasSystem", "ias", "iasUptime",
            "cctvStatus", "cctvUptime", "cctv", "hddStatus", "nvrStatus",
            "fasHealth", "fasStatus", "fas", "fasSystem",
            "integratedStatus", "integratedType",
            "basHealth", "basStatus", "bas", "basSystem",
            "timeLockHealth", "timeLock",
            "accessControlHealth", "accessControl", "accessControlStatus",
            "cameraLinkStatus", "gatewayStatus", "cameraStatus", "hikvision_camera_status", "dahua_camera_status",

            // Alarms
            "alarmCount", "severity", "alerts", "errorCount",
            "CAMERA DISCONNECT", "CAMERA TAMPER", "HDD ERROR",
            "INTEGRATED ALARM SYSTEM FAULT", "DVR/NVR OFF",

            // Location
            "lat", "lon", "lat1", "lon1",

            // Hardware info
            "cpu", "memory", "disk", "temperature", "frequency",
            "net_sent_mb", "net_recv_mb",
            "Hikvision_NVR_model", "Hikvision_NVR_deviceName", "Hikvision_NVR_Heartbeat",
            "DahuaNVR_Heartbeat",

            // Timestamps
            "timestamp", "time", "date",

            // Uptime
            "uptimeTotal", "uptimeHeartbeat", "gatewayUptime",

            // Connectivity & Activity
            "lastActivityTime", "lastConnectTime", "lastDisconnectTime",
            "lastHeartbeat", "lastTelemetryTime", "lastUpdated",

            // Counts
            "iasLastHour", "cctvLastHour", "fasLastHour", "gatewayLastHour",
            "IASinactiveCOUNT", "IASfaultCOUNT",

            // ChatBot Internal Flags
            "SYSTEM_NOTE", "available_devices_for_user_to_choose_from"
    );

    // Suffixes of keys to always skip
    private static final Set<String> SKIP_SUFFIXES = Set.of(
            "_history"
    );

    // Prefixes of keys to always skip
    private static final Set<String> SKIP_PREFIXES = Set.of(
            "fw_", "sw_", "$", "provision", "target",
            "cameraTamperCH", "cameraDisconnectCH",
            "arrLat", "arrLon", "TotalLat", "TotalLon"
    );

    // Exact keys to always skip
    private static final Set<String> SKIP_KEYS = Set.of(
            "fetched_at", "id", "undefined", "notification", "care",
            "log_type", "alarmFlag", "attribute",
            "rock", "rockAI", "dexter_config",
            "rpi_usage", "rpi_alert", "usage_history",
            "Total_Data_Usage", "usage_daily", "usage_last_7_days", "usage_last_15_days",
            "watchdog_log", "lowDurationCameras",
            "imei_id", "cavlidata_ontime",
            "hikvision_ntp", "HiksyncTimeDate",
            "Hikvision_NVR_serialNumber", "Hikvision_NVR_macAddress",
            "Hikvision_NVR_firmwareVersion", "Hikvision_NVR_hardwareVersion",
            "Hikvision_NVR_Processor", "Hikvision_NVR_deviceID",
            "Hikvision_NVR_HDDInfo", "Hikvision_NVR_CameraRecInfo",
            "Hikvision_NVR_cameraInfo", "Hikvision_NVR_Date", "Hikvision_NVR_Time",
            "Hikvision_NVR_Manufacturer", "Hikvision_NVR_deviceType",
            "Hik_SD_card_info", "Hik_SD_card_rec_info_list",
            "integrated_alarm_fault_last", "integrated_alarm_off_last",
            "integrated_alarm_activate_last", "Nvr_DVR_last", "dvr_nvr_off",
            "gatewayType", "nvrType", "type"
    );

    /**
     * Filter raw device attributes to keep only relevant data for the chatbot.
     */
    public static Map<String, Object> filterAttributes(Map<String, Object> rawData) {
        if (rawData == null || rawData.isEmpty()) {
            return new HashMap<>();
        }

        int originalSize = rawData.size();
        Map<String, Object> filtered = new HashMap<>();

        for (Map.Entry<String, Object> entry : rawData.entrySet()) {
            String fullKey = entry.getKey();
            Object value = entry.getValue();

            // Extract the actual key if it's prefixed with deviceName.
            String actualKey = fullKey;
            String prefix = "";
            if (fullKey.contains(".")) {
                prefix = fullKey.substring(0, fullKey.lastIndexOf(".") + 1);
                actualKey = fullKey.substring(fullKey.lastIndexOf(".") + 1);
            }

            // --- NORMALIZATION: Map technical keys to the user's 6 Sub-System keys ---
            if ("cctvStatus".equals(actualKey)) actualKey = "cctv";
            else if ("iasStatus".equals(actualKey)) actualKey = "ias";
            else if ("basStatus".equals(actualKey)) actualKey = "bas";
            else if ("fireAlarmStatus".equals(actualKey) || "fasStatus".equals(actualKey)) actualKey = "fas";
            else if ("timeLockHealth".equals(actualKey)) actualKey = "timeLock";
            else if ("accessControlStatus".equals(actualKey)) actualKey = "accessControl";
            
            String finalKey = prefix + actualKey;

            // Skip if explicitly excluded
            if (shouldSkip(actualKey, fullKey)) {
                continue;
            }

            // Keep if explicitly important
            if (IMPORTANT_KEYS.contains(actualKey) || IMPORTANT_KEYS.contains(fullKey)) {
                Object finalValue = value;
                String valueStr = String.valueOf(value);
                
                // --- VALUE NORMALIZATION: Standardize "Healthy/On" to "Online" ---
                if ("Healthy".equalsIgnoreCase(valueStr) || "On".equalsIgnoreCase(valueStr)) {
                    finalValue = "Online";
                } else if ("Fault".equalsIgnoreCase(valueStr)) {
                    finalValue = "Offline";
                }

                if (isTooLarge(valueStr) && !actualKey.equals("SYSTEM_NOTE") && !actualKey.equals("available_devices_for_user_to_choose_from")) {
                    filtered.put(finalKey, simplifyValue(String.valueOf(finalValue)));
                } else {
                    filtered.put(finalKey, finalValue);
                }
                continue;
            }

            // For unknown keys, only keep if value is small and useful
            String valueStr = String.valueOf(value);
            if (valueStr.length() < 100 && !valueStr.equals("[]") && !valueStr.equals("{}")) {
                filtered.put(fullKey, value);
            }
        }

        log.info("Context filtered: {} keys → {} keys (removed {} keys)",
                originalSize, filtered.size(), originalSize - filtered.size());

        return filtered;
    }

    /**
     * Check if a key should be skipped entirely.
     */
    private static boolean shouldSkip(String actualKey, String fullKey) {
        if (SKIP_KEYS.contains(actualKey) || SKIP_KEYS.contains(fullKey)) {
            return true;
        }

        for (String suffix : SKIP_SUFFIXES) {
            if (actualKey.endsWith(suffix) || fullKey.endsWith(suffix)) {
                return true;
            }
        }

        for (String prefix : SKIP_PREFIXES) {
            if (actualKey.startsWith(prefix) || fullKey.startsWith(prefix)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Check if a value string exceeds the maximum length.
     */
    private static boolean isTooLarge(String value) {
        return value != null && value.length() > MAX_VALUE_LENGTH;
    }

    /**
     * Simplify a large value by truncating or summarizing.
     */
    private static String simplifyValue(String value) {
        if (value == null) return null;

        String trimmed = value.trim();

        // For JSON objects, try to keep the first part
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return trimmed.substring(0, Math.min(250, trimmed.length())) + "...[truncated]";
        }

        return value.substring(0, Math.min(200, value.length())) + "...[truncated]";
    }
}
