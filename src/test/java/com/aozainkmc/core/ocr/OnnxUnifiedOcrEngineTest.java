package com.aozainkmc.core.ocr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aozainkmc.core.api.InkCandidate;
import com.aozainkmc.core.api.InkPoint;
import com.aozainkmc.core.api.InkTrace;
import java.util.List;
import org.junit.jupiter.api.Test;

class OnnxUnifiedOcrEngineTest {
    private static final List<String> CANDIDATES = List.of("一", "二", "火", "雷");

    @Test
    void oneModelRecognizesDynamicTrajectoryCandidates() throws Exception {
        try (OnnxUnifiedOcrEngine engine = new OnnxUnifiedOcrEngine()) {
            InkTrace trace = new InkTrace(List.of(List.of(
                new InkPoint(0.1f, 0.5f, 0L),
                new InkPoint(0.3f, 0.5f, 20L),
                new InkPoint(0.5f, 0.5f, 40L),
                new InkPoint(0.7f, 0.5f, 60L),
                new InkPoint(0.9f, 0.5f, 80L)
            )));

            TrajectoryResult result = engine.recognizeTrajectory(trace, CANDIDATES);

            assertCandidates(result.candidates());
        }
    }

    @Test
    void sameModelRecognizesImageCandidates() throws Exception {
        float[] image = new float[64 * 64];
        java.util.Arrays.fill(image, 1.0f);
        for (int y = 29; y <= 34; y++) {
            for (int x = 8; x <= 55; x++) {
                image[y * 64 + x] = -0.75f;
            }
        }

        try (OnnxUnifiedOcrEngine engine = new OnnxUnifiedOcrEngine()) {
            assertCandidates(engine.recognizeImage(image, CANDIDATES));
        }
    }

    private static void assertCandidates(List<InkCandidate> candidates) {
        assertEquals(4, candidates.size());
        assertEquals(CANDIDATES, candidates.stream().map(InkCandidate::word).sorted().toList());
        assertTrue(candidates.stream().allMatch(candidate ->
            Float.isFinite(candidate.confidence()) && candidate.confidence() >= 0.0f));
        float sum = candidates.stream().map(InkCandidate::confidence).reduce(0.0f, Float::sum);
        assertEquals(1.0f, sum, 1.0e-4f);
    }
}
