package com.seple.ThingsBoard_Bot.exception;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ContextOverflowException.class)
    public ResponseEntity<Map<String, Object>> handleContextOverflow(ContextOverflowException e) {
        log.error("Context overflow: {}", e.getMessage());
        return buildErrorResponse(HttpStatus.PAYLOAD_TOO_LARGE, "Context too large", e.getMessage());
    }

    @ExceptionHandler(ThingsBoardException.class)
    public ResponseEntity<Map<String, Object>> handleThingsBoardError(ThingsBoardException e) {
        log.error("ThingsBoard error: {}", e.getMessage());
        return buildErrorResponse(HttpStatus.BAD_GATEWAY, "ThingsBoard error", e.getMessage());
    }

    @ExceptionHandler(OpenAIException.class)
    public ResponseEntity<Map<String, Object>> handleOpenAIError(OpenAIException e) {
        log.error("OpenAI error: {}", e.getMessage());
        return buildErrorResponse(HttpStatus.BAD_GATEWAY, "OpenAI error", e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException e) {
        log.error("Bad request: {}", e.getMessage());
        return buildErrorResponse(HttpStatus.BAD_REQUEST, "Invalid request", e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneral(Exception e) {
        log.error("Unexpected error: {}", e.getMessage(), e);
        return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR,
                "Internal server error", "An unexpected error occurred. Please try again.");
    }

    private ResponseEntity<Map<String, Object>> buildErrorResponse(
            HttpStatus status, String error, String message) {
        Map<String, Object> body = new HashMap<>();
        body.put("error", error);
        body.put("message", message);
        body.put("status", status.value());
        body.put("timestamp", LocalDateTime.now().toString());
        return ResponseEntity.status(status).body(body);
    }
}
