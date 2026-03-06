package com.seple.ThingsBoard_Bot.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ChatRequest {

    private String question;
    private String conversationId;
    private Boolean includeChart;
}
