package com.seple.ThingsBoard_Bot.service.index;

import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.seple.ThingsBoard_Bot.client.UserAwareThingsBoardClient;
import com.seple.ThingsBoard_Bot.config.ThingsBoardConfig;
import com.seple.ThingsBoard_Bot.model.dto.GlobalOverviewCounters;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class GlobalAggregatorService {

    private final UserAwareThingsBoardClient userAwareThingsBoardClient;
    private final ThingsBoardConfig thingsBoardConfig;

    public GlobalAggregatorService(UserAwareThingsBoardClient userAwareThingsBoardClient,
            ThingsBoardConfig thingsBoardConfig) {
        this.userAwareThingsBoardClient = userAwareThingsBoardClient;
        this.thingsBoardConfig = thingsBoardConfig;
    }

    public boolean isEnabled() {
        return thingsBoardConfig.isAggregatorEnabled()
                && thingsBoardConfig.getAggregatorDeviceId() != null
                && !thingsBoardConfig.getAggregatorDeviceId().isBlank();
    }

    public GlobalOverviewCounters fetchGlobalOverview(String userToken) {
        if (!isEnabled()) {
            return null;
        }

        String deviceId = thingsBoardConfig.getAggregatorDeviceId();
        try {
            Map<String, Object> merged = new HashMap<>();
            merged.putAll(userAwareThingsBoardClient.getAttributes(userToken, "CLIENT_SCOPE", deviceId));
            merged.putAll(userAwareThingsBoardClient.getAttributes(userToken, "SERVER_SCOPE", deviceId));
            merged.putAll(userAwareThingsBoardClient.getAttributes(userToken, "SHARED_SCOPE", deviceId));
            merged.putAll(userAwareThingsBoardClient.getTelemetry(userToken, deviceId));

            Integer online = firstInt(merged,
                    "total_online", "totalOnline", "online_count", "active_branches", "branches_online");
            Integer offline = firstInt(merged,
                    "total_offline", "totalOffline", "offline_count", "inactive_branches", "branches_offline");

            if (online == null && offline == null) {
                return null;
            }

            return GlobalOverviewCounters.builder()
                    .onlineBranches(online != null ? online : 0)
                    .offlineBranches(offline != null ? offline : 0)
                    .sourceDeviceId(deviceId)
                    .fetchedAt(System.currentTimeMillis())
                    .build();
        } catch (Exception e) {
            log.error("Failed to fetch global aggregator counters: {}", e.getMessage());
            return null;
        }
    }

    private Integer firstInt(Map<String, Object> raw, String... keys) {
        for (String key : keys) {
            Object value = raw.get(key);
            if (value == null) {
                continue;
            }
            try {
                return Integer.parseInt(String.valueOf(value).trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }
}
