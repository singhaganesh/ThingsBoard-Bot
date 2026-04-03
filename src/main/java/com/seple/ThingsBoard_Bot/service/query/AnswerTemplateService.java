package com.seple.ThingsBoard_Bot.service.query;

import java.util.List;

import org.springframework.stereotype.Component;

import com.seple.ThingsBoard_Bot.model.domain.BranchSnapshot;

@Component
public class AnswerTemplateService {

    public String renderGlobalOverview(List<String> onlineBranches, List<String> offlineBranches) {
        StringBuilder builder = new StringBuilder();
        builder.append("**Total: ").append(onlineBranches.size()).append(" Online | ")
                .append(offlineBranches.size()).append(" Offline**");

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
        return "**The Branch Gateway status is currently " + stateText + ".**";
    }

    public String renderMetric(String label, Double value, String unit) {
        if (value == null) {
            return "**" + label + ": N/A.**";
        }
        return "**" + label + ": " + trim(value) + unit + ".**";
    }

    public String renderActiveDevices(BranchSnapshot branch, List<String> activeSystems) {
        return "**Active Devices (" + activeSystems.size() + "): " + String.join(", ", activeSystems)
                + ".**";
    }

    public String renderCctvStatus(Integer onlineCameras, Integer totalCameras) {
        if (onlineCameras == null && totalCameras == null) {
            return "**CCTV Camera Status: N/A.**";
        }
        if (totalCameras != null) {
            return "**CCTV Camera Status: " + onlineCameras + " of " + totalCameras + " cameras are ONLINE.**";
        }
        return "**CCTV Camera Status: " + onlineCameras + " cameras are ONLINE.**";
    }

    public String renderAlertStatus(String label, int count) {
        return "**" + label + ": " + count + ".**";
    }

    public String renderSubsystemStatus(String displayName, String stateText) {
        return "**" + displayName + " Status: " + stateText + ".**";
    }

    private String trim(Double value) {
        return value % 1 == 0 ? String.valueOf(value.longValue()) : String.valueOf(value);
    }
}
