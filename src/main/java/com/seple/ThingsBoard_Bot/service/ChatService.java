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
            MANDATORY OUTPUT FORMAT:
            1. GLOBAL QUERIES (e.g. "which branches are online"): START with a **Bold Summary Header**: **Total: [X] Online | [Y] Offline**.
            2. SPECIFIC QUERIES (e.g. "status of branch X"): START with a **Bold Summary Line**: **[Branch Name]: [Status]**.
            3. USE LISTS: If listing more than one item, ALWAYS use a bulleted list ('-').
            4. TERMINOLOGY: Always use "Branch" instead of "Device".
            5. NAMES: Use the provided name from the context. Each name must appear exactly ONCE in your response.
            6. FOLLOW with a short reason in *Italics* citing the context data used.
            7. NO FLUFF: Skip introductory phrases and polite closings.

            ACCURACY & ACCOUNTABILITY:
            - SYSTEM_NOTE: ALWAYS follow the instructions in the SYSTEM_NOTE provided in the context.
            - ZERO OMISSION: You must account for EVERY branch provided in the context.
            - INDEPENDENT STATUS: Every sub-system (CCTV, IAS, etc.) has its own value. 
            - CRITICAL: A sub-system can be "Online" even if the Branch Gateway is "Offline". Report the individual value exactly as it appears.
            - OFFLINE DEFINITION (BRANCH): A branch is OFFLINE only if its `gateway` status is "Offline", "Fault", "N/A", or "Inactive".
            - ONLINE DEFINITION (BRANCH): A branch is ONLINE only if its `gateway` is "Online" or "On".
            - DOUBLE-CHECK MATH: Physically count every unique branch name in your list before writing the summary header.

            STRICT PRIVACY:
            - NEVER provide a broad overview or "full info" of a branch unless a category is specified.
            - CATEGORY PRIORITY: If a category (CCTV, IAS, FAS, etc.) is mentioned, answer immediately using its individual value.

            SUB-DEVICE INDICATORS (THE TRUTH):
            Use ONLY these attributes for sub-system status:
            1. CCTV: `cctv`
            2. Integrated Alarm System (IAS): `ias`
            3. Burglar Alarm System (BAS): `bas`
            4. Fire Alarm System (FAS): `fas`
            5. Time Lock System (TLS): `timeLock`
            6. Access Control System (ACS): `accessControl`
            Values MUST be reported exactly as: "Online", "Offline", or "N/A".
            """;

    public ChatService(DataService dataService, UserDataService userDataService, OpenAIClient openAIClient, ChartService chartService, ChatMemoryService chatMemoryService) {
        this.dataService = dataService;
        this.userDataService = userDataService;
        this.openAIClient = openAIClient;
        this.chartService = chartService;
        this.chatMemoryService = chatMemoryService;
        this.objectMapper = new ObjectMapper();
    }

    public ChatResponse answerQuestion(ChatRequest request, String userToken) {
        long startTime = System.currentTimeMillis();
        try {
            String sessionId = (userToken != null && !userToken.isBlank()) ? userToken : "default-session";
            List<com.seple.ThingsBoard_Bot.model.dto.ChatMessage> history = chatMemoryService.getHistory(sessionId);

            if (userToken == null || userToken.isBlank()) {
                return ChatResponse.builder().answer("Please log in to ThingsBoard first.").error(true).build();
            }

            List<Map<String, Object>> allDevices = userDataService.getUserDevicesData(userToken);
            Map<String, Object> rawData = filterDevicesForQuestion(allDevices, request.getQuestion(), history, sessionId);

            Map<String, Object> filteredData = ContextFilterUtil.filterAttributes(rawData);
            
            // SECOND PASS: Keyword-based pruning for efficiency
            int estimateBefore = TokenCounterService.countMessageTokens(SYSTEM_PROMPT, history, request.getQuestion(), objectMapper.writeValueAsString(filteredData));
            if (!TokenCounterService.fitsInContextWindow(estimateBefore)) {
                Map<String, Object> prunedData = new HashMap<>();
                String q = request.getQuestion().toLowerCase();
                for (Map.Entry<String, Object> entry : filteredData.entrySet()) {
                    String k = entry.getKey().toLowerCase();
                    if (k.contains("name") || k.contains("id") || k.contains("gateway") || k.contains("status") || k.contains("operational") || k.contains("unified_status") || k.contains("system_note") || 
                        k.contains("cctv") || k.contains("ias") || k.contains("fas") || k.contains("bas") || k.contains("timelock") || k.contains("accesscontrol")) {
                        prunedData.put(entry.getKey(), entry.getValue());
                    }
                }
                filteredData = prunedData;
            }

            String contextJson = objectMapper.writeValueAsString(filteredData);
            int totalTokens = TokenCounterService.countMessageTokens(SYSTEM_PROMPT, history, request.getQuestion(), contextJson);

            while (!TokenCounterService.fitsInContextWindow(totalTokens) && !history.isEmpty()) {
                chatMemoryService.removeOldestMessage(sessionId);
                history = chatMemoryService.getHistory(sessionId);
                totalTokens = TokenCounterService.countMessageTokens(SYSTEM_PROMPT, history, request.getQuestion(), contextJson);
            }

            String queryInstruction = detectQueryInstructions(request.getQuestion(), filteredData);
            String userMessage = "Device Data Context:\n" + contextJson + "\n\n" + queryInstruction + "\n\nUser Question: " + request.getQuestion();

            String answer = openAIClient.chat(SYSTEM_PROMPT, history, userMessage);
            chatMemoryService.recordInteraction(sessionId, request.getQuestion(), answer);

            return ChatResponse.builder()
                    .answer(answer)
                    .tokensUsed(totalTokens)
                    .timestamp(System.currentTimeMillis())
                    .error(false)
                    .build();

        } catch (Exception e) {
            log.error("❌ Error: {}", e.getMessage(), e);
            return ChatResponse.builder().answer("I encountered an error. Please try again.").error(true).build();
        }
    }

    private String detectQueryInstructions(String question, Map<String, Object> data) {
        if (question == null || question.isBlank()) return "";
        String q = question.toLowerCase();
        StringBuilder instr = new StringBuilder();
        if (q.contains("how many") || q.contains("which") || q.contains("what")) {
            instr.append("\nCRITICAL: Report individual sub-system values (CCTV, IAS, etc.) exactly as they appear in context, even if the main gateway is offline.");
        }
        return instr.toString();
    }

    private Map<String, Object> filterDevicesForQuestion(List<Map<String, Object>> allDevices, String question, List<com.seple.ThingsBoard_Bot.model.dto.ChatMessage> history, String sessionId) {
        if (allDevices == null || allDevices.isEmpty()) return new HashMap<>();
        
        StringBuilder contextText = new StringBuilder();
        if (question != null) contextText.append(question.toLowerCase()).append(" ");
        if (history != null) {
            for (com.seple.ThingsBoard_Bot.model.dto.ChatMessage msg : history) {
                if (msg.getContent() != null) contextText.append(msg.getContent().toLowerCase()).append(" ");
            }
        }
        String qLower = contextText.toString();
        
        Map<String, Map<String, Object>> uniqueMatchedDevices = new LinkedHashMap<>();
        for (Map<String, Object> dev : allDevices) {
            String name = String.valueOf(dev.getOrDefault("device_name", "")).toLowerCase();
            String branch = String.valueOf(dev.getOrDefault("branchName", "")).toLowerCase();
            String id = String.valueOf(dev.getOrDefault("device_id", ""));
            
            String strippedName = name.replace("-", "").replace(" ", "");
            String strippedBranch = branch.replace("-", "").replace(" ", "");
            String strippedQ = qLower.replace("-", "").replace(" ", "");
            
            if (qLower.contains(name) || qLower.contains(branch) 
                || (strippedName.length() > 3 && strippedQ.contains(strippedName))
                || (strippedBranch.length() > 3 && strippedQ.contains(strippedBranch))) {
                uniqueMatchedDevices.put(id, dev);
            }
        }
        
        List<Map<String, Object>> matchedDevices = new ArrayList<>(uniqueMatchedDevices.values());
        boolean isGlobal = question != null && (qLower.contains("all") || qLower.contains("any") || qLower.contains("which") || qLower.contains("list") || qLower.contains("branches") || qLower.contains("devices") || qLower.contains("have"));

        Map<String, Object> flat;
        if (isGlobal) {
            chatMemoryService.setActiveDevices(sessionId, new ArrayList<>());
            flat = flattenDeviceSummary(allDevices);
        } else if (!matchedDevices.isEmpty()) {
            List<String> activeNames = new ArrayList<>();
            for (Map<String, Object> md : matchedDevices) activeNames.add(getBestName(md));
            chatMemoryService.setActiveDevices(sessionId, activeNames);
            flat = matchedDevices.size() <= 5 ? flattenDeviceList(matchedDevices) : flattenDeviceSummary(matchedDevices);
        } else {
            flat = new HashMap<>();
            List<String> names = new ArrayList<>();
            for (Map<String, Object> dev : allDevices) names.add(getBestName(dev));
            flat.put("available_branches", names);
            flat.put("SYSTEM_NOTE", "Ask user to specify a branch name.");
            return flat;
        }

        if (isGlobal) {
            int online = 0;
            int offline = 0;
            for (Map<String, Object> dev : allDevices) {
                String g = String.valueOf(dev.getOrDefault("gateway", "N/A"));
                if ("Online".equalsIgnoreCase(g) || "On".equalsIgnoreCase(g)) online++; else offline++;
            }
            flat.put("SYSTEM_NOTE", String.format("MANDATORY: There are exactly %d Online and %d Offline branches. Your header MUST say 'Total: %d Online | %d Offline'.", online, offline, online, offline));
        }
        
        return flat;
    }

    private String getBestName(Map<String, Object> dev) {
        Object bName = dev.get("branchName");
        if (bName != null && !bName.toString().isBlank() && !"unknown".equalsIgnoreCase(bName.toString())) return bName.toString();
        return String.valueOf(dev.getOrDefault("device_name", "Unknown Branch"));
    }

    private Map<String, Object> flattenDeviceList(List<Map<String, Object>> devices) {
        Map<String, Object> flat = new HashMap<>();
        if (devices.size() == 1) {
            flat.putAll(devices.get(0));
        } else {
            for (Map<String, Object> dev : devices) {
                String name = getBestName(dev);
                for (Map.Entry<String, Object> entry : dev.entrySet()) flat.put(name + "." + entry.getKey(), entry.getValue());
            }
        }
        return flat;
    }

    private Map<String, Object> flattenDeviceSummary(List<Map<String, Object>> devices) {
        Map<String, Object> summary = new HashMap<>();
        summary.put("total_devices", devices.size());
        for (Map<String, Object> dev : devices) {
            String name = getBestName(dev);
            String g = String.valueOf(dev.getOrDefault("gateway", "N/A"));
            String s = String.valueOf(dev.getOrDefault("status", "N/A"));
            
            summary.put(name + ".gateway", g);
            summary.put(name + ".status", s);
            summary.put(name + ".unified_status", String.format("Gateway: %s, Status: %s", g, s));
            
            // Sub-System Individual Values
            if (dev.containsKey("cctv")) summary.put(name + ".cctv", dev.get("cctv"));
            if (dev.containsKey("ias")) summary.put(name + ".ias", dev.get("ias"));
            if (dev.containsKey("bas")) summary.put(name + ".bas", dev.get("bas"));
            if (dev.containsKey("fas")) summary.put(name + ".fas", dev.get("fas"));
            if (dev.containsKey("timeLock")) summary.put(name + ".timeLock", dev.get("timeLock"));
            if (dev.containsKey("accessControl")) summary.put(name + ".accessControl", dev.get("accessControl"));

            if ("Offline".equalsIgnoreCase(g) || "Fault".equalsIgnoreCase(g) || "N/A".equalsIgnoreCase(g) || "Inactive".equalsIgnoreCase(s)) {
                summary.put(name + ".OPERATIONAL", "False");
            } else {
                summary.put(name + ".OPERATIONAL", "True");
            }
        }
        return summary;
    }

    public void initializeUserCache(String userToken) {
        if (userToken != null && !userToken.isBlank()) {
            userDataService.getUserDevicesData(userToken);
        }
    }
}
