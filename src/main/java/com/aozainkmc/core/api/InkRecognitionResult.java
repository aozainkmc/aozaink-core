package com.aozainkmc.core.api;

import java.util.Collections;
import java.util.List;

public record InkRecognitionResult(
    String topGlyph,
    float confidence,
    List<InkCandidate> candidates,
    int simplifiedStrokeCount,
    int simplifiedPointCount,
    long writingDurationMs
) {
    public InkRecognitionResult {
        if (topGlyph == null) topGlyph = "";
        if (candidates == null) candidates = Collections.emptyList();
        else candidates = Collections.unmodifiableList(candidates);
    }

    public static InkRecognitionResult empty() {
        return new InkRecognitionResult("", 0.0f, Collections.emptyList(), 0, 0, 0L);
    }
}
