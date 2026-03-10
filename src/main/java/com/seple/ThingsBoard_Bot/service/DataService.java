package com.seple.ThingsBoard_Bot.service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.annotation.PostConstruct;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.seple.ThingsBoard_Bot.client.ThingsBoardClient;

/**
 * DataService with 5-MINUTE in-memory caching + background refresh.
 * <p>
 * This is the core caching layer. All Q&A queries go through this service.
 * Data is refreshed in background every 4 minutes (before TTL expires).
 * Users always get instant responses from cache.
 * </p>
 */
@Service
public class DataService {

    private static final Logger log = LoggerFactory.getLogger(DataService.class);
    private final ThingsBoardClient tbClient;

    // Cache storage
    private Map<String, Object> cachedData;
    private long lastCacheTime;
    private final AtomicBoolean isRefreshing = new AtomicBoolean(false);

    // 5-MINUTE TTL (data stays fresh for 5 minutes)
    private static final long CACHE_TTL_MS = 5 * 60 * 1000;
    // Refresh threshold (refresh in background when 80% of TTL elapsed)
    private static final long REFRESH_THRESHOLD_MS = CACHE_TTL_MS * 80 / 100;

    public DataService(ThingsBoardClient tbClient) {
        this.tbClient = tbClient;
        // Pre-load cache on startup (async to not block app startup)
        log.info("🚀 Initializing DataService with background refresh (5-min TTL)...");
        initializeCacheAsync();
    }

    /**
     * Initialize cache asynchronously to avoid blocking application startup.
     */
    @PostConstruct
    public void initializeCacheAsync() {
        Thread initThread = new Thread(() -> {
            try {
                refreshCacheSync();
                log.info("✅ Initial cache loaded successfully");
            } catch (Exception e) {
                log.warn("⚠️ Initial cache load failed (will retry on first request): {}", e.getMessage());
            }
        }, "DataService-Init");
        initThread.start();
    }

    /**
     * Get device data. ALWAYS returns cached data instantly.
     * Background refresh happens automatically when cache is about to expire.
     */
    public synchronized Map<String, Object> getDeviceData() {
        // Always return cached data instantly (even if stale)
        if (cachedData != null) {
            long ageSeconds = (System.currentTimeMillis() - lastCacheTime) / 1000;
            long ttlSeconds = CACHE_TTL_MS / 1000;
            
            // Trigger background refresh if cache is old
            if (System.currentTimeMillis() - lastCacheTime > REFRESH_THRESHOLD_MS) {
                triggerBackgroundRefresh();
            }
            
            log.info("✅ Returning cached data ({}s old, TTL: {}s)", ageSeconds, ttlSeconds);
            return new HashMap<>(cachedData); // Return copy to avoid mutation issues
        }

        // First request: fetch synchronously (shouldn't happen due to startup preload)
        log.warn("⚠️ Cache empty on request, fetching synchronously...");
        refreshCacheSync();
        return cachedData != null ? new HashMap<>(cachedData) : new HashMap<>();
    }

    /**
     * Scheduled background refresh - runs every 4 minutes.
     * This keeps data fresh without blocking user requests.
     */
    @Scheduled(fixedRate = 4 * 60 * 1000) // Every 4 minutes
    public void scheduledRefresh() {
        log.info("⏰ Scheduled background refresh triggered");
        refreshCacheAsync();
    }

    /**
     * Trigger background refresh if not already refreshing.
     */
    private void triggerBackgroundRefresh() {
        if (!isRefreshing.get()) {
            refreshCacheAsync();
        }
    }

    /**
     * Refresh cache in background (async).
     */
    private void refreshCacheAsync() {
        if (isRefreshing.getAndSet(true)) {
            log.debug("⏳ Refresh already in progress, skipping...");
            return;
        }
        
        Thread backgroundThread = new Thread(() -> {
            try {
                refreshCacheSync();
            } finally {
                isRefreshing.set(false);
            }
        }, "DataService-BackgroundRefresh");
        backgroundThread.start();
    }

    /**
     * Synchronously refresh the cache from ThingsBoard.
     */
    private void refreshCacheSync() {
        log.info("🔄 Refreshing device data from ThingsBoard...");
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
            log.info("✅ Cache refreshed! ({}ms, {} keys, valid for 5 min)", fetchTime, freshData.size());

        } catch (Exception e) {
            log.error("❌ Error refreshing cache: {}", e.getMessage());
            // Keep old cache data on error
        }
    }

    /**
     * Force cache invalidation (useful for testing or manual refresh).
     */
    public synchronized void invalidateCache() {
        this.cachedData = null;
        this.lastCacheTime = 0;
        log.info("🗑️ Cache invalidated");
    }
}
