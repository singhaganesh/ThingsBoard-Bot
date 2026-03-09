package com.seple.ThingsBoard_Bot.service;

import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;

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
    private final UserDataService userDataService;
    private final OpenAIClient openAIClient;
    private final ChartService chartService;
    private final ChatMemoryService chatMemoryService;
    private final ObjectMapper objectMapper;

    private static final String SYSTEM_PROMPT = """
            You are an IoT device assistant. You help users understand the status and data
            from their IoT devices connected to ThingsBoard.

            You will receive real-time device data as context. Use this data to answer
            questions accurately. Always refer to the actual values from the context.

            Guidelines:
            - Be concise and informative
            - If a value is not available in the context, say so honestly, UNLESS a SYSTEM_NOTE tells you otherwise.
            - If the context contains a SYSTEM_NOTE, you MUST follow its instructions exactly.
            - Use appropriate units when discussing sensor values
            - Highlight any concerning values (low battery, high temperature, alarms)
            - If asked about trends or history, mention that chart data can be requested
            - Format numbers nicely (e.g., "67%" not "67.0")
            """;

    public ChatService(DataService dataService, UserDataService userDataService, OpenAIClient openAIClient, ChartService chartService, ChatMemoryService chatMemoryService) {
        this.dataService = dataService;
        this.userDataService = userDataService;
        this.openAIClient = openAIClient;
        this.chartService = chartService;
        this.chatMemoryService = chatMemoryService;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Answer a user question about device data.
     * Takes an optional userToken for per-user device scoping.
     */
    public ChatResponse answerQuestion(ChatRequest request, String userToken) {
        long startTime = System.currentTimeMillis();
        log.info("📩 Processing question: '{}' (user token: {})",
                request.getQuestion(), userToken != null ? "present" : "absent");

        try {
            // Step 3.5: Fetch Session History (Moved UP so filter can use it)
            String sessionId = (userToken != null && !userToken.isBlank()) ? userToken : "default-session";
            List<com.seple.ThingsBoard_Bot.model.dto.ChatMessage> history = chatMemoryService.getHistory(sessionId);

            // Step 1: Get device data (from user cache or fallback to default cache)
            Map<String, Object> rawData;
            if (userToken != null && !userToken.isBlank()) {
                List<Map<String, Object>> allDevices = userDataService.getUserDevicesData(userToken);
                rawData = filterDevicesForQuestion(allDevices, request.getQuestion(), history);
            } else {
                rawData = dataService.getDeviceData();
            }
            log.debug("Got {} raw data keys", rawData.size());

            // Step 2: Filter context to reduce tokens
            Map<String, Object> filteredData = ContextFilterUtil.filterAttributes(rawData);
            log.debug("Filtered to {} keys", filteredData.size());

            // Step 3: Convert to JSON string for the prompt
            String contextJson = objectMapper.writeValueAsString(filteredData);

            // Step 4: Count tokens and validate (with history)
            int totalTokens = TokenCounterService.countMessageTokens(
                    SYSTEM_PROMPT, history, request.getQuestion(), contextJson);

            // If we exceed context block, discard history one by one until it fits
            while (!TokenCounterService.fitsInContextWindow(totalTokens) && !history.isEmpty()) {
                chatMemoryService.removeOldestMessage(sessionId);
                history = chatMemoryService.getHistory(sessionId);
                totalTokens = TokenCounterService.countMessageTokens(
                        SYSTEM_PROMPT, history, request.getQuestion(), contextJson);
            }

            if (!TokenCounterService.fitsInContextWindow(totalTokens)) {
                throw new ContextOverflowException(
                        "Context too large: " + totalTokens + " tokens (max 6000). Try asking about a specific device.");
            }

            // Step 5: Build the user message with context
            String userMessage = "Device Data Context:\n" + contextJson
                    + "\n\nUser Question: " + request.getQuestion();

            // Step 6: Call OpenAI with Context + History + New Question
            String answer = openAIClient.chat(SYSTEM_PROMPT, history, userMessage);

            // Record this interaction into the sliding window memory
            chatMemoryService.recordInteraction(sessionId, request.getQuestion(), answer);

            // Step 7: Generate chart if requested
            ChartData chartData = null;
            if (Boolean.TRUE.equals(request.getIncludeChart()) || isChartRequest(request.getQuestion())) {
                String chartKey = detectChartKey(request.getQuestion(), filteredData);
                if (chartKey != null) {
                    String actualKey = chartKey;
                    String deviceId = null;

                    // If userToken is present and we have multiple devices, the key might be prefixed
                    if (userToken != null && !userToken.isBlank()) {
                        if (chartKey.contains(".")) {
                            String deviceName = chartKey.substring(0, chartKey.lastIndexOf("."));
                            actualKey = chartKey.substring(chartKey.lastIndexOf(".") + 1);
                            Object devIdObj = filteredData.get(deviceName + ".device_id");
                            if (devIdObj != null) deviceId = devIdObj.toString();
                        } else {
                            Object devIdObj = filteredData.get("device_id");
                            if (devIdObj != null) deviceId = devIdObj.toString();
                        }
                    }

                    chartData = chartService.generateChartData(userToken, deviceId, actualKey);
                    log.info("📊 Chart generated for actual key: {}", actualKey);
                }
            }

            long duration = System.currentTimeMillis() - startTime;
            log.info("✅ Question answered in {}ms ({} tokens)", duration, totalTokens);

            // Build a small summary context for the response
            Map<String, Object> summaryContext = new LinkedHashMap<>();
            // Only add basic overview items, not full arrays which clutters UI
            for (Map.Entry<String, Object> entry : filteredData.entrySet()) {
                if (entry.getKey().toLowerCase().contains("name") || entry.getKey().toLowerCase().contains("status")) {
                    summaryContext.put(entry.getKey(), entry.getValue());
                }
            }
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

    /**
     * Filters a list of user devices based on whether the device name 
     * is mentioned in the current question AND/OR previous conversational history.
     */
    private Map<String, Object> filterDevicesForQuestion(List<Map<String, Object>> allDevices, String question, List<com.seple.ThingsBoard_Bot.model.dto.ChatMessage> history) {
        if (allDevices == null || allDevices.isEmpty()) {
            return new HashMap<>();
        }
        
        // Build a combined 'contextual text' wall from the current question + past questions
        StringBuilder contextText = new StringBuilder();
        if (question != null) {
            contextText.append(question.toLowerCase()).append(" ");
        }
        if (history != null) {
            for (com.seple.ThingsBoard_Bot.model.dto.ChatMessage msg : history) {
                // Only scan user questions, not bot responses (in case bot listed all devices)
                if ("user".equalsIgnoreCase(msg.getRole()) && msg.getContent() != null) {
                    contextText.append(msg.getContent().toLowerCase()).append(" ");
                }
            }
        }
        String qLower = contextText.toString();
        
        List<Map<String, Object>> matchedDevices = new ArrayList<>();
        
        for (Map<String, Object> dev : allDevices) {
            String name = (String) dev.getOrDefault("device_name", "");
            if (!name.isEmpty()) {
                String nameLower = name.toLowerCase();
                String strippedName = nameLower.contains("-") ? nameLower.substring(nameLower.indexOf("-") + 1) : nameLower;
                
                if (qLower.contains(nameLower) || (strippedName.length() >= 3 && qLower.contains(strippedName))) {
                    matchedDevices.add(dev);
                }
            }
        }
        
        Map<String, Object> flat = new java.util.HashMap<>();
        
        // If matched specific devices, use those.
        // If no match but less than 3 devices total, use all (small enough context).
        // Otherwise, send a summary to force the user to specify to prevent token limits.
        if (!matchedDevices.isEmpty()) {
            flat = flattenDeviceList(matchedDevices);
        } else if (allDevices.size() <= 2) {
            flat = flattenDeviceList(allDevices);
        } else {
            flat.put("SYSTEM_NOTE", "CRITICAL INSTRUCTION: There are too many devices (" + allDevices.size() + ") to show at once, and the user didn't specify one. You MUST reply by asking the user to specify which device they want to check (for example, refer to the available_devices_for_user_to_choose_from list). Do NOT say you don't have access to the data.");
            List<String> names = new ArrayList<>();
            for (Map<String, Object> dev : allDevices) {
                names.add((String) dev.getOrDefault("device_name", "Unknown"));
            }
            flat.put("available_devices_for_user_to_choose_from", names);
        }
        
        return flat;
    }

    /**
     * Flattens a list of devices into a single map context.
     * Prefixes properties with device names if there are multiple devices.
     */
    private Map<String, Object> flattenDeviceList(List<Map<String, Object>> devices) {
        Map<String, Object> flat = new java.util.HashMap<>();
        if (devices.size() == 1) {
            flat.putAll(devices.get(0));
        } else {
            flat.put("total_devices_in_context", devices.size());
            for (Map<String, Object> deviceData : devices) {
                String name = deviceData.getOrDefault("device_name", "unknown").toString();
                for (Map.Entry<String, Object> entry : deviceData.entrySet()) {
                    flat.put(name + "." + entry.getKey(), entry.getValue());
                }
            }
        }
        return flat;
    }
}
