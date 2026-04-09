package com.seple.ThingsBoard_Bot.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GlobalOverviewCounters {
    private Integer onlineBranches;
    private Integer offlineBranches;
    private String sourceDeviceId;
    private long fetchedAt;
}
