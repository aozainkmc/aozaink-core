package com.aozainkmc.core.ocr;

import com.aozainkmc.core.api.InkCandidate;
import java.util.List;

public record TrajectoryResult(
    List<InkCandidate> candidates,
    int simplifiedStrokeCount,
    int simplifiedPointCount,
    long writingDurationMs
) {
}
