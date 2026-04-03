package com.seple.ThingsBoard_Bot.model.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GatewayStatus {
    private NormalizedState state;
    private String health;
    private Boolean active;
    private String sourceFieldUsed;
}
