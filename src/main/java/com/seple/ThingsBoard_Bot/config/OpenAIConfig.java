package com.seple.ThingsBoard_Bot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import lombok.Data;

@Data
@Configuration
@ConfigurationProperties(prefix = "iotchatbot.openai")
public class OpenAIConfig {

    private String apiKey;
    private String model = "gpt-3.5-turbo";
    private int maxTokens = 500;
    private double temperature = 0.7;
    private int timeout = 30000;
}
