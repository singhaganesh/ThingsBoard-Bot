package com.seple.ThingsBoard_Bot.util;

import java.util.HashMap;
import java.util.Map;

/**
 * Legacy flat-map context filter retained only for backward compatibility.
 * The active chat path now uses structured branch snapshots instead.
 */
@Deprecated(forRemoval = false)
public class ContextFilterUtil {

    private ContextFilterUtil() {
    }

    public static Map<String, Object> filterAttributes(Map<String, Object> rawData) {
        return rawData == null ? new HashMap<>() : new HashMap<>(rawData);
    }
}
