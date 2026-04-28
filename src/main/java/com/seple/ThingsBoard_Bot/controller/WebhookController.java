package com.seple.ThingsBoard_Bot.controller;

import com.seple.ThingsBoard_Bot.model.dto.TbEventPayload;
import com.seple.ThingsBoard_Bot.service.EventParseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/webhooks")
@RequiredArgsConstructor
public class WebhookController {

    private final EventParseService eventParseService;
    private final RabbitTemplate rabbitTemplate;

    @PostMapping("/thingsboard")
    public ResponseEntity<Void> receiveThingsBoardWebhook(
            @RequestHeader(value = "X-HMAC-SHA256", required = false) String hmac,
            @RequestBody String rawBody) {
        
        log.info("📥 Received webhook from ThingsBoard (body size: {} bytes)", rawBody.length());
        
        try {
            TbEventPayload event = eventParseService.parsePayload(rawBody);
            
            if (event == null) {
                log.warn("⚠️ Failed to parse webhook payload");
                return ResponseEntity.badRequest().build();
            }
            
            log.info("📦 Parsed event: device={}, customer={}, field={}, value={}→{}",
                    event.getDeviceName(),
                    event.getCustomerId(),
                    event.getField(),
                    event.getPrevValue(),
                    event.getNewValue());
            
            rabbitTemplate.convertAndSend("iot.events", event);
            log.info("✅ Event sent to queue: iot.events");
            
            return ResponseEntity.ok().build();
            
        } catch (Exception e) {
            log.error("❌ Error processing webhook: {}", e.getMessage(), e);
            return ResponseEntity.status(500).build();
        }
    }
    
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        return ResponseEntity.ok(Map.of(
            "status", "UP",
            "endpoint", "thingsboard-webhook",
            "timestamp", Instant.now().toString()
        ));
    }
}