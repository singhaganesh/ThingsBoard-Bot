package com.seple.ThingsBoard_Bot.model.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubsystemStatus {
    private String systemName;
    private NormalizedState state;
    private boolean installed;
    private String rawValue;
    private String health;
    private String sourceFieldUsed;
}
