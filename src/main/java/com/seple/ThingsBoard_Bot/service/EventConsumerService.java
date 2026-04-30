package com.seple.ThingsBoard_Bot.service;

import com.seple.ThingsBoard_Bot.model.dto.TbEventPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class EventConsumerService {

    private final IdempotencyService idempotencyService;
    private final EventWriteService eventWriteService;
    private final RedisCacheService redisCacheService;
    private final AncestorPathCache ancestorPathCache;
    private final LuaScriptService luaScriptService;

    @RabbitListener(queues = "iot.events")
    public void consume(TbEventPayload event) {
        log.info("📥 Consumed event: device={}, field={}, value={}",
                event.getDeviceName(), event.getField(), event.getNewValue());
        
        if (event == null || event.getTbMessageId() == null) {
            log.warn("⚠️ Invalid event payload, skipping");
            return;
        }
        
        if (!idempotencyService.checkAndMark(event.getTbMessageId())) {
            log.info("[IDEM] Duplicate message: {}", event.getTbMessageId());
            return;
        }
        
        try {
            eventWriteService.writeToDatabase(event);
            log.info("✅ Event written to database");
            
            updateRedisCache(event);
            
        } catch (Exception e) {
            log.error("❌ Failed to process event: {}", e.getMessage(), e);
        }
    }

    private void updateRedisCache(TbEventPayload event) {
        String customerId = event.getCustomerId();
        String deviceId = event.getDeviceId();
        String branchNodeId = event.getBranchName();
        
        redisCacheService.updateDeviceState(customerId, deviceId, event.getField(), event.getNewValue());
        
        redisCacheService.setDeviceMeta(customerId, deviceId, branchNodeId, event.getBranchName());
        
        List<String> ancestors = ancestorPathCache.getAncestors(customerId, branchNodeId);
        if (ancestors.isEmpty()) {
            ancestors = buildDefaultAncestors(customerId, branchNodeId);
            ancestorPathCache.cacheAncestors(customerId, branchNodeId, ancestors);
        }
        
        luaScriptService.executeUpdateCounters(
                customerId,
                deviceId,
                branchNodeId,
                ancestors,
                event.getField(),
                event.getNewValue(),
                event.getPrevValue()
        );
        
        log.info("✅ Redis cache updated for {}/{}", customerId, deviceId);
    }

    private List<String> buildDefaultAncestors(String customerId, String branchNodeId) {
        return Arrays.asList(
                customerId + "_HO",
                customerId + "_ZO_" + branchNodeId,
                branchNodeId
        );
    }
}