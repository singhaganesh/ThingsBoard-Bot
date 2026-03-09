package com.seple.ThingsBoard_Bot.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a single message in the conversational history.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ChatMessage {
    /**
     * The role of the message author ("user", "assistant", or "system")
     */
    private String role;
    
    /**
     * The textual content of the message
     */
    private String content;
}
