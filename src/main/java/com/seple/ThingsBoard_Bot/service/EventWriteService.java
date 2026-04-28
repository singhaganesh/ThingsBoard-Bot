package com.seple.ThingsBoard_Bot.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seple.ThingsBoard_Bot.entity.DeviceEvent;
import com.seple.ThingsBoard_Bot.model.dto.TbEventPayload;
import com.seple.ThingsBoard_Bot.repository.DeviceEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class EventWriteService {

    private final DeviceEventRepository deviceEventRepository;
    private final ObjectMapper objectMapper;

    public void writeToDatabase(TbEventPayload event) {
        try {
            DeviceEvent entity = new DeviceEvent();
            entity.setCustomerId(event.getCustomerId());
            entity.setTbMessageId(event.getTbMessageId());
            entity.setLogType(event.getLogType());
            entity.setField(event.getField());
            entity.setPrevValue(event.getPrevValue());
            entity.setNewValue(event.getNewValue());
            entity.setEventTime(event.getEventTime());
            entity.setRawPayload(objectMapper.writeValueAsString(event.getAttributes() != null ? event.getAttributes() : event.getTelemetry()));

            deviceEventRepository.save(entity);
            log.info("✅ Event saved: customer={}, field={}, value={}",
                    event.getCustomerId(), event.getField(), event.getNewValue());

        } catch (JsonProcessingException e) {
            log.error("❌ Failed to serialize raw payload: {}", e.getMessage());
            throw new RuntimeException("Failed to write event", e);
        }
    }
}