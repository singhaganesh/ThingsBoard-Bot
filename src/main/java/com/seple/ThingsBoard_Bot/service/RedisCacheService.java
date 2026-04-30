package com.seple.ThingsBoard_Bot.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class RedisCacheService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final StringRedisTemplate stringRedisTemplate;

    private static final Duration DEFAULT_TTL = Duration.ofHours(24);
    private static final Duration COUNTER_TTL = Duration.ofHours(168); // 7 days

    public static final String KEY_GLOBAL_COUNTERS = "%s:global:counters";
    public static final String KEY_NODE_COUNTERS = "%s:node:counters:%s";
    public static final String KEY_DEVICE_STATE = "%s:device:state:%s";
    public static final String KEY_DEVICE_META = "%s:device:meta:%s";

    public void updateDeviceState(String customerId, String deviceId, String field, String value) {
        String key = String.format(KEY_DEVICE_STATE, customerId, deviceId);
        redisTemplate.opsForHash().put(key, field, value);
        redisTemplate.expire(key, DEFAULT_TTL);
        log.info("[REDIS] Updated device state: {}/{} -> {}={}", customerId, deviceId, field, value);
    }

    public Map<Object, Object> getDeviceState(String customerId, String deviceId) {
        String key = String.format(KEY_DEVICE_STATE, customerId, deviceId);
        return redisTemplate.opsForHash().entries(key);
    }

    public void setDeviceMeta(String customerId, String deviceId, String branchNodeId, String branchName) {
        String key = String.format(KEY_DEVICE_META, customerId, deviceId);
        Map<String, String> meta = new HashMap<>();
        meta.put("customer_id", customerId);
        meta.put("branch_node_id", branchNodeId);
        meta.put("branch_name", branchName);
        redisTemplate.opsForHash().putAll(key, meta);
        redisTemplate.expire(key, DEFAULT_TTL);
        log.info("[REDIS] Set device meta: {}/{} -> branch={}", customerId, deviceId, branchName);
    }

    public Map<Object, Object> getDeviceMeta(String customerId, String deviceId) {
        String key = String.format(KEY_DEVICE_META, customerId, deviceId);
        return redisTemplate.opsForHash().entries(key);
    }

    public void updateGlobalCounters(String customerId, String field, long delta) {
        String key = String.format(KEY_GLOBAL_COUNTERS, customerId);
        redisTemplate.opsForHash().increment(key, field, delta);
        redisTemplate.expire(key, COUNTER_TTL);
        log.info("[REDIS] Updated global counter: {}/{} += {}", customerId, field, delta);
    }

    public Map<Object, Object> getGlobalCounters(String customerId) {
        String key = String.format(KEY_GLOBAL_COUNTERS, customerId);
        return redisTemplate.opsForHash().entries(key);
    }

    public void updateNodeCounters(String customerId, String nodeId, String field, long delta) {
        String key = String.format(KEY_NODE_COUNTERS, customerId, nodeId);
        redisTemplate.opsForHash().increment(key, field, delta);
        redisTemplate.expire(key, COUNTER_TTL);
        log.info("[REDIS] Updated node counter: {}/{}/{} += {}", customerId, nodeId, field, delta);
    }

    public Map<Object, Object> getNodeCounters(String customerId, String nodeId) {
        String key = String.format(KEY_NODE_COUNTERS, customerId, nodeId);
        return redisTemplate.opsForHash().entries(key);
    }

    public Set<String> getAllDeviceKeys(String customerId) {
        return stringRedisTemplate.keys(customerId + ":device:state:*");
    }

    public Set<String> getAllNodeKeys(String customerId) {
        return stringRedisTemplate.keys(customerId + ":node:counters:*");
    }

    public void clearCustomerCache(String customerId) {
        Set<String> deviceKeys = stringRedisTemplate.keys(customerId + ":device:*");
        Set<String> nodeKeys = stringRedisTemplate.keys(customerId + ":node:*");
        Set<String> globalKeys = stringRedisTemplate.keys(customerId + ":global:*");
        
        if (!deviceKeys.isEmpty()) stringRedisTemplate.delete(deviceKeys);
        if (!nodeKeys.isEmpty()) stringRedisTemplate.delete(nodeKeys);
        if (!globalKeys.isEmpty()) stringRedisTemplate.delete(globalKeys);
        
        log.info("[REDIS] Cleared cache for customer: {}", customerId);
    }
}