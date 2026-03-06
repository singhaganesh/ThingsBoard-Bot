package com.seple.ThingsBoard_Bot.model.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AlertData {

    private boolean hasAlert;
    private List<String> alerts;
    private long timestamp;
}
