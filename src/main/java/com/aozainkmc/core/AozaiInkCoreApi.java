package com.aozainkmc.core;

import com.aozainkmc.core.api.EngineType;
import com.aozainkmc.core.api.InkMarkStore;
import com.aozainkmc.core.ocr.OcrEngine;
import com.aozainkmc.core.ocr.TrajectoryOcrEngine;
import com.aozainkmc.core.recognizer.InkRecognizer;
import com.aozainkmc.core.recognizer.InkRecognizerImpl;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class AozaiInkCoreApi {
    private static InkMarkStore markStore;
    private static InkRecognizer recognizer;
    private static OcrEngine imageEngine;
    private static TrajectoryOcrEngine trajectoryEngine;
    private static final Map<String, EngineType> inputRegistrations = new HashMap<>();
    private static final Set<String> registeredGlyphs = new HashSet<>();

    private AozaiInkCoreApi() {
    }

    public static void installStore(InkMarkStore store) {
        markStore = store;
    }

    public static void installRecognizer() {
        recognizer = new InkRecognizerImpl();
    }

    public static void installImageEngine(OcrEngine engine) {
        imageEngine = engine;
    }

    public static void installTrajectoryEngine(TrajectoryOcrEngine engine) {
        trajectoryEngine = engine;
    }

    public static void registerInput(String sourceId, EngineType engineType) {
        inputRegistrations.put(sourceId, engineType);
    }

    public static void registerGlyphs(Collection<String> glyphs) {
        registeredGlyphs.addAll(glyphs);
    }

    public static EngineType engineTypeFor(String sourceId) {
        return inputRegistrations.getOrDefault(sourceId, EngineType.OFFLINE_IMAGE);
    }

    public static Set<String> activeGlyphs() {
        return Collections.unmodifiableSet(registeredGlyphs);
    }

    public static InkMarkStore markStore() {
        return markStore;
    }

    public static InkRecognizer recognizer() {
        return recognizer;
    }

    public static OcrEngine imageEngine() {
        return imageEngine;
    }

    public static TrajectoryOcrEngine trajectoryEngine() {
        return trajectoryEngine;
    }

    public static boolean imageEngineNeeded() {
        return inputRegistrations.containsValue(EngineType.OFFLINE_IMAGE);
    }

    public static boolean trajectoryEngineNeeded() {
        return inputRegistrations.containsValue(EngineType.ONLINE_TRAJECTORY);
    }
}
