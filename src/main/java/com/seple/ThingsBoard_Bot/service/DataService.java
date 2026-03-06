package com.seple.ThingsBoard_Bot.service;

import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.seple.ThingsBoard_Bot.client.ThingsBoardClient;

import lombok.extern.slf4j.Slf4j;

/**
 * DataService with 1-MINUTE in-memory caching.
 * <p>
 * This is the core caching layer. All Q&A queries go through this service.
 * Alerts use their own separate refresh cycle (10 seconds) and bypass this cache.
 * </p>
 */
@Slf4j
@Service
public class DataService {

    private final ThingsBoardClient tbClient;

    // Cache storage
    private Map<String, Object> cachedData;
    private long lastCacheTime;

    // 1-MINUTE TTL
    private static final long CACHE_TTL_MS = 60 * 1000;

    public DataService(ThingsBoardClient tbClient) {
        this.tbClient = tbClient;
    }

    /**
     * Get device data. Returns cached data if valid (< 1 minute old),
     * otherwise fetches fresh data from ThingsBoard.
     */
    public synchronized Map<String, Object> getDeviceData() {
        if (isCacheValid()) {
            long ageSeconds = (System.currentTimeMillis() - lastCacheTime) / 1000;
            long expiresIn = (CACHE_TTL_MS / 1000) - ageSeconds;
            log.info("✅ Using cached data ({}s old, expires in {}s)", ageSeconds, expiresIn);
            return cachedData;
        }

        log.warn("⏱️ Cache expired/empty. Fetching fresh data from ThingsBoard...");
        long fetchStart = System.currentTimeMillis();

        Map<String, Object> freshData = new HashMap<>();

        try {
            // Fetch from all attribute scopes
            freshData.putAll(tbClient.getAttributes("CLIENT_SCOPE"));
            freshData.putAll(tbClient.getAttributes("SERVER_SCOPE"));
            freshData.putAll(tbClient.getAttributes("SHARED_SCOPE"));

            // Fetch latest telemetry
            freshData.putAll(tbClient.getTelemetry());

            // Add metadata
            freshData.put("fetched_at", System.currentTimeMillis());

            // Save to cache
            this.cachedData = freshData;
            this.lastCacheTime = System.currentTimeMillis();

            long fetchTime = System.currentTimeMillis() - fetchStart;
            log.info("✅ Fresh data cached! (Fetch took {}ms, {} keys, expires in 60s)",
                    fetchTime, freshData.size());

        } catch (Exception e) {
            log.error("❌ Error fetching device data: {}", e.getMessage());

            // If we have stale cached data, return it rather than nothing
            if (cachedData != null) {
                log.warn("⚠️ Returning stale cached data due to fetch error");
                return cachedData;
            }
        }

        return freshData;
    }

    /**
     * Force cache invalidation (useful for testing or manual refresh).
     */
    public synchronized void invalidateCache() {
        this.cachedData = null;
        this.lastCacheTime = 0;
        log.info("🗑️ Cache invalidated");
    }

    /**
     * Check if cache is still within the 1-minute TTL.
     */
    private boolean isCacheValid() {
        if (cachedData == null) {
            return false;
        }
        long ageMs = System.currentTimeMillis() - lastCacheTime;
        return ageMs < CACHE_TTL_MS;
    }
}
