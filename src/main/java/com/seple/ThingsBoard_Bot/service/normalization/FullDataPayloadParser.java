package com.seple.ThingsBoard_Bot.service.normalization;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class FullDataPayloadParser {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public ParsedPayload parse(String json) throws IOException {
        Map<String, Object> raw = objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {
        });

        Map<String, Map<String, Object>> branches = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        Map<String, String> caseInsensitiveIndex = new LinkedHashMap<>();

        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            String fullKey = entry.getKey();
            String lower = fullKey.toLowerCase(Locale.ROOT);
            if (caseInsensitiveIndex.containsKey(lower) && !caseInsensitiveIndex.get(lower).equals(fullKey)) {
                warnings.add("Case-conflicting keys: " + caseInsensitiveIndex.get(lower) + " and " + fullKey);
            } else {
                caseInsensitiveIndex.put(lower, fullKey);
            }

            if (!fullKey.contains(".")) {
                warnings.add("Key without branch prefix: " + fullKey);
                continue;
            }

            String[] parts = fullKey.split("\\.", 2);
            branches.computeIfAbsent(parts[0], ignored -> new LinkedHashMap<>())
                    .put(parts[1], entry.getValue());
        }

        return new ParsedPayload(branches, warnings);
    }

    public record ParsedPayload(Map<String, Map<String, Object>> branches, List<String> warnings) {
    }
}
