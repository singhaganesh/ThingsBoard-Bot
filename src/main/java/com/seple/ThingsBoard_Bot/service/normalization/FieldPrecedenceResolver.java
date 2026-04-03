package com.seple.ThingsBoard_Bot.service.normalization;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.seple.ThingsBoard_Bot.model.domain.NormalizedState;

@Component
public class FieldPrecedenceResolver {

    private final ValueNormalizer valueNormalizer;

    public FieldPrecedenceResolver(ValueNormalizer valueNormalizer) {
        this.valueNormalizer = valueNormalizer;
    }

    public ResolvedField resolveGatewayState(Map<String, Object> raw) {
        return resolveFirstState(raw, List.of("gateway", "gwStatus", "gwHealth", "status"));
    }

    public ResolvedField resolveSubsystemState(Map<String, Object> raw, String primaryField, String... fallbacks) {
        List<String> candidates = new ArrayList<>();
        candidates.add(primaryField);
        for (String fallback : fallbacks) {
            candidates.add(fallback);
        }
        return resolveFirstState(raw, candidates);
    }

    public ResolvedMetric resolveBatteryVoltage(Map<String, Object> raw) {
        Double gatewayVoltage = valueNormalizer.toDouble(raw.get("gatewayStatus_battery_voltage"));
        Double batteryVoltage = valueNormalizer.toDouble(raw.get("battery_status_battery_voltage"));

        if (gatewayVoltage != null && gatewayVoltage > 0) {
            return new ResolvedMetric(gatewayVoltage, "gatewayStatus_battery_voltage");
        }
        if (batteryVoltage != null && batteryVoltage > 0) {
            return new ResolvedMetric(batteryVoltage, "battery_status_battery_voltage");
        }
        if (gatewayVoltage != null) {
            return new ResolvedMetric(gatewayVoltage, "gatewayStatus_battery_voltage");
        }
        if (batteryVoltage != null) {
            return new ResolvedMetric(batteryVoltage, "battery_status_battery_voltage");
        }
        return new ResolvedMetric(null, null);
    }

    public ResolvedMetric resolveAcVoltage(Map<String, Object> raw) {
        Double value = valueNormalizer.toDouble(raw.get("ac_status_ac_voltage"));
        return new ResolvedMetric(value, value != null ? "ac_status_ac_voltage" : null);
    }

    public ResolvedMetric resolveSystemCurrent(Map<String, Object> raw) {
        Double value = valueNormalizer.toDouble(raw.get("current_status_system_current"));
        return new ResolvedMetric(value, value != null ? "current_status_system_current" : null);
    }

    private ResolvedField resolveFirstState(Map<String, Object> raw, List<String> candidates) {
        for (String key : candidates) {
            Object rawValue = raw.get(key);
            if (rawValue == null) {
                continue;
            }
            String value = String.valueOf(rawValue);
            NormalizedState state = valueNormalizer.toState(value);
            if (state != NormalizedState.UNKNOWN) {
                return new ResolvedField(state, key, value);
            }
        }
        return new ResolvedField(NormalizedState.UNKNOWN, null, null);
    }

    public record ResolvedField(NormalizedState state, String sourceField, String rawValue) {
    }

    public record ResolvedMetric(Double value, String sourceField) {
    }
}
