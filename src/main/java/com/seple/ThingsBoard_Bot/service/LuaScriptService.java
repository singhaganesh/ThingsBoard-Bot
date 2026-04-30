package com.seple.ThingsBoard_Bot.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Slf4j
@Service
public class LuaScriptService {

    private final StringRedisTemplate stringRedisTemplate;
    private DefaultRedisScript<Long> updateCountersScript;

    private static final String KEY_GLOBAL_COUNTERS = "%s:global:counters";
    private static final String KEY_NODE_COUNTERS = "%s:node:counters:%s";

    public LuaScriptService(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @PostConstruct
    public void init() {
        try {
            updateCountersScript = new DefaultRedisScript<>();
            updateCountersScript.setScriptSource(
                new ResourceScriptSource(new ClassPathResource("scripts/update_counters.lua"))
            );
            updateCountersScript.setResultType(Long.class);
            log.info("[LUA] Script loaded: update_counters.lua");
        } catch (Exception e) {
            log.warn("[LUA] Failed to load script, using inline fallback: {}", e.getMessage());
            updateCountersScript = null;
        }
    }

    public void executeUpdateCounters(
            String customerId,
            String deviceId,
            String branchNodeId,
            List<String> ancestorPath,
            String field,
            String newValue,
            String prevValue) {
        
        try {
            if (updateCountersScript != null) {
                executeLuaScript(customerId, deviceId, branchNodeId, ancestorPath, field, newValue, prevValue);
            } else {
                executeFallback(customerId, deviceId, branchNodeId, ancestorPath, field, newValue, prevValue);
            }
        } catch (Exception e) {
            log.error("[LUA] Failed to execute counter update: {}", e.getMessage(), e);
        }
    }

    private void executeLuaScript(
            String customerId,
            String deviceId,
            String branchNodeId,
            List<String> ancestorPath,
            String field,
            String newValue,
            String prevValue) {
        
        String globalKey = String.format(KEY_GLOBAL_COUNTERS, customerId);
        
        List<String> keys = new ArrayList<>();
        keys.add(globalKey);
        for (String nodeId : ancestorPath) {
            keys.add(String.format(KEY_NODE_COUNTERS, customerId, nodeId));
        }
        
        Long result = stringRedisTemplate.execute(
                updateCountersScript,
                keys,
                field,
                newValue,
                prevValue
        );

        log.info("[LUA] Script executed, result: {}", result);
    }

    private void executeFallback(
            String customerId,
            String deviceId,
            String branchNodeId,
            List<String> ancestorPath,
            String field,
            String newValue,
            String prevValue) {
        
        log.info("[LUA] Using fallback - updating counters for device={}, field={}, value={}->{}", 
                deviceId, field, prevValue, newValue);
        
        long delta = calculateDelta(field, prevValue, newValue);
        
        String globalKey = String.format(KEY_GLOBAL_COUNTERS, customerId);
        stringRedisTemplate.opsForHash().increment(globalKey, field, delta);
        
        for (String nodeId : ancestorPath) {
            String nodeKey = String.format(KEY_NODE_COUNTERS, customerId, nodeId);
            stringRedisTemplate.opsForHash().increment(nodeKey, field, delta);
        }
        
        log.info("[LUA] Fallback executed - updated {} ancestors", ancestorPath.size());
    }

    private long calculateDelta(String field, String prevValue, String newValue) {
        if (isStatusField(field)) {
            if (isOnlineState(newValue) && !isOnlineState(prevValue)) {
                return 1;
            } else if (!isOnlineState(newValue) && isOnlineState(prevValue)) {
                return -1;
            }
        }
        return 0;
    }

    private boolean isStatusField(String field) {
        return field != null && (field.contains("status") || field.contains("Status") || 
               field.equals("gateway_status") || field.equals("online"));
    }

    private boolean isOnlineState(String value) {
        if (value == null) return false;
        String lower = value.toLowerCase();
        return lower.equals("online") || lower.equals("on") || lower.equals("1") || lower.equals("true");
    }
}