package com.seple.ThingsBoard_Bot.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnswerMetadata {
    private String intent;
    private String matchedBranch;
    private boolean deterministic;
    private double confidence;
}
