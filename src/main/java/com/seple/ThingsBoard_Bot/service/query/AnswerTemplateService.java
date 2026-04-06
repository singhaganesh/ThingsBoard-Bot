package com.seple.ThingsBoard_Bot.service.query;

import java.util.List;

import org.springframework.stereotype.Component;

import com.seple.ThingsBoard_Bot.model.domain.BranchSnapshot;

@Component
public class AnswerTemplateService {

    public String renderGlobalOverview(List<String> onlineBranches, List<String> offlineBranches) {
        StringBuilder builder = new StringBuilder();
        builder.append("**Total: ")
                .append(onlineBranches.size()).append(" Online | ")
                .append(offlineBranches.size()).append(" Offline**");
        builder.append("\nFor your question about all branches, here is the current branch-level status.");

        if (!onlineBranches.isEmpty()) {
            builder.append("\n\nOnline:");
            for (String branch : onlineBranches) {
                builder.append("\n- ").append(branch);
            }
        }

        if (!offlineBranches.isEmpty()) {
            builder.append("\n\nOffline:");
            for (String branch : offlineBranches) {
                builder.append("\n- ").append(branch);
            }
        }

        return builder.toString();
    }

    public String renderGatewayStatus(BranchSnapshot branch, String stateText) {
        return "**For Branch " + branchName(branch) + ", the Gateway status is currently " + stateText + ".**";
    }

    public String renderMetric(BranchSnapshot branch, String label, Double value, String unit) {
        if (value == null) {
            return "**For Branch " + branchName(branch) + ", " + label + " is N/A.**";
        }
        return "**For Branch " + branchName(branch) + ", " + label + " is " + trim(value) + unit + ".**";
    }

    public String renderActiveDevices(BranchSnapshot branch, List<String> activeSystems) {
        return "**For Branch " + branchName(branch) + ", Active Devices (" + activeSystems.size() + "): "
                + String.join(", ", activeSystems)
                + ".**";
    }

    public String renderCctvStatus(BranchSnapshot branch, Integer onlineCameras, Integer totalCameras) {
        if (onlineCameras == null && totalCameras == null) {
            return "**For Branch " + branchName(branch) + ", CCTV Camera Status is N/A.**";
        }
        if (totalCameras != null) {
            return "**For Branch " + branchName(branch) + ", CCTV Camera Status is "
                    + onlineCameras + " of " + totalCameras + " cameras ONLINE.**";
        }
        return "**For Branch " + branchName(branch) + ", CCTV Camera Status is " + onlineCameras + " cameras ONLINE.**";
    }

    public String renderAlertStatus(BranchSnapshot branch, String label, int count) {
        return "**For Branch " + branchName(branch) + ", " + label + " is " + count + ".**";
    }

    public String renderSubsystemStatus(BranchSnapshot branch, String displayName, String stateText) {
        return "**For Branch " + branchName(branch) + ", " + displayName + " Status is " + stateText + ".**";
    }

    private String branchName(BranchSnapshot branch) {
        if (branch == null || branch.getIdentity() == null || branch.getIdentity().getBranchName() == null) {
            return "Unknown";
        }
        String display = branch.getIdentity().getBranchName()
                .replaceFirst("(?i)^BRANCH\\s+", "")
                .trim();
        String technicalId = branch.getIdentity().getTechnicalId();
        if ("TR".equalsIgnoreCase(display) && technicalId != null
                && technicalId.toUpperCase().contains("TRENDZ")) {
            return "TRENDZ";
        }
        return display;
    }

    private String trim(Double value) {
        return value % 1 == 0 ? String.valueOf(value.longValue()) : String.valueOf(value);
    }
}
