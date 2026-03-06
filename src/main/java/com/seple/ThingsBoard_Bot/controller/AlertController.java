package com.seple.ThingsBoard_Bot.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestHeader;

import com.seple.ThingsBoard_Bot.model.dto.AlertData;
import com.seple.ThingsBoard_Bot.service.AlertService;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/api/v1/alerts")
public class AlertController {

    private final AlertService alertService;

    public AlertController(AlertService alertService) {
        this.alertService = alertService;
    }

    /**
     * GET /api/v1/alerts/check
     * Check for active IoT device alerts (battery, alarms, status).
     * If X-TB-Token header is present, data is scoped to that user's devices only.
     */
    @GetMapping("/check")
    public ResponseEntity<AlertData> checkAlerts(
            @RequestHeader(value = "X-TB-Token", required = false) String userToken) {
        log.debug("Alert check requested (user token: {})", userToken != null ? "present" : "absent");
        AlertData alertData = alertService.checkAlerts(userToken);
        return ResponseEntity.ok(alertData);
    }
}
