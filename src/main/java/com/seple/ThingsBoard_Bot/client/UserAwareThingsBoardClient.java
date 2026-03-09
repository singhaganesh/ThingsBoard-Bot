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
 * <p>
 * This ensures that API calls are scoped to only the devices
 * that the user has access to (ThingsBoard enforces this server-side).
 * </p>
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

    /**
     * Build HTTP headers using the USER's JWT token (not tenant-admin).
     */
    private HttpHeaders getHeaders(String userToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Authorization", "Bearer " + userToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    // ==================== User Info ====================

    /**
     * Extract the user's customerId from the ThingsBoard /api/auth/user endpoint.
     * This is needed to fetch customer-scoped devices.
     */
    public String getCustomerId(String userToken) {
        String url = config.getUrl() + "/api/auth/user";
        log.debug("Fetching user info from: {}", url);

        try {
            HttpEntity<Void> entity = new HttpEntity<>(getHeaders(userToken));
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode json = objectMapper.readTree(response.getBody());
                JsonNode customerIdNode = json.get("customerId");
                if (customerIdNode != null && customerIdNode.has("id")) {
                    String customerId = customerIdNode.get("id").asText();
                    log.info("✅ Resolved customerId: {}", customerId);
                    return customerId;
                }
                // If user is a tenant admin, they won't have a customerId
                log.warn("⚠️ User has no customerId — may be a tenant admin");
            }
        } catch (Exception e) {
            log.error("❌ Error fetching user info: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Get devices assigned to the user.
     * Uses the universal /api/entitiesQuery/find endpoint which properly resolves
     * RBAC permissions, Entity Groups (PE), and Customer associations (CE).
     */
    public List<Map<String, String>> getUserDevices(String userToken) {
        String url = config.getUrl() + "/api/entitiesQuery/find";
        log.info("Fetching devices for user using entitiesQuery...");
        List<Map<String, String>> devices = new ArrayList<>();

        try {
            // Build the complex Entity Data Query payload
            Map<String, Object> payload = new HashMap<>();
            
            // 1. Entity Filter
            Map<String, String> entityFilter = new HashMap<>();
            entityFilter.put("type", "entityType");
            entityFilter.put("entityType", "DEVICE");
            payload.put("entityFilter", entityFilter);

            // 2. Page Link
            Map<String, Object> pageLink = new HashMap<>();
            pageLink.put("pageSize", 1000);
            pageLink.put("page", 0);
            payload.put("pageLink", pageLink);

            // 3. Entity Fields to return
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
                        // Extract ID: ThingsBoard might return {"entityId": {"id": "..."}} OR {"id": {"id": "..."}} OR just {"id": "uuid..."}
                        JsonNode idNode = null;
                        if (deviceNode.has("entityId")) {
                            idNode = deviceNode.get("entityId");
                        } else if (deviceNode.has("id")) {
                            idNode = deviceNode.get("id");
                        }

                        if (idNode != null) {
                            if (idNode.has("id")) {
                                deviceMap.put("id", idNode.get("id").asText());
                            } else if (idNode.isTextual()) {
                                deviceMap.put("id", idNode.asText());
                            }
                        }
                        
                        // Fields returned might be arrays in latest -> ENTITY_FIELD
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
                log.info("✅ Fetched {} devices via entitiesQuery", devices.size());
                
                // Fallback for Tenant Admin or different setups
                if (devices.isEmpty()) {
                    log.warn("entitiesQuery returned 0 devices. Trying fallback to /api/tenant/devices...");
                    return fallbackTenantDevices(userToken);
                }
            }
        } catch (Exception e) {
            log.error("❌ Error fetching user devices via entitiesQuery: {}", e.getMessage());
            // Last resort fallback
            return fallbackTenantDevices(userToken);
        }

        return devices;
    }

    private List<Map<String, String>> fallbackTenantDevices(String userToken) {
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
                log.info("✅ Fetched {} devices via tenant fallback", devices.size());
            }
        } catch (Exception e) {
            log.error("❌ Fallback tenant devices failed: {}", e.getMessage());
        }
        return devices;
    }

    // ==================== Telemetry ====================

    /**
     * Fetch latest telemetry for a specific device using the user's token.
     */
    public Map<String, Object> getTelemetry(String userToken, String deviceId) {
        String url = config.getUrl()
                + "/api/plugins/telemetry/DEVICE/"
                + deviceId
                + "/values/timeseries";

        log.debug("Fetching telemetry for device {} with user token", deviceId);
        Map<String, Object> result = new HashMap<>();

        try {
            HttpEntity<Void> entity = new HttpEntity<>(getHeaders(userToken));
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode json = objectMapper.readTree(response.getBody());
                json.fields().forEachRemaining(entry -> {
                    JsonNode valueArray = entry.getValue();
                    if (valueArray.isArray() && !valueArray.isEmpty()) {
                        result.put(entry.getKey(), valueArray.get(0).get("value").asText());
                    }
                });
                log.debug("✅ Fetched {} telemetry keys for device {}", result.size(), deviceId);
            }
        } catch (Exception e) {
            log.error("❌ Failed to fetch telemetry for device {}: {}", deviceId, e.getMessage());
        }

        return result;
    }

    // ==================== Attributes ====================

    /**
     * Fetch attributes for a specific device and scope using the user's token.
     */
    public Map<String, Object> getAttributes(String userToken, String scope, String deviceId) {
        String url = config.getUrl()
                + "/api/plugins/telemetry/DEVICE/"
                + deviceId
                + "/values/attributes/" + scope;

        log.debug("Fetching {} attributes for device {} with user token", scope, deviceId);
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
                        result.put(key, valueNode.isTextual() ? valueNode.asText() : valueNode.toString());
                    }
                }
                log.debug("✅ Fetched {} {} attributes for device {}", result.size(), scope, deviceId);
            }
        } catch (Exception e) {
            log.error("❌ Failed to fetch {} attributes for device {}: {}", scope, deviceId, e.getMessage());
        }

        return result;
    }

    // ==================== History ====================

    /**
     * Fetch historical telemetry for a specific device and key using the user's token.
     */
    public Map<String, List<Map<String, Object>>> getHistory(String userToken, String deviceId, String key, long startTs, long endTs) {
        String url = config.getUrl()
                + "/api/plugins/telemetry/DEVICE/"
                + deviceId
                + "/values/timeseries?keys=" + key
                + "&startTs=" + startTs
                + "&endTs=" + endTs
                + "&limit=100";

        log.debug("Fetching history for key '{}' from {} to {} for device {}", key, startTs, endTs, deviceId);
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
                log.debug("✅ Fetched {} history points for '{}'",
                        result.values().stream().mapToInt(List::size).sum(), key);
            }
        } catch (Exception e) {
            log.error("❌ Error fetching history for '{}': {}", key, e.getMessage());
        }

        return result;
    }
}
