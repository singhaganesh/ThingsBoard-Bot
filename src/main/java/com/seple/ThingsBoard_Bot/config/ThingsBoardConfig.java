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
    private String allowedKeys;
    private int timeout = 30000;
    private int cacheTtlSeconds = 60;

    @Bean(name = "thingsBoardRestTemplate")
    public RestTemplate thingsBoardRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(java.time.Duration.ofMillis(timeout));
        factory.setReadTimeout(java.time.Duration.ofMillis(timeout));
        RestTemplate restTemplate = new RestTemplate(factory);
        return restTemplate;
    }
}
