package com.seple.ThingsBoard_Bot.client;

import java.util.ArrayList;
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
import org.springframework.web.client.HttpStatusCodeException;
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
                this.tokenExpiryTime = System.currentTimeMillis() + (10 * 60 * 1000);
                log.info("Authenticated with ThingsBoard successfully");
            } else {
                log.error("ThingsBoard authentication failed: {}", response.getStatusCode());
            }
        } catch (RestClientException e) {
            log.error("Failed to connect to ThingsBoard: {}", e.getMessage());
            throw new RuntimeException("ThingsBoard authentication failed", e);
        } catch (Exception e) {
            log.error("Error parsing authentication response: {}", e.getMessage());
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
        int pageSize = config.getDevicePageSize() > 0 ? config.getDevicePageSize() : 100;
        return getAllDevicesPaged(pageSize);
    }

    public List<Map<String, String>> getAllDevicesPaged(int pageSize) {
        List<Map<String, String>> devices = new ArrayList<>();
        int page = 0;
        boolean hasNext = true;

        while (hasNext) {
            Map<String, Object> pageResult = fetchDevicesPage(page, pageSize);
            @SuppressWarnings("unchecked")
            List<Map<String, String>> rows = (List<Map<String, String>>) pageResult.getOrDefault("data", List.of());
            devices.addAll(rows);
            hasNext = Boolean.TRUE.equals(pageResult.get("hasNext"));
            page++;
        }

        log.info("Fetched {} devices from tenant (paged)", devices.size());
        return devices;
    }

    public Map<String, Object> fetchDevicesPage(int page, int pageSize) {
        String url = config.getUrl() + "/api/tenant/devices?pageSize=" + pageSize + "&page=" + page;
        Map<String, Object> result = new HashMap<>();
        List<Map<String, String>> devices = new ArrayList<>();

        try {
            HttpEntity<Void> entity = new HttpEntity<>(getAuthHeaders());
            ResponseEntity<String> response = exchangeWithRetry(url, HttpMethod.GET, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode json = objectMapper.readTree(response.getBody());
                JsonNode dataArray = json.get("data");
                if (dataArray != null && dataArray.isArray()) {
                    for (JsonNode deviceNode : dataArray) {
                        Map<String, String> deviceMap = new HashMap<>();
                        if (deviceNode.has("id")) {
                            JsonNode idNode = deviceNode.get("id");
                            deviceMap.put("id", idNode.has("id") ? idNode.get("id").asText() : idNode.asText());
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
                result.put("hasNext", json.path("hasNext").asBoolean(false));
            } else {
                result.put("hasNext", false);
            }
        } catch (Exception e) {
            log.error("Error fetching all devices page {}: {}", page, e.getMessage());
            result.put("hasNext", false);
        }

        result.put("data", devices);
        result.put("page", page);
        return result;
    }

    public JsonNode queryEntityData(JsonNode payload) {
        String url = config.getUrl() + "/api/queries/entityData";

        try {
            HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(payload), getAuthHeaders());
            ResponseEntity<String> response = exchangeWithRetry(url, HttpMethod.POST, entity, String.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return objectMapper.readTree(response.getBody());
            }
        } catch (Exception e) {
            log.error("Entity query failed: {}", e.getMessage());
        }

        return objectMapper.createObjectNode();
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

        log.debug("Fetching all telemetry for device: {}", deviceId);
        Map<String, Object> result = new HashMap<>();

        try {
            HttpEntity<Void> entity = new HttpEntity<>(getAuthHeaders());
            ResponseEntity<String> response = exchangeWithRetry(url, HttpMethod.GET, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode json = objectMapper.readTree(response.getBody());
                json.fields().forEachRemaining(entry -> {
                    JsonNode valueArray = entry.getValue();
                    if (valueArray.isArray() && !valueArray.isEmpty()) {
                        JsonNode valueNode = valueArray.get(0).get("value");
                        if (valueNode != null && !valueNode.isNull()) {
                            String value = valueNode.asText();
                            flattenAndPut(result, entry.getKey(), value, "Telemetry");
                        }
                    }
                });
                log.info("Fetched {} valid telemetry data points", result.size());
            }
        } catch (RestClientException e) {
            log.error("Failed to fetch telemetry: {}", e.getMessage());
            retryWithFreshToken(() -> fetchTelemetryInto(deviceId, result));
        } catch (Exception e) {
            log.error("Error parsing telemetry: {}", e.getMessage());
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
            ResponseEntity<String> response = exchangeWithRetry(url, HttpMethod.GET, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode json = objectMapper.readTree(response.getBody());
                json.fields().forEachRemaining(entry -> {
                    JsonNode valueArray = entry.getValue();
                    if (valueArray.isArray() && !valueArray.isEmpty()) {
                        JsonNode valueNode = valueArray.get(0).get("value");
                        if (valueNode != null && !valueNode.isNull()) {
                            String value = valueNode.asText();
                            flattenAndPut(result, entry.getKey(), value, "Telemetry");
                        }
                    }
                });
            }
        } catch (Exception ex) {
            log.error("Retry also failed for telemetry: {}", ex.getMessage());
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

        log.debug("Fetching all {} attributes for device: {}", scope, deviceId);
        Map<String, Object> result = new HashMap<>();

        try {
            HttpEntity<Void> entity = new HttpEntity<>(getAuthHeaders());
            ResponseEntity<String> response = exchangeWithRetry(url, HttpMethod.GET, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode jsonArray = objectMapper.readTree(response.getBody());
                if (jsonArray.isArray()) {
                    for (JsonNode attr : jsonArray) {
                        String key = attr.get("key").asText();
                        JsonNode valueNode = attr.get("value");
                        String value = valueNode.isTextual() ? valueNode.asText() : valueNode.toString();
                        flattenAndPut(result, key, value, "Attribute - " + scope);
                    }
                }
                log.info("Fetched {} valid {} attributes", result.size(), scope);
            }
        } catch (RestClientException e) {
            log.error("Failed to fetch {} attributes: {}", scope, e.getMessage());
        } catch (Exception e) {
            log.error("Error parsing {} attributes: {}", scope, e.getMessage());
        }

        return result;
    }

    private void flattenAndPut(Map<String, Object> result, String key, String value, String prefix) {
        if (value == null || value.isBlank() || "null".equalsIgnoreCase(value)) {
            return;
        }

        try {
            if ((value.startsWith("{") && value.endsWith("}")) || (value.startsWith("[") && value.endsWith("]"))) {
                // ✅ Store as parsed object — DO NOT flatten
                JsonNode node = objectMapper.readTree(value);
                result.put(key, objectMapper.convertValue(node, Object.class));
                log.info("[{}] {} = <JSON Object>", prefix, key);
                return;
            }
        } catch (Exception ignored) {
            // fall back to normal put
        }

        result.put(key, value);
        log.info("[{}] {} = {}", prefix, key, value);
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
            ResponseEntity<String> response = exchangeWithRetry(url, HttpMethod.GET, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode json = objectMapper.readTree(response.getBody());
                json.fields().forEachRemaining(entry -> {
                    List<Map<String, Object>> points = new ArrayList<>();
                    for (JsonNode point : entry.getValue()) {
                        Map<String, Object> p = new HashMap<>();
                        p.put("ts", point.get("ts").asLong());
                        p.put("value", point.get("value").asText());
                        points.add(p);
                    }
                    result.put(entry.getKey(), points);
                });
                log.debug("Fetched {} history points for '{}'",
                        result.values().stream().mapToInt(List::size).sum(), key);
            }
        } catch (Exception e) {
            log.error("Error fetching history for '{}': {}", key, e.getMessage());
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
            log.error("Retry with fresh token failed: {}", e.getMessage());
        }
    }

    private <T> ResponseEntity<T> exchangeWithRetry(String url, HttpMethod method, HttpEntity<?> entity,
            Class<T> responseType) {
        int attempts = Math.max(1, config.getRetryAttempts());
        long backoffMs = Math.max(0L, config.getRetryBackoffMs());
        RestClientException lastException = null;

        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return restTemplate.exchange(url, method, entity, responseType);
            } catch (HttpStatusCodeException e) {
                lastException = e;
                int code = e.getStatusCode().value();
                boolean retryable = e.getStatusCode().is5xxServerError() || code == 429 || code == 401;
                if (code == 401 && attempt < attempts) {
                    this.jwtToken = null;
                    authenticate();
                }
                if (!retryable || attempt == attempts) {
                    throw e;
                }
            } catch (RestClientException e) {
                lastException = e;
                if (attempt == attempts) {
                    throw e;
                }
            }

            if (backoffMs > 0) {
                try {
                    Thread.sleep(backoffMs * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        if (lastException != null) {
            throw lastException;
        }
        throw new IllegalStateException("exchangeWithRetry failed without exception");
    }
}
