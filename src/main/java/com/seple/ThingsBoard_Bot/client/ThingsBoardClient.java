package com.seple.ThingsBoard_Bot.client;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seple.ThingsBoard_Bot.config.ThingsBoardConfig;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class ThingsBoardClient {

    private final ThingsBoardConfig config;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    private String jwtToken;
    private long tokenExpiryTime;

    public ThingsBoardClient(ThingsBoardConfig config,
                             @Qualifier("thingsBoardRestTemplate") RestTemplate restTemplate) {
        this.config = config;
        this.restTemplate = restTemplate;
        this.objectMapper = new ObjectMapper();
    }

    // ==================== Authentication ====================

    public void authenticate() {
        String loginUrl = config.getUrl() + "/api/auth/login";
        log.info("Authenticating with ThingsBoard at: {}", config.getUrl());

        try {
            Map<String, String> loginBody = new HashMap<>();
            loginBody.put("username", config.getUsername());
            loginBody.put("password", config.getPassword());

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<Map<String, String>> request = new HttpEntity<>(loginBody, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(loginUrl, request, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode responseJson = objectMapper.readTree(response.getBody());
                this.jwtToken = responseJson.get("token").asText();
                // Token typically valid for ~15 min; refresh at 10 min
                this.tokenExpiryTime = System.currentTimeMillis() + (10 * 60 * 1000);
                log.info("✅ Authenticated with ThingsBoard successfully!");
            } else {
                log.error("❌ ThingsBoard authentication failed: {}", response.getStatusCode());
            }
        } catch (RestClientException e) {
            log.error("❌ Failed to connect to ThingsBoard: {}", e.getMessage());
            throw new RuntimeException("ThingsBoard authentication failed", e);
        } catch (Exception e) {
            log.error("❌ Error parsing authentication response: {}", e.getMessage());
            throw new RuntimeException("ThingsBoard authentication parse error", e);
        }
    }

    private HttpHeaders getAuthHeaders() {
        if (jwtToken == null || System.currentTimeMillis() >= tokenExpiryTime) {
            log.info("Token expired or missing, re-authenticating...");
            authenticate();
        }

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Authorization", "Bearer " + jwtToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    // ==================== Devices List ====================

    public List<Map<String, String>> getAllDevices() {
        String url = config.getUrl() + "/api/tenant/devices?pageSize=1000&page=0";
        log.debug("Fetching all tenant devices from: {}", url);
        List<Map<String, String>> devices = new java.util.ArrayList<>();

        try {
            HttpEntity<Void> entity = new HttpEntity<>(getAuthHeaders());
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode json = objectMapper.readTree(response.getBody());
                JsonNode dataArray = json.get("data");
                if (dataArray != null && dataArray.isArray()) {
                    for (JsonNode deviceNode : dataArray) {
                        Map<String, String> deviceMap = new HashMap<>();
                        if (deviceNode.has("id")) {
                            deviceMap.put("id", deviceNode.get("id").get("id").asText());
                        }
                        if (deviceNode.has("name")) {
                            deviceMap.put("name", deviceNode.get("name").asText());
                        }
                        if (deviceNode.has("type")) {
                            deviceMap.put("type", deviceNode.get("type").asText());
                        }
                        devices.add(deviceMap);
                    }
                }
                log.info("✅ Fetched {} devices from tenant", devices.size());
            }
        } catch (Exception e) {
            log.error("❌ Error fetching all devices: {}", e.getMessage());
        }

        return devices;
    }

    // ==================== Telemetry ====================

    public Map<String, Object> getTelemetry() {
        return getTelemetry(config.getDeviceId());
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getTelemetry(String deviceId) {
        String url = config.getUrl()
                + "/api/plugins/telemetry/DEVICE/"
                + deviceId
                + "/values/timeseries";

        log.debug("Fetching telemetry from: {}", url);
        Map<String, Object> result = new HashMap<>();

        try {
            HttpEntity<Void> entity = new HttpEntity<>(getAuthHeaders());
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode json = objectMapper.readTree(response.getBody());
                json.fields().forEachRemaining(entry -> {
                    JsonNode valueArray = entry.getValue();
                    if (valueArray.isArray() && !valueArray.isEmpty()) {
                        result.put(entry.getKey(), valueArray.get(0).get("value").asText());
                    }
                });
                log.debug("✅ Fetched {} telemetry keys", result.size());
            }
        } catch (RestClientException e) {
            log.error("❌ Failed to fetch telemetry: {}", e.getMessage());
            // Retry once with fresh token
            retryWithFreshToken(() -> fetchTelemetryInto(deviceId, result));
        } catch (Exception e) {
            log.error("❌ Error parsing telemetry: {}", e.getMessage());
        }

        return result;
    }

    private void fetchTelemetryInto(String deviceId, Map<String, Object> result) {
        try {
            String url = config.getUrl()
                    + "/api/plugins/telemetry/DEVICE/"
                    + deviceId
                    + "/values/timeseries";

            HttpEntity<Void> entity = new HttpEntity<>(getAuthHeaders());
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode json = objectMapper.readTree(response.getBody());
                json.fields().forEachRemaining(entry -> {
                    JsonNode valueArray = entry.getValue();
                    if (valueArray.isArray() && !valueArray.isEmpty()) {
                        result.put(entry.getKey(), valueArray.get(0).get("value").asText());
                    }
                });
            }
        } catch (Exception ex) {
            log.error("❌ Retry also failed for telemetry: {}", ex.getMessage());
        }
    }

    // ==================== Attributes ====================

    public Map<String, Object> getAttributes(String scope) {
        return getAttributes(scope, config.getDeviceId());
    }

    public Map<String, Object> getAttributes(String scope, String deviceId) {
        String url = config.getUrl()
                + "/api/plugins/telemetry/DEVICE/"
                + deviceId
                + "/values/attributes/" + scope;

        log.debug("Fetching {} attributes from: {}", scope, url);
        Map<String, Object> result = new HashMap<>();

        try {
            HttpEntity<Void> entity = new HttpEntity<>(getAuthHeaders());
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode jsonArray = objectMapper.readTree(response.getBody());
                if (jsonArray.isArray()) {
                    for (JsonNode attr : jsonArray) {
                        String key = attr.get("key").asText();
                        JsonNode valueNode = attr.get("value");
                        result.put(key, valueNode.isTextual() ? valueNode.asText() : valueNode.toString());
                    }
                }
                log.debug("✅ Fetched {} {} attributes", result.size(), scope);
            }
        } catch (RestClientException e) {
            log.error("❌ Failed to fetch {} attributes: {}", scope, e.getMessage());
        } catch (Exception e) {
            log.error("❌ Error parsing {} attributes: {}", scope, e.getMessage());
        }

        return result;
    }

    // ==================== History (for Charts) ====================

    public Map<String, List<Map<String, Object>>> getHistory(String key, long startTs, long endTs) {
        String url = config.getUrl()
                + "/api/plugins/telemetry/DEVICE/"
                + config.getDeviceId()
                + "/values/timeseries?keys=" + key
                + "&startTs=" + startTs
                + "&endTs=" + endTs
                + "&limit=100";

        log.debug("Fetching history for key '{}' from {} to {}", key, startTs, endTs);
        Map<String, List<Map<String, Object>>> result = new HashMap<>();

        try {
            HttpEntity<Void> entity = new HttpEntity<>(getAuthHeaders());
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode json = objectMapper.readTree(response.getBody());
                json.fields().forEachRemaining(entry -> {
                    List<Map<String, Object>> points = new java.util.ArrayList<>();
                    for (JsonNode point : entry.getValue()) {
                        Map<String, Object> p = new HashMap<>();
                        p.put("ts", point.get("ts").asLong());
                        p.put("value", point.get("value").asText());
                        points.add(p);
                    }
                    result.put(entry.getKey(), points);
                });
                log.debug("✅ Fetched {} history points for '{}'",
                        result.values().stream().mapToInt(List::size).sum(), key);
            }
        } catch (Exception e) {
            log.error("❌ Error fetching history for '{}': {}", key, e.getMessage());
        }

        return result;
    }

    // ==================== Retry Helper ====================

    private void retryWithFreshToken(Runnable action) {
        try {
            log.info("Retrying with fresh token...");
            this.jwtToken = null;
            authenticate();
            action.run();
        } catch (Exception e) {
            log.error("❌ Retry with fresh token failed: {}", e.getMessage());
        }
    }
}
