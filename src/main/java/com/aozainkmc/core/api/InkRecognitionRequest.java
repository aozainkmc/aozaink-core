package com.aozainkmc.core.api;

import java.util.Collections;
import java.util.List;

public record InkRecognitionRequest(
    InkTrace trace,
    float[] imageInput,
    List<String> candidateWhitelist,
    long ttlTicks,
    InkSource source,
    boolean devMode
) {
    public InkRecognitionRequest(InkTrace trace, float[] imageInput,
                                  List<String> candidateWhitelist, long ttlTicks, InkSource source) {
        this(trace, imageInput, candidateWhitelist, ttlTicks, source, false);
    }

    public InkRecognitionRequest {
        if (candidateWhitelist == null) candidateWhitelist = Collections.emptyList();
        if (source == null) source = InkSource.simple("unknown");
        if (ttlTicks <= 0) ttlTicks = 12000L;
    }
}
