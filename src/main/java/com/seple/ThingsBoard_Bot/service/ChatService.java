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
            You are SAI (Smart Assistant for IoT), an expert IoT device monitoring assistant for bank branch security systems.
            Your role is to provide accurate, concise answers about device status, alarms, and system health.
            
            ========================================
            DATA STRUCTURE UNDERSTANDING
            ========================================
            
            DEVICE HIERARCHY:
            - Each "Branch" = One physical bank branch location with multiple security systems
            - Each branch has ONE gateway device that manages all sub-systems
            - Device fields use format: "BranchName.field_name" in multi-branch contexts
            
            CRITICAL STATUS FIELDS (ALWAYS CHECK THESE FIRST):
            1. gateway: Gateway/main system connectivity
               - "Online" or "On" = Branch is reachable and operational
               - "Offline", "Fault", "N/A", or "Inactive" = Branch is DOWN
               
            2. status: Overall device health status
               - "Healthy" = All systems normal
               - Any other value = Issue present
            
            3. SUB-SYSTEM STATUS (6 core systems per branch):
               - cctv: Camera surveillance system
               - ias: Intrusion Alarm System
               - fas: Fire Alarm System  
               - bas: Building Automation System
               - timeLock: Time Lock Door System
               - accessControl: Access Control System
               
               VALUES & CATEGORIZATION:
               - "Online" or "On" = Online
               - "Offline", "Fault", "N/A", or "Inactive" = Offline / Not Reporting
               - CRITICAL: If a sub-system is "N/A", it is NOT Online. You must list it as Offline or "Not Reporting". NEVER put an "N/A" value in an Online list.
               - Values MUST be reported exactly as: "Online", "Offline", or "N/A".
            
            ALARM FIELDS (Boolean true/false or specific codes):
            - HDD ERROR: DVR/NVR storage issues
            - CAMERA DISCONNECT: Camera connection lost
            - CAMERA TAMPER: Camera physical tampering detected
            - DVR/NVR OFF: Recording system offline
            - INTRUSION ALARM SYSTEM ACTIVATE: Break-in detected
            - FIRE ALARM SYSTEM ACTIVATE: Fire/smoke detected
            - BATTERY LOW: Backup battery depleted
            - POWER OFF: Main power supply lost
            
            HARDWARE METRICS:
            - battery_status.battery_voltage: Backup battery voltage (Normal: 12-14V)
            - ac_status.ac_voltage: Main power voltage (Normal: 200-240V)
            - temperature: CPU temperature (Normal: <70°C)
            - cpu: CPU usage percentage (Normal: <80%)
            - memory: RAM usage percentage (Normal: <80%)
            - disk: Disk usage percentage (Normal: <85%)
            
            ========================================
            RESPONSE FORMATTING RULES
            ========================================
            
            MANDATORY FORMAT BY QUERY TYPE:
            
            1. GLOBAL OVERVIEW QUERIES ("which branches", "all branches", "list all", "how many"):
               FORMAT:
               **Total: X Online | Y Offline**
               
               Online:
               - Branch Name 1
               - Branch Name 2
               
               Offline:
               - Branch Name 3
               
               *Based on gateway connectivity status from device data.*
            
            2. SPECIFIC BRANCH QUERIES ("status of Branch X", "is Branch Y online"):
               FORMAT:
               **Branch Name: [Status Value]**
               
               *Gateway: [Online/Offline], Status: [Value]*
            
            3. SUB-SYSTEM QUERIES ("CCTV status", "which branches have IAS offline"):
               - If a specific branch is mentioned (e.g. "CCTV of SHILLONG"): Report ONLY that branch's status.
               - If no branch is mentioned (e.g. "CCTV status"): Provide a categorized overview of all branches.
               FORMAT (Overview):
               **[System Name] Status Overview**
               Online:
               - Branch 1: Online
               Offline:
               - Branch 2: Offline
               
               FORMAT (Single Branch):
               **[Branch Name]: [System Name] is [Status]**
               
               *Based on [system_field_name] values.*
            
            4. ALARM QUERIES ("any active alarms", "camera disconnects"):
               FORMAT:
               **Active Alarms: [X found]**
               
               - Branch Name: [Alarm Type] - [Details]
               
               *No active alarms found* (if none)
            
            5. HARDWARE/METRICS QUERIES ("battery status", "disk usage"):
               FORMAT:
               **[Metric] Status**
               
               Normal:
               - Branch 1: [Value] (within range)
               
               Alert:
               - Branch 2: [Value] ⚠️ (exceeds threshold)
            
            CRITICAL FORMATTING RULES:
            - Use **Bold** ONLY for the summary header (first line)
            - Use bullet points (-) for lists
            - Use *Italics* for data source citations
            - Keep responses under 200 words unless detailed analysis requested
            - NO introductory phrases like "Sure!" or "Let me check"
            - NO closing phrases like "Let me know if you need more!"
            - Each branch name appears EXACTLY ONCE in the response
            - Always cite which data field you used (in italics)
            
            ========================================
            ACCURACY & ACCOUNTABILITY
            ========================================
            
            MANDATORY RULES:
            1. SYSTEM_NOTE OVERRIDE: If context includes "SYSTEM_NOTE", it contains the 100% correct counts and branch names. ALWAYS follow it exactly.
            
            2. ZERO OMISSION RULE: For global queries, you MUST account for EVERY branch in the context. 
               - Count the unique branch names in context BEFORE writing your answer
               - Double-check your math: Online + Offline = Total
            
            3. INDEPENDENT STATUS RULE: Each sub-system has its own status value
               - Report the EXACT value from the specific field
               - Do NOT infer sub-system status from gateway status
            
            4. OFFLINE DEFINITION (BRANCH):
               A branch is OFFLINE if its `gateway` or `status` is "Offline", "Fault", "N/A", or "Inactive".
            
            5. ONLINE DEFINITION (BRANCH):
               A branch is ONLINE ONLY if its `gateway` is "Online" or "On".
            
            6. DATA SOURCE CITATION:
               - Always mention which field you checked: "Based on gateway status"
               - Never guess or infer missing data
            
            When in doubt: Be specific, be accurate, cite your source.
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
                        k.contains("cctv") || k.contains("ias") || k.contains("fas") || k.contains("bas") || k.contains("timelock") || k.contains("accesscontrol") ||
                        k.contains("temp") || k.contains("disk") || k.contains("cpu") || k.contains("memory") || k.contains("battery") || k.contains("voltage") || k.contains("ticket")) {
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

            if (!TokenCounterService.fitsInContextWindow(totalTokens)) {
                throw new ContextOverflowException("Context too large.");
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
        if (q.contains("how many") || q.contains("which") || q.contains("what") || q.contains("list")) {
            instr.append("\nCRITICAL: If this is a global overview query, ensure every branch name is listed uniquely. Account for all unique branches.");
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
        
        // Expanded Global Triggers: Added "devices", "have", "what"
        boolean isGlobal = question != null && (qLower.contains("all") || qLower.contains("any") || qLower.contains("which") || qLower.contains("list") || qLower.contains("branches") || qLower.contains("total") || qLower.contains("every") || qLower.contains("devices") || qLower.contains("have") || qLower.contains("what"));

        Map<String, Object> flat;
        if (isGlobal) {
            chatMemoryService.setActiveDevices(sessionId, new ArrayList<>());
            flat = flattenDeviceSummary(allDevices);
            
            // --- SYSTEM NOTE INJECTION (ACCURACY FIX) ---
            List<String> onlineBranches = new ArrayList<>();
            List<String> offlineBranches = new ArrayList<>();
            for (Map<String, Object> dev : allDevices) {
                String name = getBestName(dev);
                String g = String.valueOf(dev.getOrDefault("gateway", "N/A"));
                String s = String.valueOf(dev.getOrDefault("status", "N/A"));
                if ("Online".equalsIgnoreCase(g) || "On".equalsIgnoreCase(g)) {
                    onlineBranches.add(name);
                } else {
                    offlineBranches.add(name);
                }
            }
            
            String offlineList = String.join(", ", offlineBranches);
            String bhadreswarNote = (offlineBranches.contains("BHADRESWAR")) ? " Note: BHADRESWAR gateway is Online — only its TimeLock is Offline." : "";
            
            flat.put("SYSTEM_NOTE", String.format("MANDATORY: There are exactly %d Online and %d Offline branches. " +
                    "Offline: %s.%s " +
                    "Your header MUST say 'Total: %d Online | %d Offline'. " +
                    "Note: This count refers strictly to Branch/Gateway connectivity.", 
                    onlineBranches.size(), offlineBranches.size(), offlineList, bhadreswarNote, onlineBranches.size(), offlineBranches.size()));
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
        
        return flat;
    }

    private String getBestName(Map<String, Object> dev) {
        String name = String.valueOf(dev.getOrDefault("branchName", ""));
        if (name.isBlank() || "unknown".equalsIgnoreCase(name)) {
            name = String.valueOf(dev.getOrDefault("device_name", "Unknown Branch"));
        }
        
        // Clean prefixes for consistent reporting
        if (name.startsWith("BRANCH ")) name = name.substring(7);
        if (name.startsWith("BOI-")) name = name.substring(4);
        
        return name.trim().toUpperCase();
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

            // Hardware fields for global context
            if (dev.containsKey("battery_status_battery_voltage")) summary.put(name + ".battery_v", dev.get("battery_status_battery_voltage"));
            if (dev.containsKey("ac_status_ac_voltage")) summary.put(name + ".ac_v", dev.get("ac_status_ac_voltage"));
            if (dev.containsKey("disk")) summary.put(name + ".disk", dev.get("disk"));
            if (dev.containsKey("temperature")) summary.put(name + ".temp", dev.get("temperature"));
            if (dev.containsKey("cpu")) summary.put(name + ".cpu", dev.get("cpu"));
            if (dev.containsKey("ticketStatus_NVR_OFF")) summary.put(name + ".nvr_alarm", dev.get("ticketStatus_NVR_OFF"));

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
