package com.seple.ThingsBoard_Bot.service;

import java.util.Map;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seple.ThingsBoard_Bot.client.OpenAIClient;
import com.seple.ThingsBoard_Bot.exception.ContextOverflowException;
import com.seple.ThingsBoard_Bot.model.dto.ChatRequest;
import com.seple.ThingsBoard_Bot.model.dto.ChatResponse;
import com.seple.ThingsBoard_Bot.model.dto.ChartData;
import com.seple.ThingsBoard_Bot.util.ContextFilterUtil;
import com.seple.ThingsBoard_Bot.util.TokenCounterService;

import lombok.extern.slf4j.Slf4j;

/**
 * Core chat service — the HEART of the application.
 * <p>
 * Orchestrates the full Q&A flow:
 * 1. Fetch device data (cached via DataService)
 * 2. Filter context (reduce tokens)
 * 3. Count tokens (validate before OpenAI call)
 * 4. Call OpenAI with system prompt + context + question
 * 5. Return formatted response
 * </p>
 */
@Slf4j
@Service
public class ChatService {

    private final DataService dataService;
    private final OpenAIClient openAIClient;
    private final ChartService chartService;
    private final ObjectMapper objectMapper;

    private static final String SYSTEM_PROMPT = """
            You are an IoT device assistant. You help users understand the status and data
            from their IoT devices connected to ThingsBoard.

            You will receive real-time device data as context. Use this data to answer
            questions accurately. Always refer to the actual values from the context.

            Guidelines:
            - Be concise and informative
            - If a value is not available in the context, say so honestly
            - Use appropriate units when discussing sensor values
            - Highlight any concerning values (low battery, high temperature, alarms)
            - If asked about trends or history, mention that chart data can be requested
            - Format numbers nicely (e.g., "67%" not "67.0")
            """;

    public ChatService(DataService dataService, OpenAIClient openAIClient, ChartService chartService) {
        this.dataService = dataService;
        this.openAIClient = openAIClient;
        this.chartService = chartService;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Answer a user question about device data.
     */
    public ChatResponse answerQuestion(ChatRequest request) {
        long startTime = System.currentTimeMillis();
        log.info("📩 Processing question: '{}'", request.getQuestion());

        try {
            // Step 1: Get device data (from cache or fresh fetch)
            Map<String, Object> rawData = dataService.getDeviceData();
            log.debug("Got {} raw data keys", rawData.size());

            // Step 2: Filter context to reduce tokens
            Map<String, Object> filteredData = ContextFilterUtil.filterAttributes(rawData);
            log.debug("Filtered to {} keys", filteredData.size());

            // Step 3: Convert to JSON string for the prompt
            String contextJson = objectMapper.writeValueAsString(filteredData);

            // Step 4: Count tokens and validate
            int totalTokens = TokenCounterService.countMessageTokens(
                    SYSTEM_PROMPT, request.getQuestion(), contextJson);

            if (!TokenCounterService.fitsInContextWindow(totalTokens)) {
                throw new ContextOverflowException(
                        "Context too large: " + totalTokens + " tokens (max 6000)");
            }

            // Step 5: Build the user message with context
            String userMessage = "Device Data Context:\n" + contextJson
                    + "\n\nUser Question: " + request.getQuestion();

            // Step 6: Call OpenAI
            String answer = openAIClient.chat(SYSTEM_PROMPT, userMessage);

            // Step 7: Generate chart if requested
            ChartData chartData = null;
            if (Boolean.TRUE.equals(request.getIncludeChart()) || isChartRequest(request.getQuestion())) {
                String chartKey = detectChartKey(request.getQuestion(), filteredData);
                if (chartKey != null) {
                    chartData = chartService.generateChartData(chartKey);
                    log.info("📊 Chart generated for key: {}", chartKey);
                }
            }

            long duration = System.currentTimeMillis() - startTime;
            log.info("✅ Question answered in {}ms ({} tokens)", duration, totalTokens);

            // Build a small summary context for the response (not the full data)
            Map<String, Object> summaryContext = new java.util.LinkedHashMap<>();
            if (filteredData.containsKey("deviceName"))
                summaryContext.put("deviceName", filteredData.get("deviceName"));
            if (filteredData.containsKey("status"))
                summaryContext.put("status", filteredData.get("status"));
            if (filteredData.containsKey("active"))
                summaryContext.put("active", filteredData.get("active"));
            if (filteredData.containsKey("alarmCount"))
                summaryContext.put("alarmCount", filteredData.get("alarmCount"));
            summaryContext.put("dataKeysUsed", filteredData.size());

            return ChatResponse.builder()
                    .answer(answer)
                    .context(summaryContext)
                    .chart(chartData)
                    .tokensUsed(totalTokens)
                    .timestamp(System.currentTimeMillis())
                    .error(false)
                    .build();

        } catch (ContextOverflowException e) {
            log.error("❌ Context overflow: {}", e.getMessage());
            return ChatResponse.builder()
                    .answer("I'm sorry, the device data is too large to process. "
                            + "Please try a more specific question.")
                    .error(true)
                    .errorMessage(e.getMessage())
                    .timestamp(System.currentTimeMillis())
                    .build();

        } catch (Exception e) {
            log.error("❌ Error answering question: {}", e.getMessage(), e);
            return ChatResponse.builder()
                    .answer("I encountered an error processing your question. Please try again.")
                    .error(true)
                    .errorMessage(e.getMessage())
                    .timestamp(System.currentTimeMillis())
                    .build();
        }
    }

    /**
     * Detect if the user's question is asking for a chart/graph/trend.
     */
    private boolean isChartRequest(String question) {
        if (question == null) return false;
        String lower = question.toLowerCase();
        return lower.contains("chart") || lower.contains("graph")
                || lower.contains("trend") || lower.contains("history")
                || lower.contains("plot") || lower.contains("show me");
    }

    /**
     * Detect which telemetry key to chart based on the question.
     */
    private String detectChartKey(String question, Map<String, Object> data) {
        if (question == null) return null;
        String lower = question.toLowerCase();

        // Try to match question keywords to data keys
        for (String key : data.keySet()) {
            if (lower.contains(key.toLowerCase().replace("_", " "))
                    || lower.contains(key.toLowerCase())) {
                return key;
            }
        }

        // Common keyword mappings
        if (lower.contains("battery")) return findKeyContaining(data, "battery");
        if (lower.contains("temperature") || lower.contains("temp")) return findKeyContaining(data, "temp");
        if (lower.contains("humidity")) return findKeyContaining(data, "humidity");
        if (lower.contains("cpu")) return findKeyContaining(data, "cpu");
        if (lower.contains("memory") || lower.contains("ram")) return findKeyContaining(data, "memory");

        return null;
    }

    private String findKeyContaining(Map<String, Object> data, String keyword) {
        return data.keySet().stream()
                .filter(k -> k.toLowerCase().contains(keyword))
                .findFirst()
                .orElse(null);
    }
}
