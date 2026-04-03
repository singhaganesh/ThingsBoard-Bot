package com.seple.ThingsBoard_Bot.service.normalization;

import java.util.Locale;

import org.springframework.stereotype.Component;

import com.seple.ThingsBoard_Bot.model.domain.NormalizedState;

@Component
public class ValueNormalizer {

    public NormalizedState toState(String rawValue) {
        if (rawValue == null || rawValue.isBlank() || "null".equalsIgnoreCase(rawValue)) {
            return NormalizedState.UNKNOWN;
        }

        String value = rawValue.trim().toLowerCase(Locale.ROOT);

        return switch (value) {
            case "online", "on", "healthy", "active", "true", "1", "yes", "clear", "normal" -> NormalizedState.ONLINE;
            case "offline", "off", "inactive", "false", "0", "disconnected" -> NormalizedState.OFFLINE;
            case "fault", "alarm", "error", "tamper", "triggered", "critical" -> NormalizedState.FAULT;
            case "n/a", "na", "not installed", "-" -> NormalizedState.NOT_INSTALLED;
            default -> NormalizedState.UNKNOWN;
        };
    }

    public Boolean toBoolean(String rawValue) {
        if (rawValue == null || rawValue.isBlank() || "null".equalsIgnoreCase(rawValue)) {
            return null;
        }

        String value = rawValue.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "true", "1", "yes", "on", "healthy", "online" -> true;
            case "false", "0", "no", "off", "offline", "fault", "inactive" -> false;
            default -> null;
        };
    }

    public Double toDouble(Object rawValue) {
        if (rawValue == null) {
            return null;
        }
        String text = String.valueOf(rawValue).trim();
        if (text.isBlank() || "null".equalsIgnoreCase(text) || "N/A".equalsIgnoreCase(text)) {
            return null;
        }

        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public Integer toInt(Object rawValue, int fallback) {
        if (rawValue == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(rawValue).trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
