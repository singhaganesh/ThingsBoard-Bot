package com.seple.ThingsBoard_Bot.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.seple.ThingsBoard_Bot.client.OpenAIClient;
import com.seple.ThingsBoard_Bot.config.ChatbotConfig;
import com.seple.ThingsBoard_Bot.model.domain.BranchIdentity;
import com.seple.ThingsBoard_Bot.model.domain.BranchSnapshot;
import com.seple.ThingsBoard_Bot.model.domain.PowerStatus;
import com.seple.ThingsBoard_Bot.model.dto.ChatMessage;
import com.seple.ThingsBoard_Bot.model.dto.ChatRequest;
import com.seple.ThingsBoard_Bot.model.dto.ChatResponse;
import com.seple.ThingsBoard_Bot.service.query.DeterministicAnswerService;
import com.seple.ThingsBoard_Bot.service.query.QueryIntent;
import com.seple.ThingsBoard_Bot.service.query.QueryIntentResolver;
import com.seple.ThingsBoard_Bot.service.query.ResolvedQuery;
import com.seple.ThingsBoard_Bot.util.StructuredContextBuilder;

class ChatServiceTest {

    private final UserDataService userDataService = mock(UserDataService.class);
    private final OpenAIClient openAIClient = mock(OpenAIClient.class);
    private final ChartService chartService = mock(ChartService.class);
    private final ChatMemoryService chatMemoryService = mock(ChatMemoryService.class);
    private final ChatbotConfig chatbotConfig = mock(ChatbotConfig.class);
    private final QueryIntentResolver queryIntentResolver = mock(QueryIntentResolver.class);
    private final DeterministicAnswerService deterministicAnswerService = mock(DeterministicAnswerService.class);
    private final StructuredContextBuilder structuredContextBuilder = mock(StructuredContextBuilder.class);

    private final ChatService service = new ChatService(
            userDataService,
            openAIClient,
            chartService,
            chatMemoryService,
            chatbotConfig,
            queryIntentResolver,
            deterministicAnswerService,
            structuredContextBuilder);

    @Test
    void shouldApplyPendingTopicForBranchOnlyFollowUpAndUseDeterministicAnswer() {
        String token = "jwt-token";
        String question = "CHANDANNAGAR";
        BranchSnapshot branch = BranchSnapshot.builder()
                .identity(BranchIdentity.builder()
                        .branchName("BRANCH CHANDANNAGAR")
                        .technicalId("BOI-CHANDANNAGAR")
                        .build())
                .power(PowerStatus.builder().batteryVoltage(0.0).build())
                .build();
        List<BranchSnapshot> snapshots = List.of(branch);

        ResolvedQuery initial = ResolvedQuery.builder()
                .intent(QueryIntent.GENERAL_LLM)
                .originalQuestion(question)
                .targetBranch(branch)
                .targetSystem(null)
                .global(false)
                .ambiguous(false)
                .branchFromMemory(false)
                .deterministic(false)
                .confidence(0.55)
                .build();

        when(chatMemoryService.getHistory(token)).thenReturn(List.of(new ChatMessage("assistant", "prompt")));
        when(chatMemoryService.getActiveBranch(token)).thenReturn(null);
        when(chatMemoryService.getPendingTopic(token)).thenReturn("BATTERY_VOLTAGE");
        when(userDataService.getUserBranchSnapshots(token)).thenReturn(snapshots);
        when(queryIntentResolver.resolve(eq(question), eq(snapshots), eq(null))).thenReturn(initial);
        when(chatbotConfig.isDeterministicAnswersEnabled()).thenReturn(true);
        when(chatbotConfig.isLogDecisionMetadata()).thenReturn(false);
        when(deterministicAnswerService.answer(any(ResolvedQuery.class), eq(snapshots)))
                .thenReturn("For Branch BRANCH CHANDANNAGAR, Battery Voltage Reading is 0.0V DC.");

        ChatResponse response = service.answerQuestion(new ChatRequest(question, null, null), token);

        ArgumentCaptor<ResolvedQuery> queryCaptor = ArgumentCaptor.forClass(ResolvedQuery.class);
        verify(deterministicAnswerService).answer(queryCaptor.capture(), eq(snapshots));
        assertEquals(QueryIntent.BATTERY_VOLTAGE, queryCaptor.getValue().getIntent());
        assertFalse(response.isError());
        assertEquals("For Branch CHANDANNAGAR, Battery Voltage Reading is 0.0V DC.", response.getAnswer());
        verify(chatMemoryService).setPendingTopic(token, null);
        verify(openAIClient, never()).chat(any(), any(), any());
    }
}
