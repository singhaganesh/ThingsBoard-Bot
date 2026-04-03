package com.seple.ThingsBoard_Bot.model.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BranchSubsystems {
    private SubsystemStatus cctv;
    private SubsystemStatus ias;
    private SubsystemStatus bas;
    private SubsystemStatus fas;
    private SubsystemStatus timeLock;
    private SubsystemStatus accessControl;
}
