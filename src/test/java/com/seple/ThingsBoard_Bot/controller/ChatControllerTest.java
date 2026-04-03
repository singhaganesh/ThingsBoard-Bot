package com.seple.ThingsBoard_Bot.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import com.seple.ThingsBoard_Bot.model.dto.AnswerMetadata;
import com.seple.ThingsBoard_Bot.model.dto.ChatRequest;
import com.seple.ThingsBoard_Bot.model.dto.ChatResponse;
import com.seple.ThingsBoard_Bot.service.ChatService;

class ChatControllerTest {

    private final ChatService chatService = mock(ChatService.class);
    private final ChatController controller = new ChatController(chatService);

    @Test
    void shouldRejectEmptyQuestion() {
        ResponseEntity<ChatResponse> response = controller.askQuestion(new ChatRequest("", null, null), null);

        assertEquals(400, response.getStatusCode().value());
        assertTrue(response.getBody().isError());
        assertEquals("Question cannot be empty", response.getBody().getErrorMessage());
    }

    @Test
    void shouldReturnChatAnswer() {
        ChatResponse serviceResponse = ChatResponse.builder()
                .answer("**Battery Voltage Reading: 14V DC.**")
                .metadata(AnswerMetadata.builder()
                        .intent("BATTERY_VOLTAGE")
                        .matchedBranch("BRANCH TARAKESHWAR")
                        .deterministic(true)
                        .confidence(0.95)
                        .build())
                .tokensUsed(0)
                .timestamp(System.currentTimeMillis())
                .error(false)
                .build();
        when(chatService.answerQuestion(any(ChatRequest.class), eq("jwt-token"))).thenReturn(serviceResponse);

        ResponseEntity<ChatResponse> response = controller.askQuestion(
                new ChatRequest("What is Tarakeshwar battery voltage?", null, null), "jwt-token");

        assertEquals(200, response.getStatusCode().value());
        assertEquals("BATTERY_VOLTAGE", response.getBody().getMetadata().getIntent());
        assertFalse(response.getBody().isError());
    }

    @Test
    void shouldInitializeCache() {
        ResponseEntity<Void> response = controller.initCache("jwt-token");

        assertEquals(200, response.getStatusCode().value());
        verify(chatService).initializeUserCache("jwt-token");
    }
}
