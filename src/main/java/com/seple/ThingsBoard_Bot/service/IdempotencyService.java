package com.seple.ThingsBoard_Bot.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Slf4j
@Service
public class IdempotencyService {

    private final StringRedisTemplate redisTemplate;
    private static final Duration IDEM_TTL = Duration.ofHours(24);

    public IdempotencyService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public boolean checkAndMark(String messageId) {
        if (messageId == null || messageId.isBlank()) {
            return false;
        }
        
        String key = "idem:" + messageId;
        
        Boolean wasSet = redisTemplate.opsForValue()
                .setIfAbsent(key, "1", IDEM_TTL);
        
        return wasSet != null && wasSet;
    }
    
    public boolean exists(String messageId) {
        if (messageId == null || messageId.isBlank()) {
            return false;
        }
        
        String key = "idem:" + messageId;
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }
}