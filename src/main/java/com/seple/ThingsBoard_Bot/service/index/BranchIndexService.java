package com.seple.ThingsBoard_Bot.service.index;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.seple.ThingsBoard_Bot.client.UserAwareThingsBoardClient;
import com.seple.ThingsBoard_Bot.config.ThingsBoardConfig;
import com.seple.ThingsBoard_Bot.model.dto.DeviceIndexEntry;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class BranchIndexService {

    private final UserAwareThingsBoardClient userAwareThingsBoardClient;
    private final ThingsBoardConfig thingsBoardConfig;
    private final ConcurrentHashMap<String, List<DeviceIndexEntry>> indexByUser = new ConcurrentHashMap<>();

    public BranchIndexService(UserAwareThingsBoardClient userAwareThingsBoardClient, ThingsBoardConfig thingsBoardConfig) {
        this.userAwareThingsBoardClient = userAwareThingsBoardClient;
        this.thingsBoardConfig = thingsBoardConfig;
    }

    public List<DeviceIndexEntry> getIndex(String userToken) {
        String key = cacheKey(userToken);
        List<DeviceIndexEntry> existing = indexByUser.get(key);
        if (existing != null && !existing.isEmpty()) {
            return new ArrayList<>(existing);
        }
        List<DeviceIndexEntry> refreshed = refreshIndex(userToken);
        return new ArrayList<>(refreshed);
    }

    public List<DeviceIndexEntry> refreshIndex(String userToken) {
        int pageSize = thingsBoardConfig.getDevicePageSize() > 0 ? thingsBoardConfig.getDevicePageSize() : 100;
        List<DeviceIndexEntry> entries = userAwareThingsBoardClient.getUserDevicesPaged(userToken, pageSize).stream()
                .map(device -> {
                    String name = device.getOrDefault("name", "");
                    return DeviceIndexEntry.builder()
                            .deviceId(device.get("id"))
                            .branchName(name)
                            .deviceType(device.get("type"))
                            .aliases(aliases(name))
                            .indexedAt(System.currentTimeMillis())
                            .build();
                })
                .toList();

        indexByUser.put(cacheKey(userToken), new ArrayList<>(entries));
        log.info("Indexed {} devices for user cache {}", entries.size(), cacheKey(userToken));
        return entries;
    }

    public void invalidate(String userToken) {
        indexByUser.remove(cacheKey(userToken));
    }

    @Scheduled(fixedDelayString = "${iotchatbot.thingsboard.sync-interval-seconds:60}000")
    public void periodicCleanup() {
        // Phase-1 minimal safety: keep memory bounded if inactive sessions accumulate.
        if (indexByUser.size() > 5000) {
            log.warn("Branch index cache is large ({}). Clearing inactive cache entries.", indexByUser.size());
            indexByUser.clear();
        }
    }

    private List<String> aliases(String branchName) {
        if (branchName == null || branchName.isBlank()) {
            return List.of();
        }
        String normalized = branchName.toUpperCase(Locale.ROOT)
                .replace("BRANCH ", "")
                .replace("BOI-", "")
                .replace('-', ' ')
                .replace('_', ' ')
                .replaceAll("\\s+", " ")
                .trim();
        String compact = normalized.replace(" ", "");
        return new ArrayList<>(Set.of(branchName, normalized, compact));
    }

    private String cacheKey(String userToken) {
        return String.valueOf(userToken.hashCode());
    }
}
