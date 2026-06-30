package com.aozainkmc.core.recognizer;

import com.aozainkmc.core.AozaiInkCoreApi;
import com.aozainkmc.core.api.EngineType;
import com.aozainkmc.core.api.InkCandidate;
import com.aozainkmc.core.api.InkPoint;
import com.aozainkmc.core.api.InkRecognitionRequest;
import com.aozainkmc.core.api.InkRecognitionResult;
import com.aozainkmc.core.api.InkSource;
import com.aozainkmc.core.api.InkTrace;
import com.aozainkmc.core.ocr.OcrEngine;
import com.aozainkmc.core.ocr.TrajectoryOcrEngine;
import com.aozainkmc.core.ocr.TrajectoryResult;
import com.aozainkmc.core.store.InMemoryInkMarkStore;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InkRecognizerImplTest {

    @BeforeEach
    void setUp() {
        AozaiInkCoreApi.installStore(new InMemoryInkMarkStore());
        AozaiInkCoreApi.installRecognizer();
    }

    @Test
    void imageRecognizeReturnsEmptyWhenEngineIsNull() throws Exception {
        AozaiInkCoreApi.installImageEngine(null);
        AozaiInkCoreApi.registerInput("image_input", EngineType.OFFLINE_IMAGE);
        InkRecognizer recognizer = AozaiInkCoreApi.recognizer();

        InkRecognitionResult result = recognizer.recognize(imageRequest());

        assertTrue(result.candidates().isEmpty());
        assertEquals("", result.topGlyph());
    }

    @Test
    void imageRecognizeFillsTopGlyphFromFirstCandidate() throws Exception {
        AozaiInkCoreApi.installImageEngine(new FakeImageOcrEngine());
        AozaiInkCoreApi.registerInput("image_input", EngineType.OFFLINE_IMAGE);
        InkRecognizer recognizer = AozaiInkCoreApi.recognizer();

        InkRecognitionResult result = recognizer.recognize(imageRequest());

        assertEquals("火", result.topGlyph());
        assertEquals(2, result.candidates().size());
    }

    @Test
    void trajectoryRecognizeReturnsEmptyWhenTraceIsEmpty() throws Exception {
        AozaiInkCoreApi.installTrajectoryEngine(new FakeTrajectoryOcrEngine());
        AozaiInkCoreApi.registerInput("traj_input", EngineType.ONLINE_TRAJECTORY);
        InkRecognizer recognizer = AozaiInkCoreApi.recognizer();

        InkRecognitionResult result = recognizer.recognize(new InkRecognitionRequest(
            new InkTrace(List.of()),
            null,
            null,
            null,
            0L,
            InkSource.simple("traj_input")
        ));

        assertTrue(result.candidates().isEmpty());
    }

    @Test
    void trajectoryRecognizeUsesCandidateWhitelist() throws Exception {
        AozaiInkCoreApi.installTrajectoryEngine(new FakeTrajectoryOcrEngine());
        AozaiInkCoreApi.registerInput("traj_input", EngineType.ONLINE_TRAJECTORY);
        InkRecognizer recognizer = AozaiInkCoreApi.recognizer();

        InkRecognitionResult result = recognizer.recognize(new InkRecognitionRequest(
            simpleTrace(),
            null,
            null,
            List.of("水"),
            0L,
            InkSource.simple("traj_input")
        ));

        assertEquals("水", result.topGlyph());
        assertEquals(1, result.candidates().size());
    }

    @Test
    void trajectoryResultIncludesStatistics() throws Exception {
        AozaiInkCoreApi.installTrajectoryEngine(new FakeTrajectoryOcrEngine());
        AozaiInkCoreApi.registerInput("traj_input", EngineType.ONLINE_TRAJECTORY);
        InkRecognizer recognizer = AozaiInkCoreApi.recognizer();

        InkRecognitionResult result = recognizer.recognize(new InkRecognitionRequest(
            simpleTrace(),
            null,
            null,
            null,
            0L,
            InkSource.simple("traj_input")
        ));

        assertEquals(1, result.simplifiedStrokeCount());
        assertEquals(3, result.simplifiedPointCount());
        assertEquals(20L, result.writingDurationMs());
    }

    private static InkRecognitionRequest imageRequest() {
        return new InkRecognitionRequest(
            null,
            new float[64 * 64],
            null,
            null,
            0L,
            InkSource.simple("image_input")
        );
    }

    private static InkTrace simpleTrace() {
        return new InkTrace(List.of(
            List.of(new InkPoint(0.1f, 0.1f, 0L), new InkPoint(0.5f, 0.5f, 10L), new InkPoint(0.9f, 0.1f, 20L))
        ));
    }

    private static final class FakeImageOcrEngine implements OcrEngine {
        @Override
        public List<InkCandidate> recognize(float[] input, int topK, List<String> candidateWhitelist) {
            return List.of(new InkCandidate("火", 0.9f), new InkCandidate("水", 0.5f));
        }

        @Override
        public void close() {}
    }

    private static final class FakeTrajectoryOcrEngine implements TrajectoryOcrEngine {
        @Override
        public TrajectoryResult recognizeTrajectory(InkTrace trace, List<String> candidateWhitelist) {
            List<InkCandidate> candidates;
            if (candidateWhitelist == null || candidateWhitelist.isEmpty()) {
                candidates = List.of(new InkCandidate("火", 0.9f), new InkCandidate("水", 0.5f));
            } else {
                candidates = candidateWhitelist.stream()
                    .map(c -> new InkCandidate(c, 0.8f))
                    .toList();
            }
            return new TrajectoryResult(candidates, 1, 3, 20L);
        }

        @Override
        public void close() {}
    }
}
