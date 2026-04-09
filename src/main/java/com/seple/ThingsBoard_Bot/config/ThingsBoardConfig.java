package com.seple.ThingsBoard_Bot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import lombok.Data;

@Data
@Configuration
@ConfigurationProperties(prefix = "iotchatbot.thingsboard")
public class ThingsBoardConfig {

    private String url;
    private String username;
    private String password;
    private String deviceId;
    private String aggregatorDeviceId;
    private String allowedKeys;
    private int timeout = 30000;
    private int cacheTtlSeconds = 60;
    private int requestTimeoutMs = 30000;
    private int devicePageSize = 100;
    private int maxParallelPages = 8;
    private int syncIntervalSeconds = 60;
    private boolean entityQueryEnabled = true;
    private boolean twoStepFetchEnabled = false;
    private boolean aggregatorEnabled = false;
    private int retryAttempts = 3;
    private long retryBackoffMs = 300L;

    @Bean(name = "thingsBoardRestTemplate")
    public RestTemplate thingsBoardRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        int effectiveTimeout = requestTimeoutMs > 0 ? requestTimeoutMs : timeout;
        factory.setConnectTimeout(java.time.Duration.ofMillis(effectiveTimeout));
        factory.setReadTimeout(java.time.Duration.ofMillis(effectiveTimeout));
        RestTemplate restTemplate = new RestTemplate(factory);
        return restTemplate;
    }
}
