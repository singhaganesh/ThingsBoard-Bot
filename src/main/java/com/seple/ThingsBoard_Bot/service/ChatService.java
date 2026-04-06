package com.seple.ThingsBoard_Bot.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.seple.ThingsBoard_Bot.client.OpenAIClient;
import com.seple.ThingsBoard_Bot.config.ChatbotConfig;
import com.seple.ThingsBoard_Bot.exception.ContextOverflowException;
import com.seple.ThingsBoard_Bot.model.domain.BranchSnapshot;
import com.seple.ThingsBoard_Bot.model.dto.AnswerMetadata;
import com.seple.ThingsBoard_Bot.model.dto.ChatMessage;
import com.seple.ThingsBoard_Bot.model.dto.ChatRequest;
import com.seple.ThingsBoard_Bot.model.dto.ChatResponse;
import com.seple.ThingsBoard_Bot.service.query.DeterministicAnswerService;
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

    public ChatService(UserDataService userDataService, OpenAIClient openAIClient,
            ChartService chartService, ChatMemoryService chatMemoryService,
            ChatbotConfig chatbotConfig,
            QueryIntentResolver queryIntentResolver, DeterministicAnswerService deterministicAnswerService,
            StructuredContextBuilder structuredContextBuilder) {
        this.userDataService = userDataService;
        this.openAIClient = openAIClient;
        this.chatMemoryService = chatMemoryService;
        this.chatbotConfig = chatbotConfig;
        this.queryIntentResolver = queryIntentResolver;
        this.deterministicAnswerService = deterministicAnswerService;
        this.structuredContextBuilder = structuredContextBuilder;
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

            List<BranchSnapshot> snapshots = userDataService.getUserBranchSnapshots(userToken);
            ResolvedQuery resolvedQuery = queryIntentResolver.resolve(request.getQuestion(), snapshots,
                    chatMemoryService.getActiveBranch(sessionId));

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
                    chatMemoryService.setPendingTopic(sessionId, null); 
                }
            }

            BranchSnapshot targetBranch = resolvedQuery.getTargetBranch();
            if (targetBranch != null && targetBranch.getIdentity() != null) {
                chatMemoryService.setActiveBranch(sessionId, targetBranch.getIdentity().getTechnicalId());
            }

            String deterministicAnswer = chatbotConfig.isDeterministicAnswersEnabled()
                    ? deterministicAnswerService.answer(resolvedQuery, snapshots)
                    : null;
            if (deterministicAnswer != null) {
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
                    + (resolvedQuery.isBranchFromMemory() ? "\nNOTE: The user did not specify a branch, so we are continuing with " + targetBranch.getIdentity().getBranchName() + " from memory. You MUST explicitly name this branch in your response." : "")
                    + (activeTopic != null ? "\nCRITICAL: The user is following up on a previous question about '" + activeTopic + "'. You MUST ONLY report on this specific topic for the branch." : "")
                    + "\n\nUser Question: " + request.getQuestion();
            String answer = openAIClient.chat(SYSTEM_PROMPT, history, userMessage);
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
}
