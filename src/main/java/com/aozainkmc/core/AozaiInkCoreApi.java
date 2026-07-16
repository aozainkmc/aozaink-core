package com.aozainkmc.core;

import com.aozainkmc.core.api.InkMarkStore;
import com.aozainkmc.core.ocr.OcrEngine;
import com.aozainkmc.core.recognizer.InkRecognizer;
import com.aozainkmc.core.recognizer.InkRecognizerImpl;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class AozaiInkCoreApi {
    private static InkMarkStore markStore;
    private static InkRecognizer recognizer;
    private static OcrEngine engine;
    private static final Set<String> registeredGlyphs = new HashSet<>();
    private static final Map<Class<?>, Object> services = new ConcurrentHashMap<>();

    private AozaiInkCoreApi() {
    }

    public static <T> void registerService(Class<T> type, T impl) {
        if (type == null || impl == null) return;
        services.put(type, impl);
    }

    public static <T> T getService(Class<T> type) {
        Object impl = services.get(type);
        return impl == null ? null : type.cast(impl);
    }

    public static void installStore(InkMarkStore store) {
        markStore = store;
    }

    public static void installRecognizer() {
        recognizer = new InkRecognizerImpl();
    }

    public static void installEngine(OcrEngine implementation) {
        engine = implementation;
    }

    public static void registerGlyphs(Collection<String> glyphs) {
        registeredGlyphs.addAll(glyphs);
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

    public static OcrEngine engine() {
        return engine;
    }
}
