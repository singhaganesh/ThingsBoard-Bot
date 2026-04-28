package com.seple.ThingsBoard_Bot.service;

import com.seple.ThingsBoard_Bot.model.dto.TbEventPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class EventConsumerService {

    private final IdempotencyService idempotencyService;
    private final EventWriteService eventWriteService;

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
        } catch (Exception e) {
            log.error("❌ Failed to write event to database: {}", e.getMessage(), e);
        }
    }
}