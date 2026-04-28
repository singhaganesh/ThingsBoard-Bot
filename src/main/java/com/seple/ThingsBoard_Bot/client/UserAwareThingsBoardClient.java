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

/**
 * A ThingsBoard API client that uses the logged-in user's JWT token.
 */
@Slf4j
@Component
public class UserAwareThingsBoardClient {

    private final ThingsBoardConfig config;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public UserAwareThingsBoardClient(ThingsBoardConfig config,
            @Qualifier("thingsBoardRestTemplate") RestTemplate restTemplate) {
        this.config = config;
        this.restTemplate = restTemplate;
        this.objectMapper = new ObjectMapper();
    }

    // ==================== Auth Headers ====================

    private HttpHeaders getHeaders(String userToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Authorization", "Bearer " + userToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    // ==================== User Info ====================

    public String getCustomerId(String userToken) {
        String url = config.getUrl() + "/api/auth/user";
        try {
            HttpEntity<Void> entity = new HttpEntity<>(getHeaders(userToken));
            ResponseEntity<String> response = exchangeWithRetry(url, HttpMethod.GET, entity, String.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode json = objectMapper.readTree(response.getBody());
                JsonNode customerIdNode = json.get("customerId");
                if (customerIdNode != null && customerIdNode.has("id")) {
                    return customerIdNode.get("id").asText();
                }
            }
        } catch (Exception e) {
            log.error("Error fetching user info: {}", e.getMessage());
        }
        return null;
    }

    public List<Map<String, String>> getUserDevices(String userToken) {
        int pageSize = config.getDevicePageSize() > 0 ? config.getDevicePageSize() : 100;
        return getUserDevicesPaged(userToken, pageSize);
    }

    public List<Map<String, String>> getUserDevicesPaged(String userToken, int pageSize) {
        log.info("Starting scoped device fetch for user token...");
        
        // Step 1: Get User Info to find Customer ID
        String customerId = getCustomerId(userToken);
        log.info("User associated with customerId: {}", customerId);

        if (customerId != null && !customerId.equals("13814000-1dd2-11b2-8080-808080808080")) {
            // Priority 1: Customer-specific devices by ID (Most reliable for assigned devices)
            List<Map<String, String>> devices = getDevicesByCustomerIdPaged(userToken, customerId, pageSize);
            if (!devices.isEmpty()) {
                log.info("Strict scope: Found {} devices assigned to customer {}", devices.size(), customerId);
                return devices;
            }
        }

        // Priority 2: Direct customer devices endpoint
        List<Map<String, String>> customerDevices = getCustomerDevicesPaged(userToken, pageSize);
        if (!customerDevices.isEmpty()) {
            log.info("Strict scope: Found {} devices via customer endpoint", customerDevices.size());
            return customerDevices;
        }

        // Priority 3: Fallback only if no customer scoping found (Tenant Admin case)
        List<Map<String, String>> tenantDevices = getTenantDevicesPaged(userToken, pageSize);
        log.info("Final scope: Found {} tenant devices", tenantDevices.size());
        return tenantDevices;
    }

    private List<Map<String, String>> getDevicesByCustomerIdPaged(String userToken, String customerId, int pageSize) {
        List<Map<String, String>> devices = new ArrayList<>();
        int page = 0;
        boolean hasNext = true;
        while (hasNext) {
            String url = config.getUrl() + "/api/customer/" + customerId + "/devices?pageSize=" + pageSize + "&page=" + page;
            try {
                HttpEntity<Void> entity = new HttpEntity<>(getHeaders(userToken));
                ResponseEntity<String> response = exchangeWithRetry(url, HttpMethod.GET, entity, String.class);
                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    JsonNode json = objectMapper.readTree(response.getBody());
                    JsonNode dataArray = json.get("data");
                    if (dataArray != null && dataArray.isArray()) {
                        for (JsonNode deviceNode : dataArray) {
                            devices.add(parseDeviceNode(deviceNode));
                        }
                    }
                    hasNext = json.path("hasNext").asBoolean(false);
                    page++;
                } else {
                    hasNext = false;
                }
            } catch (Exception e) {
                log.error("Error fetching devices for customer {}: {}", customerId, e.getMessage());
                hasNext = false;
            }
        }
        return devices;
    }

    private Map<String, String> parseDeviceNode(JsonNode deviceNode) {
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
        return deviceMap;
    }

    public Map<String, Object> queryUserDevicesPage(String userToken, int page, int pageSize) {
        String url = config.getUrl() + "/api/entitiesQuery/find";
        List<Map<String, String>> devices = new ArrayList<>();
        boolean hasNext = false;

        try {
            Map<String, Object> payload = new HashMap<>();
            Map<String, String> entityFilter = new HashMap<>();
            entityFilter.put("type", "entityType");
            entityFilter.put("entityType", "DEVICE");
            payload.put("entityFilter", entityFilter);

            Map<String, Object> pageLink = new HashMap<>();
            pageLink.put("pageSize", pageSize);
            pageLink.put("page", page);
            payload.put("pageLink", pageLink);

            List<Map<String, String>> entityFields = new ArrayList<>();
            Map<String, String> nameField = new HashMap<>();
            nameField.put("type", "ENTITY_FIELD");
            nameField.put("key", "name");
            entityFields.add(nameField);

            Map<String, String> typeField = new HashMap<>();
            typeField.put("type", "ENTITY_FIELD");
            typeField.put("key", "type");
            entityFields.add(typeField);

            payload.put("entityFields", entityFields);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, getHeaders(userToken));
            ResponseEntity<String> response = exchangeWithRetry(url, HttpMethod.POST, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode json = objectMapper.readTree(response.getBody());
                JsonNode dataArray = json.get("data");
                hasNext = json.path("hasNext").asBoolean(false);
                if (dataArray != null && dataArray.isArray()) {
                    for (JsonNode deviceNode : dataArray) {
                        Map<String, String> deviceMap = new HashMap<>();
                        JsonNode idNode = deviceNode.has("entityId") ? deviceNode.get("entityId") : deviceNode.get("id");
                        if (idNode != null) {
                            deviceMap.put("id", idNode.has("id") ? idNode.get("id").asText() : idNode.asText());
                        }
                        if (deviceNode.has("latest") && deviceNode.get("latest").has("ENTITY_FIELD")) {
                            JsonNode fields = deviceNode.get("latest").get("ENTITY_FIELD");
                            if (fields.has("name")) {
                                deviceMap.put("name", fields.get("name").get("value").asText());
                            }
                            if (fields.has("type")) {
                                deviceMap.put("type", fields.get("type").get("value").asText());
                            }
                        }
                        devices.add(deviceMap);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error fetching user devices page {}: {}", page, e.getMessage());
        }

        Map<String, Object> result = new HashMap<>();
        result.put("page", page);
        result.put("hasNext", hasNext);
        result.put("data", devices);
        return result;
    }

    public JsonNode queryEntityData(String userToken, JsonNode payload) {
        String url = config.getUrl() + "/api/queries/entityData";
        try {
            HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(payload), getHeaders(userToken));
            ResponseEntity<String> response = exchangeWithRetry(url, HttpMethod.POST, entity, String.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return objectMapper.readTree(response.getBody());
            }
        } catch (Exception e) {
            log.error("User entity query failed: {}", e.getMessage());
        }
        return objectMapper.createObjectNode();
    }

    private List<Map<String, String>> getTenantDevicesPaged(String userToken, int pageSize) {
        List<Map<String, String>> devices = new ArrayList<>();
        int page = 0;
        boolean hasNext = true;
        while (hasNext) {
            String url = config.getUrl() + "/api/tenant/devices?pageSize=" + pageSize + "&page=" + page;
            try {
                HttpEntity<Void> entity = new HttpEntity<>(getHeaders(userToken));
                ResponseEntity<String> response = exchangeWithRetry(url, HttpMethod.GET, entity, String.class);
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
                    hasNext = json.path("hasNext").asBoolean(false);
                    page++;
                } else {
                    hasNext = false;
                }
            } catch (Exception e) {
                log.error("Fallback tenant devices page failed: {}", e.getMessage());
                hasNext = false;
            }
        }
        return devices;
    }

    private List<Map<String, String>> getCustomerDevicesPaged(String userToken, int pageSize) {
        List<Map<String, String>> devices = new ArrayList<>();
        int page = 0;
        boolean hasNext = true;
        while (hasNext) {
            String url = config.getUrl() + "/api/customer/devices?pageSize=" + pageSize + "&page=" + page;
            try {
                HttpEntity<Void> entity = new HttpEntity<>(getHeaders(userToken));
                ResponseEntity<String> response = exchangeWithRetry(url, HttpMethod.GET, entity, String.class);
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
                    hasNext = json.path("hasNext").asBoolean(false);
                    page++;
                } else {
                    hasNext = false;
                }
            } catch (Exception e) {
                hasNext = false;
            }
        }
        return devices;
    }

    // ==================== Telemetry ====================

    public Map<String, Object> getTelemetry(String userToken, String deviceId) {
        String url = config.getUrl() + "/api/plugins/telemetry/DEVICE/" + deviceId + "/values/timeseries";
        log.debug("Fetching all telemetry for device {} with user token", deviceId);
        Map<String, Object> result = new HashMap<>();

        try {
            HttpEntity<Void> entity = new HttpEntity<>(getHeaders(userToken));
            ResponseEntity<String> response = exchangeWithRetry(url, HttpMethod.GET, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode json = objectMapper.readTree(response.getBody());
                json.fields().forEachRemaining(entry -> {
                    JsonNode valueArray = entry.getValue();
                    if (valueArray.isArray() && !valueArray.isEmpty()) {
                        JsonNode valueNode = valueArray.get(0).get("value");
                        if (valueNode != null && !valueNode.isNull()) {
                            String value = valueNode.isTextual() ? valueNode.asText() : valueNode.toString();
                            flattenAndPut(result, entry.getKey(), value, "User Telemetry");
                        }
                    }
                });
                log.info("Fetched {} valid telemetry data points for device {}", result.size(), deviceId);
            }
        } catch (Exception e) {
            log.error("Failed to fetch telemetry for device {}: {}", deviceId, e.getMessage());
        }
        return result;
    }

    public Map<String, Object> getTelemetry(String userToken, String deviceId, List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return getTelemetry(userToken, deviceId);
        }
        String url = config.getUrl() + "/api/plugins/telemetry/DEVICE/" + deviceId
                + "/values/timeseries?keys=" + String.join(",", keys);
        log.debug("Fetching selected telemetry keys ({}) for device {}", keys.size(), deviceId);
        Map<String, Object> result = new HashMap<>();
        try {
            HttpEntity<Void> entity = new HttpEntity<>(getHeaders(userToken));
            ResponseEntity<String> response = exchangeWithRetry(url, HttpMethod.GET, entity, String.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode json = objectMapper.readTree(response.getBody());
                json.fields().forEachRemaining(entry -> {
                    JsonNode valueArray = entry.getValue();
                    if (valueArray.isArray() && !valueArray.isEmpty()) {
                        JsonNode valueNode = valueArray.get(0).get("value");
                        if (valueNode != null && !valueNode.isNull()) {
                            String value = valueNode.isTextual() ? valueNode.asText() : valueNode.toString();
                            flattenAndPut(result, entry.getKey(), value, "User Telemetry");
                        }
                    }
                });
            }
        } catch (Exception e) {
            log.error("Failed to fetch selected telemetry for device {}: {}", deviceId, e.getMessage());
        }
        return result;
    }

    // ==================== Attributes ====================

    public Map<String, Object> getAttributes(String userToken, String scope, String deviceId) {
        String url = config.getUrl() + "/api/plugins/telemetry/DEVICE/" + deviceId + "/values/attributes/" + scope;
        log.debug("Fetching all {} attributes for device {} with user token", scope, deviceId);
        Map<String, Object> result = new HashMap<>();

        try {
            HttpEntity<Void> entity = new HttpEntity<>(getHeaders(userToken));
            ResponseEntity<String> response = exchangeWithRetry(url, HttpMethod.GET, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode jsonArray = objectMapper.readTree(response.getBody());
                if (jsonArray.isArray()) {
                    for (JsonNode attr : jsonArray) {
                        String key = attr.get("key").asText();
                        JsonNode valueNode = attr.get("value");
                        String value = valueNode.isTextual() ? valueNode.asText() : valueNode.toString();
                        flattenAndPut(result, key, value, "User Attribute - " + scope);
                    }
                }
                log.info("Fetched {} valid {} attributes for device {}", result.size(), scope, deviceId);
            }
        } catch (Exception e) {
            log.error("Failed to fetch {} attributes for device {}: {}", scope, deviceId, e.getMessage());
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
        }
        result.put(key, value);
        log.info("[{}] {} = {}", prefix, key, value);
    }

    // ==================== History ====================

    public Map<String, List<Map<String, Object>>> getHistory(String userToken, String deviceId, String key, long startTs,
            long endTs) {
        String url = config.getUrl() + "/api/plugins/telemetry/DEVICE/" + deviceId + "/values/timeseries?keys=" + key
                + "&startTs=" + startTs + "&endTs=" + endTs + "&limit=100";
        Map<String, List<Map<String, Object>>> result = new HashMap<>();
        try {
            HttpEntity<Void> entity = new HttpEntity<>(getHeaders(userToken));
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
            }
        } catch (Exception e) {
            log.error("Error fetching history: {}", e.getMessage());
        }
        return result;
    }

    // ==================== Raw Data Fetchers (Un-restructured) ====================

    public Object getRawAttributes(String userToken, String scope, String deviceId) {
        String url = config.getUrl() + "/api/plugins/telemetry/DEVICE/" + deviceId + "/values/attributes/" + scope;
        try {
            HttpEntity<Void> entity = new HttpEntity<>(getHeaders(userToken));
            ResponseEntity<String> response = exchangeWithRetry(url, HttpMethod.GET, entity, String.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode node = objectMapper.readTree(response.getBody());
                return objectMapper.convertValue(node, Object.class);
            }
        } catch (Exception e) {
            log.error("Failed to fetch raw {} attributes for device {}: {}", scope, deviceId, e.getMessage());
        }
        return new ArrayList<>();
    }

    public Object getRawTelemetry(String userToken, String deviceId) {
        String url = config.getUrl() + "/api/plugins/telemetry/DEVICE/" + deviceId + "/values/timeseries";
        try {
            HttpEntity<Void> entity = new HttpEntity<>(getHeaders(userToken));
            ResponseEntity<String> response = exchangeWithRetry(url, HttpMethod.GET, entity, String.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode node = objectMapper.readTree(response.getBody());
                return objectMapper.convertValue(node, Object.class);
            }
        } catch (Exception e) {
            log.error("Failed to fetch raw telemetry for device {}: {}", deviceId, e.getMessage());
        }
        return new HashMap<>();
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
                boolean retryable = e.getStatusCode().is5xxServerError() || code == 429;
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
