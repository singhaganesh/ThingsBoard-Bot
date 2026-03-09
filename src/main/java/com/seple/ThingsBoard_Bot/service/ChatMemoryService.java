package com.seple.ThingsBoard_Bot.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

import org.springframework.stereotype.Service;

import com.seple.ThingsBoard_Bot.model.dto.ChatMessage;

import lombok.extern.slf4j.Slf4j;

/**
 * Service to store the conversational history (sliding window) for users.
 * <p>
 * Keeps a limited number of previous question/answer pairs in memory
 * to provide OpenAI with necessary context for follow-up questions.
 * </p>
 */
@Slf4j
@Service
public class ChatMemoryService {

    // Maps a userToken (or sessionId) to a deque of ChatMessages
    private final Map<String, ConcurrentLinkedDeque<ChatMessage>> chatHistory = new ConcurrentHashMap<>();
    
    // Maximum number of messages to remember per user (2 Q&A pairs = 4 messages)
    private static final int MAX_HISTORY_MESSAGES = 4;

    /**
     * Add a single message to a user's chronological history.
     */
    public void addMessage(String sessionId, ChatMessage message) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }

        chatHistory.compute(sessionId, (key, deque) -> {
            if (deque == null) {
                deque = new ConcurrentLinkedDeque<>();
            }
            
            // Add to the end of the history
            deque.addLast(message);
            
            // Enforce sliding window token protection
            while (deque.size() > MAX_HISTORY_MESSAGES) {
                deque.removeFirst();
            }
            
            return deque;
        });
        
        log.debug("Added {} message to history for session '{}'. History size: {}", 
                message.getRole(), sessionId, chatHistory.get(sessionId).size());
    }

    /**
     * Adds both a user question and the AI's response to the history simultaneously.
     */
    public void recordInteraction(String sessionId, String userQuestion, String aiAnswer) {
        addMessage(sessionId, new ChatMessage("user", userQuestion));
        addMessage(sessionId, new ChatMessage("assistant", aiAnswer));
    }

    /**
     * Retrieve the recent conversation history for a user.
     * 
     * @return an ordered list of ChatMessage objects (oldest to newest)
     */
    public List<ChatMessage> getHistory(String sessionId) {
        if (sessionId == null || !chatHistory.containsKey(sessionId)) {
            return new ArrayList<>();
        }
        return new ArrayList<>(chatHistory.get(sessionId));
    }

    /**
     * Discards the oldest message to make room for tokens, if necessary.
     */
    public void removeOldestMessage(String sessionId) {
        if (sessionId != null && chatHistory.containsKey(sessionId)) {
            ConcurrentLinkedDeque<ChatMessage> deque = chatHistory.get(sessionId);
            if (!deque.isEmpty()) {
                ChatMessage removed = deque.removeFirst();
                log.debug("Dropping oldest history message ({}) to save tokens for {}", removed.getRole(), sessionId);
            }
        }
    }
    
    /**
     * Clear the history for a specific session.
     */
    public void clearHistory(String sessionId) {
        if (sessionId != null) {
            chatHistory.remove(sessionId);
            log.debug("Cleared history for session '{}'", sessionId);
        }
    }
}
