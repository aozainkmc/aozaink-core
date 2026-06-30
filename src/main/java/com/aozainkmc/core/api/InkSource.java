package com.aozainkmc.core.api;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public record InkSource(
    String sourceId,
    float powerMultiplier,
    String tierLabel,
    float tierRank,
    Map<String, Object> extra
) {
    public InkSource {
        if (sourceId == null) sourceId = "unknown";
        if (tierLabel == null) tierLabel = "unknown";
        extra = extra == null ? Collections.emptyMap() : Collections.unmodifiableMap(new HashMap<>(extra));
    }

    public static InkSource simple(String sourceId) {
        return new InkSource(sourceId, 1.0f, "unknown", 0.0f, Collections.emptyMap());
    }
}
