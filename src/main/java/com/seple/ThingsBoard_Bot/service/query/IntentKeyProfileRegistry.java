package com.seple.ThingsBoard_Bot.service.query;

import java.util.List;

import org.springframework.stereotype.Component;

@Component
public class IntentKeyProfileRegistry {

    public List<String> keysFor(QueryIntent intent) {
        return switch (intent) {
            case GATEWAY_STATUS -> List.of("gateway", "status", "gatewayStatus");
            case BATTERY_VOLTAGE -> List.of("battery_status_battery_voltage", "gatewayStatus_battery_voltage", "BATTERY LOW");
            case BATTERY_LOW_STATUS -> List.of("BATTERY LOW", "gatewayStatus_BATTERY LOW", "ticketStatus_BATTERY_LOW");
            case AC_VOLTAGE -> List.of("gatewayStatus_ac_voltage", "ac_status_ac_voltage", "ac_result");
            case SYSTEM_CURRENT -> List.of("gatewayStatus_system_current", "current_status_system_current", "cur_result");
            case NETWORK_STATUS -> List.of("gatewayStatus_NETWORK", "system_status_statusbox_network", "networkOperator");
            case CCTV_STATUS -> List.of("cameraLinkStatus", "cctvStatus", "rock_CAMERAdETAILS", "hikvision_camera_status");
            case CCTV_HDD_ERROR_STATUS -> List.of("HDD ERROR", "ticketStatus_HDD_ERROR", "cameraStatus_HDD ERROR", "hddStatus");
            case CCTV_HDD_INFO -> List.of("rock_HddINFO", "hddStatus");
            case CCTV_RECORDING_INFO -> List.of("rock_VIDEOdETAILS", "Hikvision_NVR_CameraRecInfo");
            case SUBSYSTEM_STATUS, SUBSYSTEM_FAULT_STATUS, SUBSYSTEM_ALARM_STATUS -> List.of(
                    "iasStatus", "basStatus", "fasStatus", "timeLock", "timeLockHealth", "accessControlStatus",
                    "ticketStatus_IAS_FAULT", "ticketStatus_FAS_FAULT", "BASfaultCOUNT", "ticketStatus_TLS_TAMPER",
                    "ticketStatus_ACS_TAMPER", "ticketStatus_IAS_ACTIVATE", "ticketStatus_FAS_ACTIVATE",
                    "ticketStatus_TLS_DOOR_OPEN", "ticketStatus_ACS_DOOR_OPEN");
            case DOOR_STATUS -> List.of("timeLockDoor", "accessControlDoor");
            case ACCESS_CONTROL_USER_COUNT -> List.of("accessControlTotalUsers", "totalUsers", "registeredUsers", "accessControlUserCount");
            case ACCESS_CONTROL_DEVICE_INFO -> List.of("accessControlModel", "biometricModel", "acsModel", "accessControlFirmware",
                    "biometricFirmware", "accessControlFirmwareVersion", "accessControlIp", "biometricIp", "accessControlIP");
            default -> List.of();
        };
    }
}
