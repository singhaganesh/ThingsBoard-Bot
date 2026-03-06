package com.seple.ThingsBoard_Bot.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seple.ThingsBoard_Bot.config.OpenAIConfig;

import lombok.extern.slf4j.Slf4j;

/**
 * OpenAI REST API client using RestTemplate.
 * Sends chat completion requests and returns the AI response.
 */
@Slf4j
@Component
public class OpenAIClient {

    private final OpenAIConfig config;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    private static final String OPENAI_CHAT_URL = "https://api.openai.com/v1/chat/completions";

    public OpenAIClient(OpenAIConfig config) {
        this.config = config;
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Send a chat completion request to OpenAI.
     *
     * @param systemPrompt The system message defining the AI's role
     * @param userMessage  The user's question with context
     * @return The AI's response text
     */
    public String chat(String systemPrompt, String userMessage) {
        log.debug("Calling OpenAI {} with {} char message", config.getModel(), userMessage.length());

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + config.getApiKey());

            // Build request body
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", config.getModel());
            requestBody.put("max_tokens", config.getMaxTokens());
            requestBody.put("temperature", config.getTemperature());

            // Messages array
            List<Map<String, String>> messages = new ArrayList<>();

            Map<String, String> systemMsg = new HashMap<>();
            systemMsg.put("role", "system");
            systemMsg.put("content", systemPrompt);
            messages.add(systemMsg);

            Map<String, String> userMsg = new HashMap<>();
            userMsg.put("role", "user");
            userMsg.put("content", userMessage);
            messages.add(userMsg);

            requestBody.put("messages", messages);

            String jsonBody = objectMapper.writeValueAsString(requestBody);
            HttpEntity<String> entity = new HttpEntity<>(jsonBody, headers);

            long startTime = System.currentTimeMillis();
            ResponseEntity<String> response = restTemplate.postForEntity(OPENAI_CHAT_URL, entity, String.class);
            long duration = System.currentTimeMillis() - startTime;

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode responseJson = objectMapper.readTree(response.getBody());
                String reply = responseJson
                        .path("choices").get(0)
                        .path("message")
                        .path("content").asText();

                int totalTokens = responseJson.path("usage").path("total_tokens").asInt(0);

                log.info("✅ OpenAI response received in {}ms ({} tokens used)", duration, totalTokens);
                return reply;
            } else {
                log.error("❌ OpenAI returned non-success: {}", response.getStatusCode());
                return "I'm sorry, I encountered an error communicating with the AI service. Please try again.";
            }

        } catch (Exception e) {
            log.error("❌ Error calling OpenAI: {}", e.getMessage());
            return "I'm sorry, I couldn't process your question right now. Error: " + e.getMessage();
        }
    }
}
