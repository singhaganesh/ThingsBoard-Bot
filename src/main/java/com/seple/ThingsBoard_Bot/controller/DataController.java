package com.seple.ThingsBoard_Bot.controller;

import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.seple.ThingsBoard_Bot.service.DataService;
import com.seple.ThingsBoard_Bot.service.UserDataService;
import com.seple.ThingsBoard_Bot.client.ThingsBoardClient;

import lombok.extern.slf4j.Slf4j;

/**
 * REST Controller for accessing raw, unfiltered device data.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/data")
public class DataController {

    private final DataService dataService;
    private final UserDataService userDataService;
    private final ThingsBoardClient tbClient;

    public DataController(DataService dataService, UserDataService userDataService, ThingsBoardClient tbClient) {
        this.dataService = dataService;
        this.userDataService = userDataService;
        this.tbClient = tbClient;
    }

    /**
     * Endpoint to retrieve the FULL, unfiltered data dump for the device(s).
     * Includes all telemetry and all 3 scopes of attributes directly from the cache.
     * If userToken is provided, retrieves data scoped to the user's devices.
     *
     * @return 200 OK with the full JSON map of device data.
     */
    @GetMapping("/full")
    public ResponseEntity<Map<String, Object>> getFullDeviceData(
            @RequestHeader(value = "X-TB-Token", required = false) String userToken) {
        log.info("API Request: GET /api/v1/data/full (user token: {})", userToken != null ? "present" : "absent");
        
        Map<String, Object> fullData;
        
        if (userToken != null && !userToken.isBlank()) {
            fullData = userDataService.getUserDevicesDataFlat(userToken);
        } else {
            fullData = dataService.getDeviceData();
        }
        
        log.info("Returning full device data ({} keys)", fullData.size());
        return ResponseEntity.ok(fullData);
    }

    /**
     * Endpoint to retrieve full information for a SINGLE device by its ID.
     * Searches user cache if X-TB-Token is present, otherwise uses global cache/fetch.
     *
     * @param deviceId The ThingsBoard device ID.
     * @param userToken Optional user JWT token for scoped access.
     * @return 200 OK with device data, or 404 if not found.
     */
    @GetMapping("/device/{deviceId}")
    public ResponseEntity<Map<String, Object>> getDeviceDataById(
            @PathVariable String deviceId,
            @RequestHeader(value = "X-TB-Token", required = false) String userToken) {
        log.info("API Request: GET /api/v1/data/device/{} (user token: {})", 
                deviceId, userToken != null ? "present" : "absent");
        
        Map<String, Object> deviceData;
        
        if (userToken != null && !userToken.isBlank()) {
            deviceData = userDataService.getUserDeviceDataById(userToken, deviceId);
        } else {
            deviceData = dataService.getDeviceDataById(deviceId);
        }
        
        if (deviceData.isEmpty() || !deviceData.containsKey("device_id")) {
            log.warn("Device {} not found", deviceId);
            return ResponseEntity.notFound().build();
        }
        
        log.info("Returning data for device {}", deviceId);
        return ResponseEntity.ok(deviceData);
    }

    /**
     * Endpoint to retrieve data for ALL devices assigned to the tenant or user.
     * Fetches directly from ThingsBoard using the new helper methods.
     *
     * @return 200 OK with a list of device data maps.
     */
    @GetMapping("/all-devices")
    public ResponseEntity<Map<String, Object>> getAllDevicesData(
            @RequestHeader(value = "X-TB-Token", required = false) String userToken) {
        log.info("API Request: GET /api/v1/data/all-devices (user token: {})", userToken != null ? "present" : "absent");
        
        List<Map<String, String>> cleanedDevices = new ArrayList<>();

        if (userToken != null && !userToken.isBlank()) {
            log.info("Attempting to fetch devices using provided JWT token...");
            // Get user scoped devices directly
            cleanedDevices = userDataService.getUserDevicesList(userToken);
            log.info("UserDataService returned {} devices for this token.", cleanedDevices.size());
        } else {
            log.info("No token provided. Falling back to global system client fetch.");
            // Fallback to all tenant devices
            List<Map<String, String>> devices = tbClient.getAllDevices();
            for (Map<String, String> device : devices) {
                Map<String, String> basicInfo = new HashMap<>();
                basicInfo.put("device_id", device.get("id"));
                basicInfo.put("device_name", device.get("name"));
                basicInfo.put("device_type", device.get("type"));
                cleanedDevices.add(basicInfo);
            }
            log.info("Global client returned {} devices.", cleanedDevices.size());
        }
        
        Map<String, Object> response = new HashMap<>();
        response.put("device_count", cleanedDevices.size());
        response.put("devices", cleanedDevices);

        log.info("FINAL RESPONSE: Returning {} devices to client.", cleanedDevices.size());
        return ResponseEntity.ok(response);
    }
}
