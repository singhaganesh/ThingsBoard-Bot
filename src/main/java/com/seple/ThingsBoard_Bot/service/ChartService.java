package com.seple.ThingsBoard_Bot.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.seple.ThingsBoard_Bot.client.ThingsBoardClient;
import com.seple.ThingsBoard_Bot.client.UserAwareThingsBoardClient;
import com.seple.ThingsBoard_Bot.model.dto.ChartData;

import lombok.extern.slf4j.Slf4j;

/**
 * Chart generation service.
 * Fetches 24-hour historical data from ThingsBoard and formats it for Chart.js.
 */
@Slf4j
@Service
public class ChartService {

    private final ThingsBoardClient tbClient;
    private final UserAwareThingsBoardClient userTbClient;

    private static final long TWENTY_FOUR_HOURS_MS = 24 * 60 * 60 * 1000L;

    public ChartService(ThingsBoardClient tbClient, UserAwareThingsBoardClient userTbClient) {
        this.tbClient = tbClient;
        this.userTbClient = userTbClient;
    }

    /**
     * Generate chart data for a given telemetry key (last 24 hours).
     *
     * @param userToken Optional user token for scoped access.
     * @param deviceId  Optional device ID (required if userToken is present).
     * @param key       The telemetry key (e.g., "battery_status", "temperature")
     * @return ChartData formatted for Chart.js
     */
    public ChartData generateChartData(String userToken, String deviceId, String key) {
        log.info("📊 Generating chart data for key: '{}'", key);

        long endTs = System.currentTimeMillis();
        long startTs = endTs - TWENTY_FOUR_HOURS_MS;

        try {
            Map<String, List<Map<String, Object>>> history;
            if (userToken != null && !userToken.isBlank() && deviceId != null && !deviceId.isBlank()) {
                history = userTbClient.getHistory(userToken, deviceId, key, startTs, endTs);
            } else {
                history = tbClient.getHistory(key, startTs, endTs);
            }

            List<ChartData.DataPoint> points = new ArrayList<>();

            if (history.containsKey(key)) {
                List<Map<String, Object>> rawPoints = history.get(key);

                for (Map<String, Object> rawPoint : rawPoints) {
                    long ts = rawPoint.get("ts") instanceof Long
                            ? (Long) rawPoint.get("ts")
                            : Long.parseLong(rawPoint.get("ts").toString());
                    String value = rawPoint.get("value").toString();

                    points.add(ChartData.DataPoint.builder()
                            .t(ts)
                            .y(value)
                            .build());
                }

                // Sort by timestamp ascending
                points.sort((a, b) -> Long.compare(a.getT(), b.getT()));

                log.info("✅ Chart generated with {} data points for '{}'", points.size(), key);
            } else {
                log.warn("⚠️ No history data found for key: '{}'", key);
            }

            return ChartData.builder()
                    .label(key)
                    .points(points)
                    .build();

        } catch (Exception e) {
            log.error("❌ Error generating chart for '{}': {}", key, e.getMessage());
            return ChartData.builder()
                    .label(key)
                    .points(new ArrayList<>())
                    .build();
        }
    }
}
