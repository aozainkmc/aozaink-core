package com.aozainkmc.core.ocr;

import com.aozainkmc.core.api.InkPoint;
import com.aozainkmc.core.api.InkTrace;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrajectoryPreprocessorTest {

    @Test
    void emptyTraceReturnsNull() {
        TrajectoryPreprocessor preprocessor = new TrajectoryPreprocessor(256, 0.018f, "arc");
        TrajectoryPreprocessor.TrajectoryFeatures result = preprocessor.preprocess(new InkTrace(List.of()));
        assertNull(result);
    }

    @Test
    void outputHasSixFeaturesAndRespectsMaxPoints() {
        TrajectoryPreprocessor preprocessor = new TrajectoryPreprocessor(32, 0.018f, "arc");
        InkTrace trace = new InkTrace(List.of(
            List.of(
                new InkPoint(0.0f, 0.0f, 0L),
                new InkPoint(50.0f, 50.0f, 10L),
                new InkPoint(100.0f, 0.0f, 20L)
            )
        ));

        TrajectoryPreprocessor.TrajectoryFeatures result = preprocessor.preprocess(trace);

        assertNotNull(result);
        assertTrue(result.features().length <= 32);
        assertEquals(1, result.simplifiedStrokeCount());
        assertEquals(3, result.simplifiedPointCount());
        for (float[] frame : result.features()) {
            assertEquals(6, frame.length);
        }
    }

    @Test
    void longTraceIsDownsampledToMaxPoints() {
        List<InkPoint> stroke = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            float y = (i % 2 == 0) ? i * 1.0f : i * 0.5f;
            stroke.add(new InkPoint(i * 1.0f, y, i * 10L));
        }
        TrajectoryPreprocessor preprocessor = new TrajectoryPreprocessor(64, 0.001f, "arc");

        TrajectoryPreprocessor.TrajectoryFeatures result = preprocessor.preprocess(new InkTrace(List.of(stroke)));

        assertNotNull(result);
        assertEquals(64, result.features().length);
    }
}
