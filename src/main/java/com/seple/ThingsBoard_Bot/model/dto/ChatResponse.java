package com.seple.ThingsBoard_Bot.model.dto;

import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ChatResponse {

    private String answer;
    private Map<String, Object> context;
    private Object chart;
    private AnswerMetadata metadata;
    private int tokensUsed;
    private long timestamp;
    private boolean error;
    private String errorMessage;
}
