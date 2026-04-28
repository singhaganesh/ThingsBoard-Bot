package com.seple.ThingsBoard_Bot.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seple.ThingsBoard_Bot.model.dto.TbEventPayload;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.context.annotation.ApplicationScope;

import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

@Slf4j
@Service
@ApplicationScope
public class EventParseService {

    private final ObjectMapper objectMapper;

    @Value("${iotchatbot.customers.prefixes:BOI,BOB,SBI,CB,IB,PNB,UBI,CBI,IOB,UCO}")
    private String customerPrefixes;

    private static final String[] CUSTOMER_PREFIX_ARRAY = {
        "BOI", "BOB", "SBI", "CB", "IB", "PNB", "UBI", "CBI", "IOB", "UCO"
    };

    public EventParseService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public TbEventPayload parsePayload(String rawBody) {
        try {
            JsonNode root = objectMapper.readTree(rawBody);
            
            String deviceName = getTextValue(root, "deviceName");
            String deviceId = getTextValue(root, "deviceId");
            
            if (deviceName == null || deviceName.isBlank()) {
                log.warn("⚠️ No deviceName in payload");
                return null;
            }
            
            String customerId = extractCustomerId(deviceName);
            String branchName = extractBranchName(deviceName);
            
            TbEventPayload event = new TbEventPayload();
            event.setDeviceName(deviceName);
            event.setDeviceId(deviceId != null ? deviceId : "");
            event.setCustomerId(customerId);
            event.setBranchName(branchName);
            event.setReceivedAt(Instant.now());
            
            parseAttributeChanges(root, event);
            parseTelemetryChanges(root, event);
            
            return event;
            
        } catch (JsonProcessingException e) {
            log.error("❌ Failed to parse JSON: {}", e.getMessage());
            return null;
        }
    }

    private void parseAttributeChanges(JsonNode root, TbEventPayload event) {
        JsonNode dataNode = root.get("data");
        JsonNode currentAttr = dataNode != null ? dataNode.get("currentAttr") : null;
        JsonNode prevAttr = dataNode != null ? dataNode.get("prevAttr") : null;
        
        if (currentAttr == null || currentAttr.isEmpty()) {
            return;
        }
        
        Iterator<Map.Entry<String, JsonNode>> fields = currentAttr.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String field = entry.getKey();
            String newValue = entry.getValue().asText();
            
            String prevValue = "";
            if (prevAttr != null && prevAttr.has(field)) {
                prevValue = prevAttr.get(field).asText();
            }
            
            if (!newValue.equals(prevValue)) {
                event.setTbMessageId(java.util.UUID.randomUUID().toString());
                event.setLogType("ATTRIBUTE_CHANGE");
                event.setField(field);
                event.setPrevValue(prevValue);
                event.setNewValue(newValue);
                event.setEventTime(Instant.now());
                event.setAttributes(flatten(currentAttr));
                return;
            }
        }
    }

    private void parseTelemetryChanges(JsonNode root, TbEventPayload event) {
        JsonNode dataNode = root.get("data");
        JsonNode currentTs = dataNode != null ? dataNode.get("currentTelemetry") : null;
        JsonNode prevTs = dataNode != null ? dataNode.get("prevTelemetry") : null;
        
        if (currentTs == null || currentTs.isEmpty()) {
            return;
        }
        
        Iterator<Map.Entry<String, JsonNode>> fields = currentTs.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String field = entry.getKey();
            String newValue = entry.getValue().asText();
            
            String prevValue = "";
            if (prevTs != null && prevTs.has(field)) {
                prevValue = prevTs.get(field).asText();
            }
            
            if (!newValue.equals(prevValue)) {
                event.setTbMessageId(java.util.UUID.randomUUID().toString());
                event.setLogType("TELEMETRY_CHANGE");
                event.setField(field);
                event.setPrevValue(prevValue);
                event.setNewValue(newValue);
                event.setEventTime(Instant.now());
                event.setTelemetry(flatten(currentTs));
                return;
            }
        }
    }

    private String extractCustomerId(String deviceName) {
        if (deviceName == null || deviceName.isBlank()) {
            return "UNKNOWN";
        }
        
        String upperName = deviceName.toUpperCase();
        for (String prefix : CUSTOMER_PREFIX_ARRAY) {
            if (upperName.startsWith(prefix + "-") || upperName.startsWith(prefix)) {
                return prefix;
            }
        }
        
        log.warn("⚠️ Unknown customer prefix for device: {}", deviceName);
        return "UNKNOWN";
    }

    private String extractBranchName(String deviceName) {
        if (deviceName == null || deviceName.isBlank()) {
            return "";
        }
        
        String upperName = deviceName.toUpperCase();
        for (String prefix : CUSTOMER_PREFIX_ARRAY) {
            if (upperName.startsWith(prefix + "-")) {
                return deviceName.substring(prefix.length() + 1);
            }
        }
        
        return deviceName;
    }

    private String getTextValue(JsonNode node, String field) {
        JsonNode fieldNode = node.get(field);
        return fieldNode != null ? fieldNode.asText() : null;
    }

    private Map<String, Object> flatten(JsonNode node) {
        Map<String, Object> result = new HashMap<>();
        if (node == null || node.isEmpty()) {
            return result;
        }
        
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            result.put(entry.getKey(), entry.getValue().asText());
        }
        return result;
    }
}