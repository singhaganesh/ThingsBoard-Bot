package com.seple.ThingsBoard_Bot.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.seple.ThingsBoard_Bot.client.ThingsBoardClient;
import com.seple.ThingsBoard_Bot.client.UserAwareThingsBoardClient;
import com.seple.ThingsBoard_Bot.model.dto.AlertData;

import lombok.extern.slf4j.Slf4j;

/**
 * Alert monitoring service — SEPARATE from the Q&A cache!
 * <p>
 * Polls ThingsBoard every 10 seconds for critical alerts.
 * Bypasses the 1-minute DataService cache to get LIVE data for alerts.
 * </p>
 */
@Slf4j
@Service
public class AlertService {

    private final ThingsBoardClient tbClient;
    private final UserAwareThingsBoardClient userTbClient;
    private final UserDataService userDataService;

    // Last known alert state
    private volatile AlertData lastAlertData;

    // Alert thresholds
    private static final int BATTERY_LOW_THRESHOLD = 20;   // %
    private static final int CPU_CRITICAL_THRESHOLD = 90;   // %
    private static final int MEMORY_CRITICAL_THRESHOLD = 95; // %

    public AlertService(ThingsBoardClient tbClient, UserAwareThingsBoardClient userTbClient, UserDataService userDataService) {
        this.tbClient = tbClient;
        this.userTbClient = userTbClient;
        this.userDataService = userDataService;
        this.lastAlertData = AlertData.builder()
                .hasAlert(false)
                .alerts(new ArrayList<>())
                .timestamp(System.currentTimeMillis())
                .build();
    }

    /**
     * Periodic alert check — runs every 10 seconds.
     * Bypasses the DataService cache to get LIVE data.
     * Note: This remains global/tenant-level for the background worker.
     */
    @Scheduled(fixedDelayString = "${iotchatbot.chatbot.alert-poll-interval:10000}")
    @ConditionalOnProperty(name = "iotchatbot.chatbot.enable-alerts", havingValue = "true", matchIfMissing = true)
    public void periodicAlertCheck() {
        try {
            // Direct fetch from TB — bypasses the 1-minute cache!
            Map<String, Object> liveData = tbClient.getTelemetry();
            List<String> alerts = evaluateAlerts(liveData);

            this.lastAlertData = AlertData.builder()
                    .hasAlert(!alerts.isEmpty())
                    .alerts(alerts)
                    .timestamp(System.currentTimeMillis())
                    .build();

            if (!alerts.isEmpty()) {
                log.warn("🚨 Active alerts ({}): {}", alerts.size(), alerts);
            } else {
                log.debug("✅ No active alerts");
            }

        } catch (Exception e) {
            log.error("❌ Error during periodic alert check: {}", e.getMessage());
        }
    }

    /**
     * On-demand alert check — also bypasses cache.
     * If userToken is provided, it checks the user's specific devices.
     */
    public AlertData checkAlerts(String userToken) {
        if (userToken == null || userToken.isBlank()) {
            // Fallback to global/tenant check
            try {
                Map<String, Object> liveData = tbClient.getTelemetry();
                List<String> alerts = evaluateAlerts(liveData);

                return AlertData.builder()
                        .hasAlert(!alerts.isEmpty())
                        .alerts(alerts)
                        .timestamp(System.currentTimeMillis())
                        .build();
            } catch (Exception e) {
                log.error("❌ Error checking global alerts: {}", e.getMessage());
                return lastAlertData;
            }
        } else {
            // User-scoped check
            try {
                List<String> allAlerts = new ArrayList<>();
                List<Map<String, String>> devices = userDataService.getUserDevicesList(userToken);
                
                for (Map<String, String> device : devices) {
                    String deviceId = device.get("id");
                    String deviceName = device.get("name");
                    
                    if (deviceId == null || deviceId.isBlank() || "null".equals(deviceId)) {
                        continue;
                    }
                    
                    Map<String, Object> liveData = userTbClient.getTelemetry(userToken, deviceId);
                    List<String> deviceAlerts = evaluateAlerts(liveData);
                    
                    for(String alert : deviceAlerts) {
                        allAlerts.add("[" + deviceName + "] " + alert);
                    }
                }
                
                return AlertData.builder()
                        .hasAlert(!allAlerts.isEmpty())
                        .alerts(allAlerts)
                        .timestamp(System.currentTimeMillis())
                        .build();
            } catch (Exception e) {
                log.error("❌ Error checking alerts for user: {}", e.getMessage());
                // Return an empty/safe state rather than exposing global alerts to a restricted user
                return AlertData.builder()
                        .hasAlert(false)
                        .alerts(new ArrayList<>())
                        .timestamp(System.currentTimeMillis())
                        .build();
            }
        }
    }

    /**
     * Get the last known alert state (from periodic checks).
     */
    public AlertData getLastAlertData() {
        return lastAlertData;
    }

    /**
     * Evaluate live data and generate alert messages.
     */
    private List<String> evaluateAlerts(Map<String, Object> data) {
        List<String> alerts = new ArrayList<>();

        // Check battery
        Double battery = parseDouble(data.get("battery_status"));
        if (battery == null) battery = parseDouble(data.get("battery_level"));
        if (battery == null) battery = parseDouble(data.get("battery"));
        if (battery != null && battery < BATTERY_LOW_THRESHOLD) {
            alerts.add("🔋 CRITICAL: Battery low at " + battery.intValue() + "%!");
        }

        // Check alarm count
        Integer alarmCount = parseInt(data.get("alarm_count"));
        if (alarmCount == null) alarmCount = parseInt(data.get("alarmCount"));
        if (alarmCount != null && alarmCount > 0) {
            alerts.add("🚨 " + alarmCount + " active alarm(s) detected!");
        }

        // Check system status
        String status = data.get("system_status") != null
                ? data.get("system_status").toString()
                : (data.get("active") != null ? data.get("active").toString() : null);
        if (status != null && !status.equalsIgnoreCase("online")
                && !status.equalsIgnoreCase("true")
                && !status.equalsIgnoreCase("active")) {
            alerts.add("❌ Device status: " + status);
        }

        // Check CPU
        Double cpu = parseDouble(data.get("cpu_usage"));
        if (cpu == null) cpu = parseDouble(data.get("cpuUsage"));
        if (cpu != null && cpu > CPU_CRITICAL_THRESHOLD) {
            alerts.add("🔥 CPU usage critical at " + cpu.intValue() + "%!");
        }

        // Check memory
        Double memory = parseDouble(data.get("memory_usage"));
        if (memory == null) memory = parseDouble(data.get("memoryUsage"));
        if (memory != null && memory > MEMORY_CRITICAL_THRESHOLD) {
            alerts.add("💾 Memory usage critical at " + memory.intValue() + "%!");
        }

        return alerts;
    }

    private Double parseDouble(Object value) {
        if (value == null) return null;
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer parseInt(Object value) {
        if (value == null) return null;
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
