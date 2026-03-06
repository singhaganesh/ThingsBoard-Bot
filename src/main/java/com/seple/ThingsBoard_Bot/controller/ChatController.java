package com.seple.ThingsBoard_Bot.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.seple.ThingsBoard_Bot.model.dto.ChatRequest;
import com.seple.ThingsBoard_Bot.model.dto.ChatResponse;
import com.seple.ThingsBoard_Bot.service.ChatService;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/api/v1/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    /**
     * POST /api/v1/chat/ask
     * Answer a user question about IoT device data.
     * If X-TB-Token header is present, data is scoped to that user's devices only.
     */
    @PostMapping("/ask")
    public ResponseEntity<ChatResponse> askQuestion(
            @RequestBody ChatRequest request,
            @RequestHeader(value = "X-TB-Token", required = false) String userToken) {

        log.info("Received chat request: '{}' (user token: {})",
                request.getQuestion(), userToken != null ? "present" : "absent");

        if (request.getQuestion() == null || request.getQuestion().trim().isEmpty()) {
            return ResponseEntity.badRequest().body(
                    ChatResponse.builder()
                            .error(true)
                            .errorMessage("Question cannot be empty")
                            .timestamp(System.currentTimeMillis())
                            .build()
            );
        }

        ChatResponse response = chatService.answerQuestion(request, userToken);
        return ResponseEntity.ok(response);
    }
}

