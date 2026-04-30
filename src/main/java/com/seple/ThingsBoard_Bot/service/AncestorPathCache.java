package com.seple.ThingsBoard_Bot.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class AncestorPathCache {

    private final StringRedisTemplate stringRedisTemplate;
    private static final Duration CACHE_TTL = Duration.ofHours(24);
    private static final String KEY_PREFIX = "%s:ancestors:%s";

    public AncestorPathCache(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void cacheAncestors(String customerId, String branchNodeId, List<String> ancestors) {
        String key = String.format(KEY_PREFIX, customerId, branchNodeId);
        String value = String.join(",", ancestors);
        stringRedisTemplate.opsForValue().set(key, value, CACHE_TTL);
        log.info("[CACHE] Cached {} ancestors for {}/{}", ancestors.size(), customerId, branchNodeId);
    }

    public List<String> getAncestors(String customerId, String branchNodeId) {
        String key = String.format(KEY_PREFIX, customerId, branchNodeId);
        String value = stringRedisTemplate.opsForValue().get(key);
        
        if (value != null && !value.isEmpty()) {
            log.info("[CACHE] Found ancestors for {}/{}: {}", customerId, branchNodeId, value);
            List<String> ancestors = new ArrayList<>();
            for (String part : value.split(",")) {
                if (!part.isEmpty()) {
                    ancestors.add(part);
                }
            }
            return ancestors;
        }
        
        log.info("[CACHE] No cached ancestors for {}/{}", customerId, branchNodeId);
        return new ArrayList<>();
    }

    public boolean hasAncestors(String customerId, String branchNodeId) {
        String key = String.format(KEY_PREFIX, customerId, branchNodeId);
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(key));
    }

    public void invalidate(String customerId, String branchNodeId) {
        String key = String.format(KEY_PREFIX, customerId, branchNodeId);
        stringRedisTemplate.delete(key);
        log.info("[CACHE] Invalidated ancestors for {}/{}", customerId, branchNodeId);
    }

    public void invalidateAll(String customerId) {
        var keys = stringRedisTemplate.keys(customerId + ":ancestors:*");
        if (!keys.isEmpty()) {
            stringRedisTemplate.delete(keys);
            log.info("[CACHE] Invalidated all ancestor paths for customer: {}", customerId);
        }
    }
}