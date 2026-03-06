package com.seple.ThingsBoard_Bot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import lombok.Data;

@Data
@Configuration
@ConfigurationProperties(prefix = "iotchatbot.chatbot")
public class ChatbotConfig {

    private int maxContextTokens = 6000;
    private boolean enableAlerts = true;
    private int alertPollInterval = 10000;
}
