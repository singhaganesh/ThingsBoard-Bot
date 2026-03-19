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
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seple.ThingsBoard_Bot.config.ThingsBoardConfig;

import lombok.extern.slf4j.Slf4j;

/**
 * A ThingsBoard API client that uses the LOGGED-IN USER's JWT token
 * instead of the tenant-admin token.
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
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode json = objectMapper.readTree(response.getBody());
                JsonNode customerIdNode = json.get("customerId");
                if (customerIdNode != null && customerIdNode.has("id")) {
                    return customerIdNode.get("id").asText();
                }
            }
        } catch (Exception e) {
            log.error("❌ Error fetching user info: {}", e.getMessage());
        }
        return null;
    }

    public List<Map<String, String>> getUserDevices(String userToken) {
        String url = config.getUrl() + "/api/entitiesQuery/find";
        log.info("Fetching devices for user using entitiesQuery...");
        List<Map<String, String>> devices = new ArrayList<>();

        try {
            Map<String, Object> payload = new HashMap<>();
            Map<String, String> entityFilter = new HashMap<>();
            entityFilter.put("type", "entityType");
            entityFilter.put("entityType", "DEVICE");
            payload.put("entityFilter", entityFilter);

            Map<String, Object> pageLink = new HashMap<>();
            pageLink.put("pageSize", 1000);
            pageLink.put("page", 0);
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
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode json = objectMapper.readTree(response.getBody());
                JsonNode dataArray = json.get("data");
                if (dataArray != null && dataArray.isArray()) {
                    for (JsonNode deviceNode : dataArray) {
                        Map<String, String> deviceMap = new HashMap<>();
                        JsonNode idNode = deviceNode.has("entityId") ? deviceNode.get("entityId") : deviceNode.get("id");
                        if (idNode != null) {
                            deviceMap.put("id", idNode.has("id") ? idNode.get("id").asText() : idNode.asText());
                        }
                        if (deviceNode.has("latest") && deviceNode.get("latest").has("ENTITY_FIELD")) {
                            JsonNode fields = deviceNode.get("latest").get("ENTITY_FIELD");
                            if (fields.has("name")) deviceMap.put("name", fields.get("name").get("value").asText());
                            if (fields.has("type")) deviceMap.put("type", fields.get("type").get("value").asText());
                        }
                        devices.add(deviceMap);
                    }
                }
                log.info("✅ Fetched {} devices via entitiesQuery", devices.size());
                if (devices.isEmpty()) return fallbackTenantDevices(userToken);
            }
        } catch (Exception e) {
            log.error("❌ Error fetching user devices via entitiesQuery: {}", e.getMessage());
            return fallbackTenantDevices(userToken);
        }
        return devices;
    }

    private List<Map<String, String>> fallbackTenantDevices(String userToken) {
        List<Map<String, String>> customerDevices = getCustomerDevices(userToken);
        if (!customerDevices.isEmpty()) return customerDevices;

        String url = config.getUrl() + "/api/tenant/devices?pageSize=1000&page=0";
        List<Map<String, String>> devices = new ArrayList<>();
        try {
            HttpEntity<Void> entity = new HttpEntity<>(getHeaders(userToken));
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode json = objectMapper.readTree(response.getBody());
                JsonNode dataArray = json.get("data");
                if (dataArray != null && dataArray.isArray()) {
                    for (JsonNode deviceNode : dataArray) {
                        Map<String, String> deviceMap = new HashMap<>();
                        if (deviceNode.has("id")) deviceMap.put("id", deviceNode.get("id").get("id").asText());
                        if (deviceNode.has("name")) deviceMap.put("name", deviceNode.get("name").asText());
                        if (deviceNode.has("type")) deviceMap.put("type", deviceNode.get("type").asText());
                        devices.add(deviceMap);
                    }
                }
            }
        } catch (Exception e) {
            log.error("❌ Fallback tenant devices failed: {}", e.getMessage());
        }
        return devices;
    }

    private List<Map<String, String>> getCustomerDevices(String userToken) {
        String url = config.getUrl() + "/api/customer/devices?pageSize=1000&page=0";
        List<Map<String, String>> devices = new ArrayList<>();
        try {
            HttpEntity<Void> entity = new HttpEntity<>(getHeaders(userToken));
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode json = objectMapper.readTree(response.getBody());
                JsonNode dataArray = json.get("data");
                if (dataArray != null && dataArray.isArray()) {
                    for (JsonNode deviceNode : dataArray) {
                        Map<String, String> deviceMap = new HashMap<>();
                        if (deviceNode.has("id")) deviceMap.put("id", deviceNode.get("id").get("id").asText());
                        if (deviceNode.has("name")) deviceMap.put("name", deviceNode.get("name").asText());
                        if (deviceNode.has("type")) deviceMap.put("type", deviceNode.get("type").asText());
                        devices.add(deviceMap);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Customer devices API not available");
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
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode json = objectMapper.readTree(response.getBody());
                json.fields().forEachRemaining(entry -> {
                    JsonNode valueArray = entry.getValue();
                    if (valueArray.isArray() && !valueArray.isEmpty()) {
                        JsonNode valueNode = valueArray.get(0).get("value");
                        if (valueNode != null && !valueNode.isNull()) {
                            flattenAndPut(result, entry.getKey(), valueNode.asText(), "User Telemetry");
                        }
                    }
                });
                log.info("✅ Fetched {} valid telemetry data points for device {}", result.size(), deviceId);
            }
        } catch (Exception e) {
            log.error("❌ Failed to fetch telemetry for device {}: {}", deviceId, e.getMessage());
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
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

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
                log.info("✅ Fetched {} valid {} attributes for device {}", result.size(), scope, deviceId);
            }
        } catch (Exception e) {
            log.error("❌ Failed to fetch {} attributes for device {}: {}", scope, deviceId, e.getMessage());
        }
        return result;
    }

    private void flattenAndPut(Map<String, Object> result, String key, String value, String prefix) {
        if (value == null || value.isBlank() || "null".equalsIgnoreCase(value)) return;
        try {
            if ((value.startsWith("{") && value.endsWith("}")) || (value.startsWith("[") && value.endsWith("]"))) {
                JsonNode node = objectMapper.readTree(value);
                if (node.isObject()) {
                    node.fields().forEachRemaining(entry -> {
                        String subKey = key + "_" + entry.getKey();
                        String subValue = entry.getValue().isTextual() ? entry.getValue().asText() : entry.getValue().toString();
                        result.put(subKey, subValue);
                        log.info(" [{}] {} = {}", prefix, subKey, subValue);
                    });
                    return;
                }
            }
        } catch (Exception e) {}
        result.put(key, value);
        log.info("📡 [{}] {} = {}", prefix, key, value);
    }

    // ==================== History ====================

    public Map<String, List<Map<String, Object>>> getHistory(String userToken, String deviceId, String key, long startTs, long endTs) {
        String url = config.getUrl() + "/api/plugins/telemetry/DEVICE/" + deviceId + "/values/timeseries?keys=" + key + "&startTs=" + startTs + "&endTs=" + endTs + "&limit=100";
        Map<String, List<Map<String, Object>>> result = new HashMap<>();
        try {
            HttpEntity<Void> entity = new HttpEntity<>(getHeaders(userToken));
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
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
            log.error("❌ Error fetching history: {}", e.getMessage());
        }
        return result;
    }
}
