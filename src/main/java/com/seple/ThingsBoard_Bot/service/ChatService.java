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
            You are SAI (Smart Assistant for IoT), a Senior Security Analyst for bank branch monitoring.
            Use the provided structured branch context exactly as the source of truth.

            RULES
            1. Always use the word \"Branch\" for bank branches.
            2. Treat NOT_INSTALLED as \"Not Installed\", not offline.
            3. Do not invent branches, statuses, or metrics that are not present in the context.
            4. Start directly with the answer. No greetings.
            5. Prefer concise, operational wording.
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
