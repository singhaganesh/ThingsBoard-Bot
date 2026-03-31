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
 * Senior Security Analyst (SAI) - Optimized for exact Q&A Guide adherence.
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
            You are SAI (Smart Assistant for IoT), a Senior Security Analyst.
            Your role is to provide structured, professional answers following the MANDATORY FORMATS below.
            
            ========================================
            MANDATORY RESPONSE TEMPLATES
            ========================================
            
            1. GATEWAY STATUS:
               **The Branch Gateway status is currently [ONLINE/OFFLINE]. [Details].**
               
            2. BATTERY/AC VOLTAGE:
               **[Metric Name]: [Value] [V DC/V AC].** (Example: Battery Voltage Reading: 13.6V DC)
               
            3. ACTIVE/OFFLINE DEVICES:
               **[Category] ([Count]): [Device 1], [Device 2]... [Status Description].**
               
            4. CCTV CAMERA STATUS:
               **CCTV Camera Status: [Count] cameras are ONLINE.**
               
            5. CCTV HDD:
               **CCTV HDD Status: [HEALTHY/ERROR]. HDD Slot: [Slot], Capacity: [Size], Used: [Used], Free: [Free].**
               
            6. SUB-SYSTEM POWER/ALARM (IAS, BAS, FAS):
               **[System Name] [Power/Alarm] Status: [ON/OFF/NORMAL/ALARM]. [Contextual Note].**
               
            7. TIME LOCK / ACCESS CONTROL:
               **[System Name] [Metric] Status: [Status]. [Actionable Advice].**
            
            ========================================
            ANALYTICAL RULES
            ========================================
            - CORRELATION: Link Mains Power status to Battery health.
            - N/A POLICY: If a value is "N/A", report as "Not Installed" or "Offline". NEVER Online.
            - ACCURACY: Follow the count and list in "SYSTEM_NOTE" exactly.
            - NAMES: Use the Branch Name (e.g. BALLY BAZAR).
            
            ========================================
            MANDATORY OUTPUT STYLE
            ========================================
            - Start with a **Bold Summary Header**.
            - Use bullet points for metrics.
            - *Italicize data fields used at the bottom.*
            - NO conversational filler.
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
        boolean isGlobal = qLower.contains("all") || qLower.contains("any") || qLower.contains("list") || qLower.contains("total") || qLower.contains("branches");

        Map<String, Object> flat;
        if (isGlobal) {
            flat = flattenDeviceSummary(allDevices);
            injectGlobalSystemNote(allDevices, flat);
        } else {
            List<Map<String, Object>> matched = new ArrayList<>();
            for (Map<String, Object> dev : allDevices) {
                String name = getBestName(dev).toLowerCase();
                String technicalName = String.valueOf(dev.getOrDefault("device_name", "")).toLowerCase();
                if (qLower.contains(name) || qLower.contains(technicalName) || (name.length() > 3 && technicalName.contains(name))) {
                    matched.add(dev);
                }
            }
            flat = matched.isEmpty() ? flattenDeviceSummary(allDevices) : flattenDeviceList(matched);
            if (!matched.isEmpty()) injectBranchSystemNote(matched.get(0), flat);
        }
        return flat;
    }

    private void injectGlobalSystemNote(List<Map<String, Object>> devices, Map<String, Object> flat) {
        int online = 0, offline = 0;
        for (Map<String, Object> dev : devices) {
            String g = String.valueOf(dev.getOrDefault("gateway", "N/A"));
            if ("Online".equalsIgnoreCase(g) || "On".equalsIgnoreCase(g)) online++; else offline++;
        }
        flat.put("SYSTEM_NOTE", String.format("MANDATORY: Global Stats. Total: %d Online | %d Offline.", online, offline));
    }

    private void injectBranchSystemNote(Map<String, Object> dev, Map<String, Object> flat) {
        List<String> active = new ArrayList<>();
        if ("Online".equalsIgnoreCase(String.valueOf(dev.get("cctv")))) active.add("CCTV DVR");
        if ("Online".equalsIgnoreCase(String.valueOf(dev.get("ias")))) active.add("IAS Panel");
        if ("Online".equalsIgnoreCase(String.valueOf(dev.get("fas")))) active.add("FAS Panel");
        if ("Online".equalsIgnoreCase(String.valueOf(dev.get("accessControl")))) active.add("Access Control Controller");
        
        String battery = String.valueOf(dev.getOrDefault("battery_voltage", "N/A"));
        String ac = String.valueOf(dev.getOrDefault("ac_voltage", "N/A"));
        
        flat.put("SYSTEM_NOTE", String.format("MANDATORY: Branch: %s. Active Devices (%d): %s. Offline: %s. Battery: %sV, AC: %sV.", 
                getBestName(dev), active.size(), String.join(", ", active), 
                active.size() < 4 ? "Some system sub-components may be offline" : "None",
                battery, ac));
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
