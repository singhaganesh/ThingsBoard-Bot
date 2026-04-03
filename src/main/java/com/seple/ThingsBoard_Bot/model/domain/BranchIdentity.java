package com.seple.ThingsBoard_Bot.model.domain;

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
public class BranchIdentity {
    private String branchName;
    private String technicalId;
    private String deviceId;
    private String branchId;

    @Builder.Default
    private List<String> aliases = new ArrayList<>();
}
