package com.seple.ThingsBoard_Bot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import lombok.Data;

@Data
@Configuration
@ConfigurationProperties(prefix = "iotchatbot.chatbot")
public class ChatbotConfig {

    private int maxContextTokens = 10000;
    private boolean enableAlerts = true;
    private int alertPollInterval = 10000;
    private boolean deterministicAnswersEnabled = true;
    private boolean logDecisionMetadata = true;
    private boolean branchAliasStrictMode = false;
}
