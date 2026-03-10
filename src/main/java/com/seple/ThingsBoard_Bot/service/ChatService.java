package com.seple.ThingsBoard_Bot.service;

import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.text.SimpleDateFormat;
import java.util.TimeZone;

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
            - CRITICAL RULE FOR ONLINE STATUS: A branch or device is ONLY "Online" or "Active" if its `gateway` or `gwStatus` value is "Online" or "On". If `gateway`/`gwStatus` is "Offline" or missing, the branch is OFFLINE and INACTIVE. Do NOT claim it is active just because individual subsystems (like `integratedStatus` or `accessControl`) report "Healthy" or "true", as those are likely stale offline readings!
            - When answering questions about "how many" or "which" devices are inactive using summarized data, count ONLY the devices that have a `gwStatus` or `gateway` property explicitly stating they are offline/inactive. If a device has `[deviceName].gwStatus = Offline`, it is inactive.
            
            OUTPUT FORMAT:
            - When counting devices: "X out of Y devices are [status]"
            - List specific device/branch names with their status
            - Always cite the actual values from the data
            - If uncertain, say "Based on the data provided..."
            - For status questions, provide a clear yes/no or list of statuses
            
            EXAMPLES:
            - User: "How many branches are offline?"
              Context: BOI-DANKUNI.gateway=Online, BOI-CHANDANNAGAR.gateway=Offline, BOI-ARAMBAGH.gateway=Offline
              Bot: "2 out of 3 branches are offline: BOI-CHANDANNAGAR and BOI-ARAMBAGH"
            
            - User: "Show me battery status"
              Context: BOI-DANKUNI.battery_status=OK, BOI-CHANDANNAGAR.battery_status=Low
              Bot: "Battery Status:\n- BOI-DANKUNI: OK\n- BOI-CHANDANNAGAR: Low (needs attention)"
            
            - User: "Which devices have alarms?"
              Context: BOI-DANKUNI.alarmCount=0, BOI-CHANDANNAGAR.alarmCount=3
              Bot: "1 device has active alarms: BOI-CHANDANNAGAR (3 alarms)"
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
                rawData = filterDevicesForQuestion(allDevices, request.getQuestion(), history, sessionId);
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
            // Add query-type specific instructions to guide the AI
            String queryInstruction = detectQueryInstructions(request.getQuestion(), filteredData);
            String userMessage = "Device Data Context:\n" + contextJson
                    + "\n\n" + queryInstruction
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
     * Detect query type and add specific instructions to guide the AI response.
     * This helps ensure accurate answers for different types of questions.
     */
    private String detectQueryInstructions(String question, Map<String, Object> data) {
        if (question == null || question.isBlank()) {
            return "";
        }
        
        String q = question.toLowerCase();
        StringBuilder instructions = new StringBuilder();
        
        // Count queries - focus on gateway status
        if (q.contains("how many") || q.contains("count") || q.contains("number of")) {
            if (q.contains("offline") || q.contains("inactive") || q.contains("not working") || q.contains("down")) {
                instructions.append("\nCRITICAL: Count only devices where gateway='Offline' or gwStatus='Offline'. ");
                instructions.append("Do NOT count devices as offline just because subsystems show issues. ");
                instructions.append("The gateway status is the definitive indicator. ");
            } else if (q.contains("online") || q.contains("active") || q.contains("working")) {
                instructions.append("\nCRITICAL: Count only devices where gateway='Online' or gwStatus='Online'. ");
            }
        }
        
        // Which queries - list specific devices
        if (q.contains("which") || q.contains("list")) {
            if (q.contains("offline") || q.contains("inactive") || q.contains("not working")) {
                instructions.append("\nList the specific device/branch names that have gateway='Offline' or gwStatus='Offline'. ");
            } else if (q.contains("battery") || q.contains("alarm")) {
                instructions.append("\nList each device with its specific value (e.g., 'BOI-DANKUNI: OK, BOI-CHANDANNAGAR: Low'). ");
            }
        }
        
        // Status queries - provide clear yes/no
        if (q.contains("is there") || q.contains("are there") || q.contains("does") || q.contains("do any")) {
            instructions.append("\nAnswer with a clear yes/no followed by specific examples if applicable. ");
        }
        
        // Battery/power queries
        if (q.contains("battery") || q.contains("power") || q.contains("mains")) {
            instructions.append("\nHighlight any devices with 'Low', 'Critical', or 'Reverse' battery status. ");
        }
        
        // Alarm queries
        if (q.contains("alarm") || q.contains("alert")) {
            instructions.append("\nList devices with alarmCount > 0 and mention the alarm count. ");
        }
        
        return instructions.toString();
    }

    /**
     * Filters a list of user devices based on whether the device name 
     * is mentioned in the current question AND/OR previous conversational history.
     */
    private Map<String, Object> filterDevicesForQuestion(List<Map<String, Object>> allDevices, String question, List<com.seple.ThingsBoard_Bot.model.dto.ChatMessage> history, String sessionId) {
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
                // Scan both user questions AND bot responses. 
                // If the bot says "CHINSURAH is inactive", we want to capture CHINSURAH as the active context!
                if (msg.getContent() != null) {
                    contextText.append(msg.getContent().toLowerCase()).append(" ");
                }
            }
        }
        String qLower = contextText.toString();
        
        List<Map<String, Object>> matchedDevices = new ArrayList<>();
        
        for (Map<String, Object> dev : allDevices) {
            String name = (String) dev.getOrDefault("device_name", "");
            // Also check branchName and customerTitle for matching
            String branchName = (String) dev.getOrDefault("branchName", "");
            String customerTitle = (String) dev.getOrDefault("customerTitle", "");
            
            if (!name.isEmpty()) {
                String nameLower = name.toLowerCase();
                String strippedName = nameLower.contains("-") ? nameLower.substring(nameLower.indexOf("-") + 1) : nameLower;
                String branchLower = branchName.toLowerCase();
                String customerLower = customerTitle.toLowerCase();
                
                // Match against device name, stripped name (after -), branch name, or customer title
                if (qLower.contains(nameLower) || (strippedName.length() >= 3 && qLower.contains(strippedName))
                    || (!branchLower.isEmpty() && qLower.contains(branchLower))
                    || (!customerLower.isEmpty() && qLower.contains(customerLower))) {
                    matchedDevices.add(dev);
                }
            }
        }
        
        Map<String, Object> flat = new HashMap<>();
        
        boolean isGlobalQuery = false;
        if (question != null) {
            String currentQ = question.toLowerCase();
            isGlobalQuery = currentQ.contains("all") || currentQ.contains("any") 
                            || currentQ.contains("which") || currentQ.contains("my devices")
                            || currentQ.contains("what") || currentQ.contains("how many")
                            || currentQ.contains("list") || currentQ.contains("overview");
        }
        
        // 1. If it's a generic overview query, send a highly summarized device list.
        // This overrides any specific matched devices because the user is zooming out.
        if (isGlobalQuery) {
            chatMemoryService.setActiveDevices(sessionId, new ArrayList<>()); // clear active zoom
            flat = flattenDeviceSummary(allDevices);
            flat.put("SYSTEM_NOTE", "The user asked a general/overview question across all their devices. A summary of all devices is provided. Answer based on this data. For questions about specific device details, please ask about a specific device name.");
        } 
        // 2. If matched specific devices, tightly bound how many we expand.
        else if (!matchedDevices.isEmpty()) {
            List<String> activeNames = new ArrayList<>();
            for (Map<String, Object> md : matchedDevices) {
                activeNames.add((String) md.getOrDefault("device_name", ""));
            }
            chatMemoryService.setActiveDevices(sessionId, activeNames);
            
            // ALWAYS use full data for specific device queries (1-5 matched)
            // For larger sets, use summary to prevent token overflow
            if (matchedDevices.size() <= 5) {
                flat = flattenDeviceList(matchedDevices); // Safe to fully expand
            } else {
                // Too many to expand safely - use summary
                flat = flattenDeviceSummary(matchedDevices);
                flat.put("SYSTEM_NOTE", "Multiple devices matched the context (" + matchedDevices.size() + "). A lightweight summary of these devices is provided to save tokens. If detailed telemetry is needed for a specific device, ask the user to specify just one.");
            }
        } 
        // 3. If no match but less than 6 devices total, use all (small enough context).
        else if (allDevices.size() <= 5) {
            flat = flattenDeviceList(allDevices);
        } 
        // 4. Fallback: Check if we have active devices stored in the session memory
        else {
            List<String> activeSessionDevices = chatMemoryService.getActiveDevices(sessionId);
            if (!activeSessionDevices.isEmpty()) {
                List<Map<String, Object>> sessionMatched = new ArrayList<>();
                for (Map<String, Object> dev : allDevices) {
                    if (activeSessionDevices.contains((String) dev.getOrDefault("device_name", ""))) {
                        sessionMatched.add(dev);
                    }
                }
                if (!sessionMatched.isEmpty()) {
                    if (sessionMatched.size() <= 5) {
                        flat = flattenDeviceList(sessionMatched);
                    } else {
                        flat = flattenDeviceSummary(sessionMatched);
                        flat.put("SYSTEM_NOTE", "Multiple devices matched the context from previous session (" + sessionMatched.size() + "). A lightweight summary is provided to save tokens. Ask the user to specify one if they need full telemetry.");
                    }
                    return flat;
                }
            }

            // 5. Still no match? Force the user to specify.
            flat.put("SYSTEM_NOTE", "CRITICAL INSTRUCTION: There are too many devices (" + allDevices.size() + ") to show full telemetry for at once, and the user didn't specify one. You MUST reply by asking the user to specify which device they want to check (for example, refer to the available_devices_for_user_to_choose_from list). Do NOT say you don't have access to the data.");
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
        Map<String, Object> flat = new HashMap<>();
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

    /**
     * Creates a lightweight summary of all devices for global questions (battery, status, alarms)
     * without exceeding LLM token limits when a user has many devices.
     */
    private Map<String, Object> flattenDeviceSummary(List<Map<String, Object>> devices) {
        Map<String, Object> summary = new HashMap<>();
        summary.put("total_devices", devices.size());
        for (Map<String, Object> dev : devices) {
            String name = dev.getOrDefault("device_name", "unknown").toString();
            if (dev.containsKey("battery_status")) summary.put(name + ".battery", dev.get("battery_status"));
            if (dev.containsKey("status")) summary.put(name + ".status", dev.get("status"));
            if (dev.containsKey("active")) summary.put(name + ".active", dev.get("active"));
            if (dev.containsKey("alarmCount")) summary.put(name + ".alarms", dev.get("alarmCount"));
            if (dev.containsKey("temperature")) summary.put(name + ".temp", dev.get("temperature"));
            if (dev.containsKey("branchName")) summary.put(name + ".branch", dev.get("branchName"));
            if (dev.containsKey("gwStatus")) summary.put(name + ".gwStatus", dev.get("gwStatus"));
            if (dev.containsKey("gwHealth")) summary.put(name + ".gwHealth", dev.get("gwHealth"));
            if (dev.containsKey("gateway")) summary.put(name + ".gateway", dev.get("gateway"));
            if (dev.containsKey("gatewayStatus")) summary.put(name + ".gatewayStatus", dev.get("gatewayStatus"));
            if (dev.containsKey("iasStatus")) summary.put(name + ".iasStatus", dev.get("iasStatus"));
            if (dev.containsKey("fasStatus")) summary.put(name + ".fasStatus", dev.get("fasStatus"));
            if (dev.containsKey("basStatus")) summary.put(name + ".basStatus", dev.get("basStatus"));
            if (dev.containsKey("cctvStatus")) summary.put(name + ".cctvStatus", dev.get("cctvStatus"));
            if (dev.containsKey("cameraStatus")) summary.put(name + ".cameraStatus", dev.get("cameraStatus"));
            if (dev.containsKey("integratedStatus")) summary.put(name + ".integratedStatus", dev.get("integratedStatus"));
            if (dev.containsKey("accessControlStatus")) summary.put(name + ".accessControlStatus", dev.get("accessControlStatus"));
            if (dev.containsKey("timeLockHealth")) summary.put(name + ".timeLockHealth", dev.get("timeLockHealth"));
            
            // Uptime & Activity
            if (dev.containsKey("uptimeTotal")) summary.put(name + ".uptimeTotal", dev.get("uptimeTotal"));
            if (dev.containsKey("uptimeHeartbeat")) summary.put(name + ".uptimeHeartbeat", dev.get("uptimeHeartbeat"));
            if (dev.containsKey("inactivityAlarmTime")) {
                Object inactTime = dev.get("inactivityAlarmTime");
                summary.put(name + ".inactivityAlarmTime", formatTimestamp(inactTime));
            }
            
            // Hardware Stats
            if (dev.containsKey("cpu")) summary.put(name + ".cpu", dev.get("cpu"));
            if (dev.containsKey("memory")) summary.put(name + ".memory", dev.get("memory"));
            if (dev.containsKey("disk")) summary.put(name + ".disk", dev.get("disk"));
            if (dev.containsKey("temperature")) summary.put(name + ".temperature", dev.get("temperature"));
            
            // Power
            if (dev.containsKey("ac_status")) summary.put(name + ".ac_status", dev.get("ac_status"));
            if (dev.containsKey("POWER OFF")) summary.put(name + ".powerOff", dev.get("POWER OFF"));
            if (dev.containsKey("SYSTEM ON")) summary.put(name + ".systemOn", dev.get("SYSTEM ON"));
            if (dev.containsKey("MAINS ON")) summary.put(name + ".mainsOn", dev.get("MAINS ON"));
            if (dev.containsKey("BATTERY LOW")) summary.put(name + ".batteryLow", dev.get("BATTERY LOW"));
            
            // Network
            if (dev.containsKey("NETWORK")) summary.put(name + ".network", dev.get("NETWORK"));
            
            // Error counts
            if (dev.containsKey("errorCount")) summary.put(name + ".errorCount", dev.get("errorCount"));
            if (dev.containsKey("IASinactiveCOUNT")) summary.put(name + ".iasInactiveCount", dev.get("IASinactiveCOUNT"));
            if (dev.containsKey("IASfaultCOUNT")) summary.put(name + ".iasFaultCount", dev.get("IASfaultCOUNT"));
            
            // Format timestamps to human-readable format
            if (dev.containsKey("lastActivityTime")) {
                Object activityTime = dev.get("lastActivityTime");
                summary.put(name + ".lastActivityTime", formatTimestamp(activityTime));
            }
            if (dev.containsKey("lastConnectTime")) {
                Object connectTime = dev.get("lastConnectTime");
                summary.put(name + ".lastConnectTime", formatTimestamp(connectTime));
            }
            if (dev.containsKey("lastDisconnectTime")) {
                Object disconnectTime = dev.get("lastDisconnectTime");
                summary.put(name + ".lastDisconnectTime", formatTimestamp(disconnectTime));
            }
        }
        return summary;
    }
    
    /**
     * Convert epoch milliseconds to human-readable date/time format.
     * Returns the original value if it's not a valid epoch timestamp.
     */
    private Object formatTimestamp(Object value) {
        if (value == null) {
            return null;
        }
        
        try {
            // Try to parse as Long (epoch milliseconds)
            long timestamp;
            if (value instanceof Number) {
                timestamp = ((Number) value).longValue();
            } else {
                String strValue = value.toString().trim();
                // Check if it looks like an epoch timestamp (13+ digits for milliseconds)
                if (!strValue.matches("\\d{13,}")) {
                    return value; // Not a timestamp
                }
                timestamp = Long.parseLong(strValue);
            }
            
            // Validate it's a reasonable timestamp (between 2000 and 2100)
            if (timestamp < 946684800000L || timestamp > 4102444800000L) {
                return value; // Outside reasonable range
            }
            
            // Format to human-readable
            SimpleDateFormat sdf = new SimpleDateFormat("dd MMM yyyy, HH:mm:ss");
            sdf.setTimeZone(TimeZone.getTimeZone("IST")); // India Standard Time
            return sdf.format(new java.util.Date(timestamp));
        } catch (Exception e) {
            log.debug("Failed to format timestamp: {}", value, e);
            return value; // Return original on any error
        }
    }
}
