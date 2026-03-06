package com.seple.ThingsBoard_Bot.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
     */
    @GetMapping("/check")
    public ResponseEntity<AlertData> checkAlerts() {
        log.debug("Alert check requested");
        AlertData alertData = alertService.checkAlerts();
        return ResponseEntity.ok(alertData);
    }
}
