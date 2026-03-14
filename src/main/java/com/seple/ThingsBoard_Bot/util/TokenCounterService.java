package com.seple.ThingsBoard_Bot.util;

import lombok.extern.slf4j.Slf4j;

/**
 * Estimates token count for OpenAI API calls.
 * <p>
 * Uses the ~4 characters per token approximation.
 * Ensures we never exceed the context window before calling OpenAI.
 * </p>
 */
@Slf4j
public class TokenCounterService {

    // Budget per message (system + user + context combined)
    private static final int MAX_CONTEXT_TOKENS = 10000;

    // OpenAI model limit (gpt-3.5-turbo = 16,385 tokens total)
    private static final int MODEL_TOKEN_LIMIT = 128000;

    /**
     * Estimate token count for a given text.
     * Approximation: ~1 token per 4 characters.
     */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return text.length() / 4;
    }

    /**
     * Count total tokens across system prompt, history, user question, and context.
     */
    public static int countMessageTokens(String systemPrompt, java.util.List<com.seple.ThingsBoard_Bot.model.dto.ChatMessage> history, String question, String contextJson) {
        int systemTokens = estimateTokens(systemPrompt);
        int questionTokens = estimateTokens(question);
        int contextTokens = estimateTokens(contextJson);
        
        int historyTokens = 0;
        if (history != null) {
            for (com.seple.ThingsBoard_Bot.model.dto.ChatMessage msg : history) {
                historyTokens += estimateTokens(msg.getContent());
            }
        }
        
        int total = systemTokens + historyTokens + questionTokens + contextTokens;

        log.debug("Token count — sys: {}, hist: {}, q: {}, ctx: {}, TOTAL: {}",
                systemTokens, historyTokens, questionTokens, contextTokens, total);

        return total;
    }

    /**
     * Check if the total token count fits within our context budget.
     */
    public static boolean fitsInContextWindow(int totalTokens) {
        boolean fits = totalTokens <= MAX_CONTEXT_TOKENS;
        if (!fits) {
            log.warn("⚠️ Token count {} exceeds budget of {}", totalTokens, MAX_CONTEXT_TOKENS);
        }
        return fits;
    }

    /**
     * Check if total tokens fit within the model's absolute limit.
     */
    public static boolean fitsInModelLimit(int totalTokens) {
        return totalTokens <= MODEL_TOKEN_LIMIT;
    }
}
