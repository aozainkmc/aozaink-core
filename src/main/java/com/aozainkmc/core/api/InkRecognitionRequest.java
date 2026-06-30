package com.aozainkmc.core.api;

import java.util.Collections;
import java.util.List;

public record InkRecognitionRequest(
    InkTrace trace,
    float[] imageInput,
    InkRecognitionMode mode,
    List<String> candidateWhitelist,
    long ttlTicks,
    InkSource source
) {
    public InkRecognitionRequest {
        if (mode == null) mode = InkRecognitionMode.OFFLINE;
        if (candidateWhitelist == null) candidateWhitelist = Collections.emptyList();
        if (source == null) source = InkSource.simple("unknown");
        if (ttlTicks <= 0) ttlTicks = 12000L;
    }
}
