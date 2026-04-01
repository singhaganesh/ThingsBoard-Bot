package com.seple.ThingsBoard_Bot.service;

import java.util.*;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seple.ThingsBoard_Bot.client.OpenAIClient;
import com.seple.ThingsBoard_Bot.exception.ContextOverflowException;
import com.seple.ThingsBoard_Bot.model.dto.ChatRequest;
import com.seple.ThingsBoard_Bot.model.dto.ChatResponse;
import com.seple.ThingsBoard_Bot.util.ContextFilterUtil;
import com.seple.ThingsBoard_Bot.util.TokenCounterService;
import lombok.extern.slf4j.Slf4j;

/**
 * Senior Security Analyst (SAI) - Final Merged Architecture.
 * Harmonizes strict Q&A templates with absolute counting accuracy.
 */
@Slf4j
@Service
public class ChatService {

    private final DataService dataService;
    private final UserDataService userDataService;
    private final OpenAIClient openAIClient;
    private final ChatMemoryService chatMemoryService;
    private final ObjectMapper objectMapper;

    private static final String SYSTEM_PROMPT = """
            You are SAI (Smart Assistant for IoT), a Senior Security Analyst for bank branch monitoring.
            You MUST follow these rules exactly to match the Branch System QA Guide.
            
            ========================================
            CORE TERMINOLOGY RULES
            ========================================
            1. NEVER use the word "Device" for a branch. ALWAYS use "Branch" (e.g., "Branch BALLY BAZAR").
            2. For global overviews, the FIRST LINE must be: **Total: [X] Online | [Y] Offline**
            
            ========================================
            MANDATORY RESPONSE BLUEPRINTS
            ========================================
            
            1. GLOBAL OVERVIEW ("What branches", "What devices", "List all"):
               **Total: [X] Online | [Y] Offline**
               
               Online:
               - [Branch Name 1]
               - [Branch Name 2]
               
               Offline:
               - [Branch Name 3]
               
               *Based on gateway connectivity from SYSTEM_NOTE.*
               
            2. GATEWAY STATUS:
               **The Branch Gateway status is currently [ONLINE/OFFLINE]. All systems are operational.**
               
            3. VOLTAGE/METRICS:
               **[Metric Name]: [Value][V DC/V AC/Amp].** (e.g. Battery Voltage Reading: 13.6V DC)
               
            4. ACTIVE DEVICES (Specific Branch):
               **Active Devices ([Count]): CCTV DVR, IAS Panel... All responding normally.**
               
            5. CCTV SYSTEM:
               **CCTV Camera Status: [X] cameras are ONLINE.**
               
            6. SUB-SYSTEM POWER/ALARM:
               **[System Name] [Metric] Status: [ON/OFF/NORMAL/ALARM]. [Context].**
            
            ========================================
            REASONING & ACCURACY
            ========================================
            - N/A POLICY: Report "N/A" as "Offline" or "Not Installed". NEVER Online.
            - SYSTEM_NOTE: Follow the counts and device lists in SYSTEM_NOTE exactly.
            - NO FLUFF: No greetings, no "Here is your report". Start with the bold summary.
            """;

    public ChatService(DataService dataService, UserDataService userDataService, OpenAIClient openAIClient, ChartService chartService, ChatMemoryService chatMemoryService) {
        this.dataService = dataService;
        this.userDataService = userDataService;
        this.openAIClient = openAIClient;
        this.chatMemoryService = chatMemoryService;
        this.objectMapper = new ObjectMapper();
    }

    public ChatResponse answerQuestion(ChatRequest request, String userToken) {
        try {
            String sessionId = (userToken != null) ? userToken : "default-session";
            List<com.seple.ThingsBoard_Bot.model.dto.ChatMessage> history = chatMemoryService.getHistory(sessionId);

            if (userToken == null || userToken.isBlank()) {
                return ChatResponse.builder().answer("Please log in first.").error(true).build();
            }

            List<Map<String, Object>> allDevices = userDataService.getUserDevicesData(userToken);
            Map<String, Object> rawData = filterDevicesForQuestion(allDevices, request.getQuestion(), history, sessionId);
            Map<String, Object> filteredData = ContextFilterUtil.filterAttributes(rawData);
            
            String contextJson = objectMapper.writeValueAsString(filteredData);
            String userMessage = "Device Data Context:\n" + contextJson + "\n\nUser Question: " + request.getQuestion();
            String answer = openAIClient.chat(SYSTEM_PROMPT, history, userMessage);
            chatMemoryService.recordInteraction(sessionId, request.getQuestion(), answer);

            return ChatResponse.builder().answer(answer).tokensUsed(TokenCounterService.countMessageTokens(SYSTEM_PROMPT, history, request.getQuestion(), contextJson)).timestamp(System.currentTimeMillis()).error(false).build();

        } catch (Exception e) {
            log.error("❌ Error: {}", e.getMessage(), e);
            return ChatResponse.builder().answer("System error encountered.").error(true).build();
        }
    }

    private Map<String, Object> filterDevicesForQuestion(List<Map<String, Object>> allDevices, String question, List<com.seple.ThingsBoard_Bot.model.dto.ChatMessage> history, String sessionId) {
        if (allDevices == null || allDevices.isEmpty()) return new HashMap<>();
        
        String qLower = (question != null ? question.toLowerCase() : "");
        
        // ACCURACY FIX: Added "devices", "have", "total" to global triggers
        boolean isGlobal = qLower.contains("all") || qLower.contains("any") || qLower.contains("list") || qLower.contains("total") || qLower.contains("branches") || qLower.contains("devices") || qLower.contains("have");

        Map<String, Object> flat;
        if (isGlobal) {
            flat = flattenDeviceSummary(allDevices);
            injectGlobalSystemNote(allDevices, flat);
        } else {
            List<Map<String, Object>> matched = new ArrayList<>();
            for (Map<String, Object> dev : allDevices) {
                String name = getBestName(dev).toLowerCase();
                String technicalName = String.valueOf(dev.getOrDefault("device_name", "")).toLowerCase();
                if (qLower.contains(name) || qLower.contains(technicalName)) {
                    matched.add(dev);
                }
            }
            flat = matched.isEmpty() ? flattenDeviceSummary(allDevices) : flattenDeviceList(matched);
            if (!matched.isEmpty()) injectBranchSystemNote(matched.get(0), flat);
        }
        return flat;
    }

    private void injectGlobalSystemNote(List<Map<String, Object>> devices, Map<String, Object> flat) {
        List<String> online = new ArrayList<>();
        List<String> offline = new ArrayList<>();
        for (Map<String, Object> dev : devices) {
            String name = getBestName(dev);
            String g = String.valueOf(dev.getOrDefault("gateway", "N/A"));
            if ("Online".equalsIgnoreCase(g) || "On".equalsIgnoreCase(g)) online.add(name); else offline.add(name);
        }
        flat.put("SYSTEM_NOTE", String.format("MANDATORY: Global Overview. Total: %d Online | %d Offline. Online Branches: %s. Offline Branches: %s.", 
                online.size(), offline.size(), String.join(", ", online), String.join(", ", offline)));
    }

    private void injectBranchSystemNote(Map<String, Object> dev, Map<String, Object> flat) {
        List<String> active = new ArrayList<>();
        if ("Online".equalsIgnoreCase(String.valueOf(dev.get("cctv")))) active.add("CCTV DVR");
        if ("Online".equalsIgnoreCase(String.valueOf(dev.get("ias")))) active.add("IAS Panel");
        if ("Online".equalsIgnoreCase(String.valueOf(dev.get("fas")))) active.add("FAS Panel");
        if ("Online".equalsIgnoreCase(String.valueOf(dev.get("accessControl")))) active.add("Access Control Controller");
        
        flat.put("SYSTEM_NOTE", String.format("MANDATORY: Branch: %s. Active Devices (%d): %s. Offline: %s.", 
                getBestName(dev), active.size(), String.join(", ", active), 
                active.size() < 4 ? "Some components are offline" : "None"));
    }

    private String getBestName(Map<String, Object> dev) {
        String name = String.valueOf(dev.getOrDefault("branchName", dev.getOrDefault("device_name", "Unknown")));
        return name.replace("BOI-", "").replace("BRANCH ", "").trim().toUpperCase();
    }

    private Map<String, Object> flattenDeviceList(List<Map<String, Object>> devices) {
        Map<String, Object> flat = new HashMap<>();
        for (Map<String, Object> dev : devices) {
            String name = getBestName(dev);
            for (Map.Entry<String, Object> e : dev.entrySet()) flat.put(name + "." + e.getKey(), e.getValue());
        }
        return flat;
    }

    private Map<String, Object> flattenDeviceSummary(List<Map<String, Object>> devices) {
        Map<String, Object> summary = new HashMap<>();
        for (Map<String, Object> dev : devices) {
            String name = getBestName(dev);
            summary.put(name + ".gateway", dev.get("gateway"));
            summary.put(name + ".status", dev.get("status"));
        }
        return summary;
    }

    public void initializeUserCache(String userToken) {
        if (userToken != null) userDataService.getUserDevicesData(userToken);
    }
}
