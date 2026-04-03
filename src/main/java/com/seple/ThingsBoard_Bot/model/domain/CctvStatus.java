package com.seple.ThingsBoard_Bot.model.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CctvStatus {
    private NormalizedState state;
    private Integer cameraCount;
    private Integer onlineCameraCount;
    private Boolean hasDisconnect;
    private Boolean hasTamper;
    private String hddStatus;
}
