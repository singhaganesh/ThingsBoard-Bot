package com.seple.ThingsBoard_Bot.service.query;

import com.seple.ThingsBoard_Bot.model.domain.BranchSnapshot;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class ResolvedQuery {
    QueryIntent intent;
    String originalQuestion;
    BranchSnapshot targetBranch;
    String targetSystem;
    boolean global;
    boolean deterministic;
    double confidence;
}
