package com.aozainkmc.core.ocr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aozainkmc.core.api.InkPoint;
import com.aozainkmc.core.api.InkTrace;
import java.util.List;
import org.junit.jupiter.api.Test;

class OnnxTrajectoryOcrEngineTest {
    @Test
    void olsingle24LoadsAsOneModelAndUsesDynamicCandidates() throws Exception {
        try (OnnxTrajectoryOcrEngine engine = new OnnxTrajectoryOcrEngine(
                "/assets/aozaink_core/ocr/olsingle24", 256, 0.018f, "arc")) {
            InkTrace trace = new InkTrace(List.of(List.of(
                new InkPoint(0.1f, 0.5f, 0L),
                new InkPoint(0.3f, 0.5f, 20L),
                new InkPoint(0.5f, 0.5f, 40L),
                new InkPoint(0.7f, 0.5f, 60L),
                new InkPoint(0.9f, 0.5f, 80L)
            )));
            TrajectoryResult result =
                engine.recognizeTrajectory(trace, List.of("一", "二", "火", "雷"));

            assertEquals(4, result.candidates().size());
            assertEquals(List.of("一", "二", "火", "雷"), result.candidates().stream()
                .map(candidate -> candidate.word()).sorted().toList());
            assertTrue(result.candidates().stream().allMatch(candidate ->
                Float.isFinite(candidate.confidence()) && candidate.confidence() >= 0.0f));
            float sum = result.candidates().stream()
                .map(candidate -> candidate.confidence())
                .reduce(0.0f, Float::sum);
            assertEquals(1.0f, sum, 1.0e-4f);
        }
    }
}
