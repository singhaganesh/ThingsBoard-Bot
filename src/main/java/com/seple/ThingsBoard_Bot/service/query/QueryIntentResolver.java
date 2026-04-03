package com.seple.ThingsBoard_Bot.service.query;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.seple.ThingsBoard_Bot.model.domain.BranchSnapshot;
import com.seple.ThingsBoard_Bot.service.normalization.BranchAliasIndex;

@Component
public class QueryIntentResolver {

    private final BranchAliasIndex branchAliasIndex;

    public QueryIntentResolver(BranchAliasIndex branchAliasIndex) {
        this.branchAliasIndex = branchAliasIndex;
    }

    public ResolvedQuery resolve(String question, List<BranchSnapshot> snapshots, String activeBranchAlias) {
        String normalizedQuestion = branchAliasIndex.normalize(question);
        String compactQuestion = branchAliasIndex.compact(question);
        Map<String, BranchSnapshot> aliasIndex = branchAliasIndex.build(snapshots);
        boolean explicitGlobalQuestion = hasGlobalMarkers(normalizedQuestion);
        BranchSnapshot targetBranch = findBranch(normalizedQuestion, compactQuestion, aliasIndex, activeBranchAlias,
                explicitGlobalQuestion);
        boolean global = isGlobalQuestion(normalizedQuestion, targetBranch != null);
        QueryIntent intent = detectIntent(normalizedQuestion, targetBranch != null);
        boolean deterministic = intent != QueryIntent.GENERAL_LLM;
        double confidence = targetBranch != null || global ? 0.95 : 0.55;

        if (intent == QueryIntent.SUBSYSTEM_STATUS || intent == QueryIntent.DOOR_STATUS) {
            return ResolvedQuery.builder()
                    .intent(intent)
                    .originalQuestion(question)
                    .targetBranch(targetBranch)
                    .targetSystem(detectSubsystem(normalizedQuestion))
                    .global(false)
                    .deterministic(targetBranch != null)
                    .confidence(confidence)
                    .build();
        }

        return ResolvedQuery.builder()
                .intent(intent)
                .originalQuestion(question)
                .targetBranch(targetBranch)
                .global(global)
                .deterministic(deterministic && (global || targetBranch != null))
                .confidence(confidence)
                .build();
    }

    private BranchSnapshot findBranch(String normalizedQuestion, String compactQuestion,
            Map<String, BranchSnapshot> aliasIndex, String activeBranchAlias, boolean explicitGlobalQuestion) {
        List<String> aliases = aliasIndex.keySet().stream()
                .sorted((left, right) -> Integer.compare(right.length(), left.length()))
                .toList();

        for (String alias : aliases) {
            if (alias.isBlank()) {
                continue;
            }
            if (isWeakAlias(alias)) {
                continue;
            }
            if (matchesExplicitAlias(normalizedQuestion, compactQuestion, alias)) {
                return aliasIndex.get(alias);
            }
        }

        if (!explicitGlobalQuestion && activeBranchAlias != null && !activeBranchAlias.isBlank()) {
            for (String variant : branchAliasIndex.aliasVariants(activeBranchAlias)) {
                if (aliasIndex.containsKey(variant)) {
                    return aliasIndex.get(variant);
                }
            }
        }

        return null;
    }

    private boolean matchesExplicitAlias(String normalizedQuestion, String compactQuestion, String alias) {
        String normalizedAlias = branchAliasIndex.normalize(alias);
        if (normalizedAlias.isBlank()) {
            return false;
        }

        if (normalizedAlias.contains(" ")) {
            return Pattern.compile("(^|\\s)" + Pattern.quote(normalizedAlias) + "($|\\s)").matcher(normalizedQuestion)
                    .find();
        }

        if (normalizedAlias.length() >= 4
                && Pattern.compile("(^|\\s)" + Pattern.quote(normalizedAlias) + "($|\\s)").matcher(normalizedQuestion)
                        .find()) {
            return true;
        }

        String compactAlias = branchAliasIndex.compact(alias);
        return compactAlias.length() >= 6 && compactQuestion.contains(compactAlias);
    }

    private boolean isWeakAlias(String alias) {
        String normalizedAlias = branchAliasIndex.normalize(alias);
        return normalizedAlias.length() < 4;
    }

    private QueryIntent detectIntent(String normalizedQuestion, boolean hasTargetBranch) {
        String question = normalizedQuestion.toUpperCase(Locale.ROOT);

        if (isGlobalQuestion(question, hasTargetBranch)) {
            return QueryIntent.GLOBAL_OVERVIEW;
        }
        if (question.contains("BATTERY LOW")) {
            return QueryIntent.BATTERY_LOW_STATUS;
        }
        if (question.contains("ACCESS CONTROL") && question.contains("USER")) {
            return QueryIntent.ACCESS_CONTROL_USER_COUNT;
        }
        if (question.contains("ACCESS CONTROL") && question.contains("DEVICE")
                && (question.contains("INFO") || question.contains("DETAIL"))) {
            return QueryIntent.ACCESS_CONTROL_DEVICE_INFO;
        }
        if (question.contains("DOOR")
                && (question.contains("TIME LOCK") || question.contains("ACCESS CONTROL") || question.contains("ACS"))) {
            return QueryIntent.DOOR_STATUS;
        }
        if ((question.contains("HISTORY") || question.contains("HISTORICAL")) && (question.contains("DISCONNECT") || question.contains("DISCONNECTS")) && question.contains("CAMERA")) {
            return QueryIntent.CAMERA_DISCONNECT_HISTORY;
        }
        if ((question.contains("DISCONNECT") || question.contains("DISCONNECTS") || question.contains("DISCONNECTED"))
                && (question.contains("CAMERA") || question.contains("CCTV"))) {
            return QueryIntent.CAMERA_DISCONNECT_HISTORY;
        }
        if (question.startsWith("WHY") || (question.contains("WHY") && question.contains("FAULT"))) {
            return QueryIntent.FAULT_REASON;
        }
        if (question.contains("FAULT DEVICE") || (question.contains("FAULTY") && question.contains("DEVICE"))) {
            return QueryIntent.FAULT_DEVICES;
        }
        if (question.contains("OFFLINE DEVICE") || (question.contains("OFFLINE") && question.contains("DEVICE"))) {
            return QueryIntent.OFFLINE_DEVICES;
        }
        if (question.contains("CONNECTED DEVICE") || (question.contains("ALL DEVICES") && question.contains("CONNECTED"))) {
            return QueryIntent.CONNECTED_DEVICES;
        }
        if ((question.contains("ACTIVE DEVICE") || (question.contains("ACTIVE") && question.contains("DEVICE")))
                && !question.contains("INACTIVE")) {
            return QueryIntent.ACTIVE_DEVICES;
        }
        if (question.contains("NETWORK")) {
            return QueryIntent.NETWORK_STATUS;
        }
        if ((question.contains("CCTV") || question.contains("CAMERA")) && question.contains("HDD")
                && (question.contains("INFO") || question.contains("DETAIL"))) {
            return QueryIntent.CCTV_HDD_INFO;
        }
        if ((question.contains("CCTV") || question.contains("CAMERA")) && question.contains("RECORD")) {
            return QueryIntent.CCTV_RECORDING_INFO;
        }
        if (question.contains("HOW MANY") && question.contains("CAMERA")) {
            return QueryIntent.CCTV_STATUS;
        }
        if (question.contains("BATTERY") && question.contains("VOLT")) {
            return QueryIntent.BATTERY_VOLTAGE;
        }
        if (question.contains("AC") && question.contains("VOLT")) {
            return QueryIntent.AC_VOLTAGE;
        }
        if (question.contains("SYSTEM CURRENT")) {
            return QueryIntent.SYSTEM_CURRENT;
        }
        if (question.contains("CAMERA") || question.contains("CCTV")) {
            return QueryIntent.CCTV_STATUS;
        }
        if (question.contains("ERROR")) {
            return QueryIntent.ERROR_STATUS;
        }
        if (question.contains("ALARM")) {
            return QueryIntent.ALARM_STATUS;
        }
        if (containsSubsystemKeyword(question)) {
            return QueryIntent.SUBSYSTEM_STATUS;
        }
        if (question.contains("GATEWAY") || question.contains("STATUS") || question.contains("WORKING PROPERLY")
                || question.contains("ONLINE") || question.contains("OFFLINE")) {
            return QueryIntent.GATEWAY_STATUS;
        }
        return QueryIntent.GENERAL_LLM;
    }

    private boolean isGlobalQuestion(String normalizedQuestion, boolean hasTargetBranch) {
        String question = normalizedQuestion.toLowerCase(Locale.ROOT);
        if (hasTargetBranch) {
            return false;
        }
        return hasGlobalMarkers(question);
    }

    private boolean hasGlobalMarkers(String normalizedQuestion) {
        String question = normalizedQuestion.toLowerCase(Locale.ROOT);
        return question.contains("all")
                || question.contains("list")
                || question.contains("total")
                || question.contains("what branches")
                || question.contains("what devices")
                || question.contains("which branches")
                || question.contains("how many branches");
    }

    private boolean containsSubsystemKeyword(String normalizedQuestion) {
        return detectSubsystem(normalizedQuestion) != null;
    }

    private String detectSubsystem(String normalizedQuestion) {
        if (normalizedQuestion.contains("IAS") || normalizedQuestion.contains("INTRUSION")) {
            return "ias";
        }
        if (normalizedQuestion.contains("BAS")) {
            return "bas";
        }
        if (normalizedQuestion.contains("FAS") || normalizedQuestion.contains("FIRE")) {
            return "fas";
        }
        if (normalizedQuestion.contains("TIME LOCK")) {
            return "timeLock";
        }
        if (normalizedQuestion.contains("ACCESS CONTROL") || normalizedQuestion.contains("ACS")) {
            return "accessControl";
        }
        if (normalizedQuestion.contains("CCTV") || normalizedQuestion.contains("CAMERA")) {
            return "cctv";
        }
        return null;
    }
}

