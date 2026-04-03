package com.seple.ThingsBoard_Bot.util;

import lombok.extern.slf4j.Slf4j;

/**
 * Estimates token count for OpenAI API calls using a rough character budget.
 */
@Slf4j
public class TokenCounterService {

    private static final int MAX_CONTEXT_TOKENS = 10000;
    private static final int MODEL_TOKEN_LIMIT = 128000;

    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return text.length() / 4;
    }

    public static int countMessageTokens(String systemPrompt,
            java.util.List<com.seple.ThingsBoard_Bot.model.dto.ChatMessage> history,
            String question, String contextJson) {
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
        log.debug("Token count - sys: {}, hist: {}, q: {}, ctx: {}, total: {}",
                systemTokens, historyTokens, questionTokens, contextTokens, total);
        return total;
    }

    public static boolean fitsInContextWindow(int totalTokens) {
        return fitsInContextWindow(totalTokens, MAX_CONTEXT_TOKENS);
    }

    public static boolean fitsInContextWindow(int totalTokens, int maxContextTokens) {
        boolean fits = totalTokens <= maxContextTokens;
        if (!fits) {
            log.warn("Token count {} exceeds budget of {}", totalTokens, maxContextTokens);
        }
        return fits;
    }

    public static boolean fitsInModelLimit(int totalTokens) {
        return totalTokens <= MODEL_TOKEN_LIMIT;
    }
}
