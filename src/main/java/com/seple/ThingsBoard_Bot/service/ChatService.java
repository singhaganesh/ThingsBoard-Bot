package com.seple.ThingsBoard_Bot.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.seple.ThingsBoard_Bot.client.OpenAIClient;
import com.seple.ThingsBoard_Bot.config.ChatbotConfig;
import com.seple.ThingsBoard_Bot.exception.ContextOverflowException;
import com.seple.ThingsBoard_Bot.model.domain.BranchSnapshot;
import com.seple.ThingsBoard_Bot.model.dto.GlobalOverviewCounters;
import com.seple.ThingsBoard_Bot.model.dto.AnswerMetadata;
import com.seple.ThingsBoard_Bot.model.dto.ChatMessage;
import com.seple.ThingsBoard_Bot.model.dto.ChatRequest;
import com.seple.ThingsBoard_Bot.model.dto.ChatResponse;
import com.seple.ThingsBoard_Bot.service.query.DeterministicAnswerService;
import com.seple.ThingsBoard_Bot.service.index.GlobalAggregatorService;
import com.seple.ThingsBoard_Bot.service.query.QueryIntent;
import com.seple.ThingsBoard_Bot.service.query.QueryIntentResolver;
import com.seple.ThingsBoard_Bot.service.query.ResolvedQuery;
import com.seple.ThingsBoard_Bot.util.StructuredContextBuilder;
import com.seple.ThingsBoard_Bot.util.TokenCounterService;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class ChatService {

    private static final String SYSTEM_PROMPT = """
            You are SAI (Smart Assistant for IoT), a Senior Security Analyst.
            Every response MUST be self-descriptive so the user knows the Branch and the Metric being reported.

            MANDATORY HEADER RULES
            1. Every response MUST start with a bold line following this pattern:
               **Branch [NAME]: The [Metric Name] is [Value].**
            2. Never give a "naked" value. Always anchor it to the Branch name.
            3. Always use the word "Branch" (e.g. Branch BALLY BAZAR).

            MANDATORY RESPONSE TEMPLATES
            - GATEWAY: **Branch [NAME]: The Gateway status is currently [ONLINE/OFFLINE].**
            - VOLTAGE: **Branch [NAME]: The [Battery/AC] Voltage Reading is [Value]V [AC/DC].**
            - SYSTEMS: **Branch [NAME]: The [IAS/BAS/FAS] Status is [Status].**
            - CCTV: **Branch [NAME]: The CCTV Camera Status is [X] cameras ONLINE.**

            REASONING & MEMORY
            - If you are answering based on conversational history (memory), you MUST explicitly state: "Continuing report for Branch [Name]:" or "For Branch [Name]:".
            - N/A POLICY: Report "N/A" as "Offline" or "Not Installed".
            - NO FLUFF: Start directly with the bold anchor line.
            """;

    private final UserDataService userDataService;
    private final OpenAIClient openAIClient;
    private final ChatMemoryService chatMemoryService;
    private final ChatbotConfig chatbotConfig;
    private final QueryIntentResolver queryIntentResolver;
    private final DeterministicAnswerService deterministicAnswerService;
    private final StructuredContextBuilder structuredContextBuilder;
    private final GlobalAggregatorService globalAggregatorService;

    public ChatService(UserDataService userDataService, OpenAIClient openAIClient,
            ChartService chartService, ChatMemoryService chatMemoryService,
            ChatbotConfig chatbotConfig,
            QueryIntentResolver queryIntentResolver, DeterministicAnswerService deterministicAnswerService,
            StructuredContextBuilder structuredContextBuilder,
            GlobalAggregatorService globalAggregatorService) {
        this.userDataService = userDataService;
        this.openAIClient = openAIClient;
        this.chatMemoryService = chatMemoryService;
        this.chatbotConfig = chatbotConfig;
        this.queryIntentResolver = queryIntentResolver;
        this.deterministicAnswerService = deterministicAnswerService;
        this.structuredContextBuilder = structuredContextBuilder;
        this.globalAggregatorService = globalAggregatorService;
    }

    public ChatResponse answerQuestion(ChatRequest request, String userToken) {
        try {
            String sessionId = (userToken != null) ? userToken : "default-session";
            List<ChatMessage> history = chatMemoryService.getHistory(sessionId);

            if (userToken == null || userToken.isBlank()) {
                return ChatResponse.builder()
                        .answer("Please log in first.")
                        .error(true)
                        .build();
            }

            boolean twoStepEnabled = userDataService.isTwoStepFetchEnabled();
            List<BranchSnapshot> snapshots = twoStepEnabled
                    ? userDataService.getUserBranchIndexSnapshots(userToken)
                    : userDataService.getUserBranchSnapshots(userToken);
            ResolvedQuery resolvedQuery = queryIntentResolver.resolve(request.getQuestion(), snapshots,
                    chatMemoryService.getActiveBranch(sessionId));

            if (resolvedQuery.isGlobal() && globalAggregatorService.isEnabled()) {
                GlobalOverviewCounters counters = globalAggregatorService.fetchGlobalOverview(userToken);
                String globalAnswer = deterministicAnswerService.answerGlobalOverview(counters);
                if (globalAnswer != null) {
                    logDecision(resolvedQuery, true, 0);
                    chatMemoryService.recordInteraction(sessionId, request.getQuestion(), globalAnswer);
                    return ChatResponse.builder()
                            .answer(globalAnswer)
                            .metadata(buildMetadata(resolvedQuery, true))
                            .tokensUsed(0)
                            .timestamp(System.currentTimeMillis())
                            .error(false)
                            .build();
                }
            }

            // UNIVERSAL AMBIGUITY FILTER: If multiple branches could answer this, ask for clarification
            if (resolvedQuery.isAmbiguous()) {
                // SAVE TOPIC: Remember what they asked about (e.g. "cctv")
                if (resolvedQuery.getTargetSystem() != null) {
                    chatMemoryService.setPendingTopic(sessionId, resolvedQuery.getTargetSystem());
                } else {
                    // Map common intents to readable topics if targetSystem is null
                    chatMemoryService.setPendingTopic(sessionId, resolvedQuery.getIntent().name());
                }

                String clarification = "I found multiple branches. Which specific branch would you like to check? \n\n" +
                        String.join(", ", snapshots.stream()
                                .map(s -> s.getIdentity().getBranchName())
                                .toList());
                
                chatMemoryService.recordInteraction(sessionId, request.getQuestion(), clarification);
                return ChatResponse.builder()
                        .answer(clarification)
                        .metadata(buildMetadata(resolvedQuery, true))
                        .tokensUsed(0)
                        .timestamp(System.currentTimeMillis())
                        .error(false)
                        .build();
            }

            // TOPIC RETENTION: If we have a pending topic and the user just gave us a branch, apply the topic
            String activeTopic = null;
            if (resolvedQuery.getTargetBranch() != null && !resolvedQuery.isBranchFromMemory()) {
                activeTopic = chatMemoryService.getPendingTopic(sessionId);
                if (activeTopic != null) {
                    log.info("Applying pending topic '{}' to branch {}", activeTopic, resolvedQuery.getTargetBranch().getIdentity().getBranchName());
                    resolvedQuery = applyPendingTopic(resolvedQuery, activeTopic);
                    chatMemoryService.setPendingTopic(sessionId, null);
                }
            }

            // Phase-2 two-step retrieval:
            // resolve branch against lightweight index first, then lazy-load that single branch by intent.
            if (twoStepEnabled && resolvedQuery.getTargetBranch() != null && !resolvedQuery.isGlobal()
                    && resolvedQuery.getTargetBranch().getIdentity() != null) {
                String branchKey = resolvedQuery.getTargetBranch().getIdentity().getTechnicalId();
                BranchSnapshot hydrated = userDataService.getBranchSnapshotForIntent(userToken, branchKey, resolvedQuery.getIntent());
                if (hydrated != null) {
                    resolvedQuery = resolvedQuery.toBuilder().targetBranch(hydrated).build();
                    snapshots = List.of(hydrated);
                }
            } else if (twoStepEnabled && resolvedQuery.isGlobal()) {
                // Global deterministic overview still needs a branch list with states for now.
                snapshots = userDataService.getUserBranchSnapshots(userToken);
            }

            BranchSnapshot targetBranch = resolvedQuery.getTargetBranch();
            if (targetBranch != null && targetBranch.getIdentity() != null) {
                chatMemoryService.setActiveBranch(sessionId, targetBranch.getIdentity().getTechnicalId());
            }

            String deterministicAnswer = chatbotConfig.isDeterministicAnswersEnabled()
                    ? deterministicAnswerService.answer(resolvedQuery, snapshots)
                    : null;
            if (deterministicAnswer != null) {
                deterministicAnswer = normalizeAnswerStyle(deterministicAnswer);
                logDecision(resolvedQuery, true, 0);
                chatMemoryService.recordInteraction(sessionId, request.getQuestion(), deterministicAnswer);
                return ChatResponse.builder()
                        .answer(deterministicAnswer)
                        .metadata(buildMetadata(resolvedQuery, true))
                        .tokensUsed(0)
                        .timestamp(System.currentTimeMillis())
                        .error(false)
                        .build();
            }

            String contextJson = structuredContextBuilder.build(snapshots, targetBranch);
            int estimatedTokens = TokenCounterService.countMessageTokens(
                    SYSTEM_PROMPT, history, request.getQuestion(), contextJson);
            if (!TokenCounterService.fitsInContextWindow(estimatedTokens, chatbotConfig.getMaxContextTokens())) {
                throw new ContextOverflowException("Structured context exceeded local token budget");
            }

            String userMessage = "Structured Branch Context:\n" + contextJson
                    + "\nNOTE: You are currently reporting for " + targetBranch.getIdentity().getBranchName() + ". You MUST explicitly name this branch in your response header (e.g. **Branch " + targetBranch.getIdentity().getBranchName() + ": ...**)."
                    + (activeTopic != null ? "\nCRITICAL: The user is following up on a previous question about '" + activeTopic + "'. You MUST ONLY report on this specific topic for the branch." : "")
                    + "\n\nUser Question: " + request.getQuestion();
            String answer = openAIClient.chat(SYSTEM_PROMPT, history, userMessage);
            answer = normalizeAnswerStyle(answer);
            logDecision(resolvedQuery, false, estimatedTokens);
            chatMemoryService.recordInteraction(sessionId, request.getQuestion(), answer);

            return ChatResponse.builder()
                    .answer(answer)
                    .metadata(buildMetadata(resolvedQuery, false))
                    .tokensUsed(estimatedTokens)
                    .timestamp(System.currentTimeMillis())
                    .error(false)
                    .build();

        } catch (Exception e) {
            log.error("Chat handling failed: {}", e.getMessage(), e);
            return ChatResponse.builder()
                    .answer("System error encountered.")
                    .error(true)
                    .build();
        }
    }

    public void initializeUserCache(String userToken) {
        if (userToken != null) {
            userDataService.getUserDevicesData(userToken);
        }
    }

    private AnswerMetadata buildMetadata(ResolvedQuery query, boolean deterministic) {
        return AnswerMetadata.builder()
                .intent(query.getIntent().name())
                .matchedBranch(query.getTargetBranch() != null && query.getTargetBranch().getIdentity() != null
                        ? query.getTargetBranch().getIdentity().getBranchName()
                        : null)
                .deterministic(deterministic)
                .confidence(query.getConfidence())
                .build();
    }

    private void logDecision(ResolvedQuery query, boolean deterministic, int tokens) {
        if (!chatbotConfig.isLogDecisionMetadata()) {
            return;
        }

        log.info("Answer routing -> intent: {}, branch: {}, deterministic: {}, confidence: {}, estimatedTokens: {}",
                query.getIntent(),
                query.getTargetBranch() != null && query.getTargetBranch().getIdentity() != null
                        ? query.getTargetBranch().getIdentity().getTechnicalId()
                        : "global-or-none",
                deterministic,
                query.getConfidence(),
                tokens);
    }

    private String normalizeAnswerStyle(String answer) {
        if (answer == null) {
            return null;
        }

        String normalized = answer.trim();

        // Normalize legacy heading style from the LLM fallback:
        // "Branch XYZ: The <metric> ..."
        normalized = normalized.replaceAll("(?i)^\\*\\*Branch\\s+([^:]+):\\s*The\\s+", "**For Branch $1, ");
        normalized = normalized.replaceAll("(?i)^Branch\\s+([^:]+):\\s*The\\s+", "For Branch $1, ");

        // Keep the canonical form consistent.
        normalized = normalized.replaceAll("(?i)^\\*\\*For\\s+Branch\\s+", "**For Branch ");
        normalized = normalized.replaceAll("(?i)^For\\s+Branch\\s+", "For Branch ");
        normalized = normalized.replaceAll("(?i)^\\*\\*For\\s+Branch\\s+BRANCH\\s+", "**For Branch ");
        normalized = normalized.replaceAll("(?i)^For\\s+Branch\\s+BRANCH\\s+", "For Branch ");

        return normalized;
    }

    private ResolvedQuery applyPendingTopic(ResolvedQuery base, String pendingTopic) {
        QueryIntent intent = mapPendingTopicToIntent(pendingTopic);
        if (intent == null) {
            return base;
        }

        String targetSystem = mapPendingTopicToTargetSystem(pendingTopic, intent, base.getTargetSystem());
        return ResolvedQuery.builder()
                .intent(intent)
                .originalQuestion(base.getOriginalQuestion())
                .targetBranch(base.getTargetBranch())
                .targetSystem(targetSystem)
                .global(false)
                .ambiguous(false)
                .branchFromMemory(base.isBranchFromMemory())
                .deterministic(base.getTargetBranch() != null)
                .confidence(Math.max(base.getConfidence(), 0.95))
                .build();
    }

    private QueryIntent mapPendingTopicToIntent(String pendingTopic) {
        if (pendingTopic == null || pendingTopic.isBlank()) {
            return null;
        }

        String normalized = pendingTopic.trim().toUpperCase().replace(' ', '_');
        try {
            return QueryIntent.valueOf(normalized);
        } catch (IllegalArgumentException ignored) {
            // Fall through to topic aliases.
        }

        return switch (pendingTopic.trim().toLowerCase()) {
            case "battery", "battery_voltage" -> QueryIntent.BATTERY_VOLTAGE;
            case "ac", "ac_voltage" -> QueryIntent.AC_VOLTAGE;
            case "system_current", "current" -> QueryIntent.SYSTEM_CURRENT;
            case "battery_low", "battery_low_status" -> QueryIntent.BATTERY_LOW_STATUS;
            case "network", "network_status" -> QueryIntent.NETWORK_STATUS;
            case "cctv", "camera", "camera_status", "cctv_status" -> QueryIntent.CCTV_STATUS;
            case "camera_disconnect_history", "disconnect_history" -> QueryIntent.CAMERA_DISCONNECT_HISTORY;
            case "alarm", "alarm_status" -> QueryIntent.ALARM_STATUS;
            case "error", "error_status" -> QueryIntent.ERROR_STATUS;
            case "fault_devices" -> QueryIntent.FAULT_DEVICES;
            case "offline_devices" -> QueryIntent.OFFLINE_DEVICES;
            case "connected_devices" -> QueryIntent.CONNECTED_DEVICES;
            case "active_devices" -> QueryIntent.ACTIVE_DEVICES;
            case "fault_reason" -> QueryIntent.FAULT_REASON;
            case "door_status" -> QueryIntent.DOOR_STATUS;
            case "access_control_user_count" -> QueryIntent.ACCESS_CONTROL_USER_COUNT;
            case "access_control_device_info" -> QueryIntent.ACCESS_CONTROL_DEVICE_INFO;
            case "ias", "bas", "fas", "timelock", "accesscontrol" -> QueryIntent.SUBSYSTEM_STATUS;
            default -> null;
        };
    }

    private String mapPendingTopicToTargetSystem(String pendingTopic, QueryIntent intent, String currentTargetSystem) {
        if (intent != QueryIntent.SUBSYSTEM_STATUS && intent != QueryIntent.DOOR_STATUS) {
            return currentTargetSystem;
        }
        if (pendingTopic == null || pendingTopic.isBlank()) {
            return currentTargetSystem;
        }

        return switch (pendingTopic.trim().toLowerCase()) {
            case "ias" -> "ias";
            case "bas" -> "bas";
            case "fas" -> "fas";
            case "timelock", "time_lock", "door_status" -> "timeLock";
            case "accesscontrol", "access_control", "access control" -> "accessControl";
            case "cctv", "camera" -> "cctv";
            default -> currentTargetSystem;
        };
    }
}
