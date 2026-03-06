package com.seple.ThingsBoard_Bot.controller;

import java.util.Map;
import java.util.HashMap;

import org.springframework.http.ResponseEntity;
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
     * Endpoint to retrieve data for ALL devices assigned to the tenant or user.
     * Fetches directly from ThingsBoard using the new helper methods.
     *
     * @return 200 OK with a list of device data maps.
     */
    @GetMapping("/all-devices")
    public ResponseEntity<Map<String, Object>> getAllDevicesData(
            @RequestHeader(value = "X-TB-Token", required = false) String userToken) {
        log.info("API Request: GET /api/v1/data/all-devices (user token: {})", userToken != null ? "present" : "absent");
        
        java.util.List<Map<String, String>> cleanedDevices = new java.util.ArrayList<>();

        if (userToken != null && !userToken.isBlank()) {
            // Get user scoped devices directly
            cleanedDevices = userDataService.getUserDevicesList(userToken);
        } else {
            // Fallback to all tenant devices
            java.util.List<Map<String, String>> devices = tbClient.getAllDevices();
            for (Map<String, String> device : devices) {
                Map<String, String> basicInfo = new HashMap<>();
                basicInfo.put("device_id", device.get("id"));
                basicInfo.put("device_name", device.get("name"));
                cleanedDevices.add(basicInfo);
            }
        }
        
        Map<String, Object> response = new HashMap<>();
        response.put("device_count", cleanedDevices.size());
        response.put("devices", cleanedDevices);

        log.info("Returning count and basic info for {} devices", cleanedDevices.size());
        return ResponseEntity.ok(response);
    }
}
