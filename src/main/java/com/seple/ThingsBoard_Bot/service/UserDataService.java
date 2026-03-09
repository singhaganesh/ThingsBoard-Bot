package com.seple.ThingsBoard_Bot.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import com.seple.ThingsBoard_Bot.client.UserAwareThingsBoardClient;

import lombok.extern.slf4j.Slf4j;

/**
 * Per-user data caching service.
 * <p>
 * Each user (identified by their JWT token / user ID) has their own
 * separate 1-minute cache. This ensures that User A never sees
 * User B's device data.
 * </p>
 */
@Slf4j
@Service
public class UserDataService {

    private final UserAwareThingsBoardClient userTbClient;

    // Per-user cache: key = userToken hash, value = cached data + timestamp
    private final ConcurrentHashMap<String, CachedUserData> userCacheMap = new ConcurrentHashMap<>();

    // 1-MINUTE TTL
    private static final long CACHE_TTL_MS = 60 * 1000;

    public UserDataService(UserAwareThingsBoardClient userTbClient) {
        this.userTbClient = userTbClient;
    }

    // ==================== Cache Entry ====================

    private static class CachedUserData {
        final List<Map<String, Object>> devicesData;
        final long timestamp;

        CachedUserData(List<Map<String, Object>> devicesData, long timestamp) {
            this.devicesData = devicesData;
            this.timestamp = timestamp;
        }

        boolean isExpired() {
            return (System.currentTimeMillis() - timestamp) > CACHE_TTL_MS;
        }
    }

    // ==================== Public API ====================

    /**
     * Get all device data for the logged-in user.
     * Returns cached data if still valid (< 1 minute old).
     *
     * @param userToken The user's ThingsBoard JWT token.
     * @return A list of maps, each containing one device's data.
     */
    public List<Map<String, Object>> getUserDevicesData(String userToken) {
        String cacheKey = getCacheKey(userToken);

        // Check cache
        CachedUserData cached = userCacheMap.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            long ageSeconds = (System.currentTimeMillis() - cached.timestamp) / 1000;
            log.info("✅ Using cached user data ({}s old, {} devices)", ageSeconds, cached.devicesData.size());
            return cached.devicesData;
        }

        // Fetch fresh
        log.info("⏱️ Cache expired/empty for user. Fetching fresh data...");
        long fetchStart = System.currentTimeMillis();

        List<Map<String, String>> devices = userTbClient.getUserDevices(userToken);
        List<Map<String, Object>> allDevicesData = new ArrayList<>();

        for (Map<String, String> device : devices) {
            String deviceId = device.get("id");
            if (deviceId == null || deviceId.isBlank() || "null".equals(deviceId)) {
                log.warn("⚠️ Skipping device with missing or null ID: {}", device);
                continue;
            }
            Map<String, Object> deviceData = new HashMap<>();

            // Basic info
            deviceData.put("device_id", deviceId);
            deviceData.put("device_name", device.get("name"));
            deviceData.put("device_type", device.get("type"));

            try {
                deviceData.putAll(userTbClient.getAttributes(userToken, "CLIENT_SCOPE", deviceId));
                deviceData.putAll(userTbClient.getAttributes(userToken, "SERVER_SCOPE", deviceId));
                deviceData.putAll(userTbClient.getAttributes(userToken, "SHARED_SCOPE", deviceId));
                deviceData.putAll(userTbClient.getTelemetry(userToken, deviceId));
            } catch (Exception e) {
                log.error("❌ Error fetching data for device {}: {}", device.get("name"), e.getMessage());
            }

            allDevicesData.add(deviceData);
        }

        // Store in cache
        userCacheMap.put(cacheKey, new CachedUserData(allDevicesData, System.currentTimeMillis()));

        long fetchTime = System.currentTimeMillis() - fetchStart;
        log.info("✅ Cached user data! ({} devices, took {}ms)", allDevicesData.size(), fetchTime);

        return allDevicesData;
    }

    /**
     * Get a flattened map of all user device data, suitable for the ChatService context.
     * Combines data from all devices into a single map, prefixing keys with device name
     * when there are multiple devices.
     */
    public Map<String, Object> getUserDevicesDataFlat(String userToken) {
        List<Map<String, Object>> allDevices = getUserDevicesData(userToken);
        Map<String, Object> flat = new HashMap<>();

        if (allDevices.size() == 1) {
            // Single device — no prefix needed
            flat.putAll(allDevices.get(0));
        } else {
            // Multiple devices — prefix each key with device name
            flat.put("total_devices", allDevices.size());
            for (Map<String, Object> deviceData : allDevices) {
                String name = deviceData.getOrDefault("device_name", "unknown").toString();
                for (Map.Entry<String, Object> entry : deviceData.entrySet()) {
                    flat.put(name + "." + entry.getKey(), entry.getValue());
                }
            }
        }

        flat.put("fetched_at", System.currentTimeMillis());
        return flat;
    }

    /**
     * Get the user's device list (id + name only).
     */
    public List<Map<String, String>> getUserDevicesList(String userToken) {
        return userTbClient.getUserDevices(userToken);
    }

    /**
     * Invalidate cache for a specific user.
     */
    public void invalidateUserCache(String userToken) {
        String cacheKey = getCacheKey(userToken);
        userCacheMap.remove(cacheKey);
        log.info("🗑️ Cache invalidated for user");
    }

    // ==================== Helpers ====================

    /**
     * Generate a stable cache key from the user token.
     * We use a hash to avoid storing the full token as a map key.
     */
    private String getCacheKey(String userToken) {
        return String.valueOf(userToken.hashCode());
    }
}
