package com.seple.ThingsBoard_Bot.model.dto;

import java.util.ArrayList;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeviceIndexEntry {
    private String deviceId;
    private String branchName;
    private String deviceType;
    private long indexedAt;

    @Builder.Default
    private List<String> aliases = new ArrayList<>();
}
