package com.seple.ThingsBoard_Bot.controller;

import java.util.Map;
import java.util.HashMap;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.seple.ThingsBoard_Bot.service.DataService;
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
    private final ThingsBoardClient tbClient;

    public DataController(DataService dataService, ThingsBoardClient tbClient) {
        this.dataService = dataService;
        this.tbClient = tbClient;
    }

    /**
     * Endpoint to retrieve the FULL, unfiltered data dump for the device.
     * Includes all telemetry and all 3 scopes of attributes directly from the cache.
     *
     * @return 200 OK with the full JSON map of device data.
     */
    @GetMapping("/full")
    public ResponseEntity<Map<String, Object>> getFullDeviceData() {
        log.info("API Request: GET /api/v1/data/full");
        
        // Fetch the 1-minute cached data (bypassing the Chat Q&A filter)
        Map<String, Object> fullData = dataService.getDeviceData();
        
        log.info("Returning full device data ({} keys)", fullData.size());
        return ResponseEntity.ok(fullData);
    }

    /**
     * Endpoint to retrieve data for ALL devices assigned to the tenant.
     * Fetches directly from ThingsBoard using the new helper methods.
     *
     * @return 200 OK with a list of device data maps.
     */
    @GetMapping("/all-devices")
    public ResponseEntity<Map<String, Object>> getAllDevicesData() {
        log.info("API Request: GET /api/v1/data/all-devices");
        
        java.util.List<Map<String, String>> devices = tbClient.getAllDevices();
        java.util.List<Map<String, String>> cleanedDevices = new java.util.ArrayList<>();

        // Extract just ID and Name
        for (Map<String, String> device : devices) {
            Map<String, String> basicInfo = new HashMap<>();
            basicInfo.put("device_id", device.get("id"));
            basicInfo.put("device_name", device.get("name"));
            cleanedDevices.add(basicInfo);
        }
        
        Map<String, Object> response = new HashMap<>();
        response.put("device_count", devices.size());
        response.put("devices", cleanedDevices);

        log.info("Returning count and basic info for {} devices", devices.size());
        return ResponseEntity.ok(response);
    }
}
