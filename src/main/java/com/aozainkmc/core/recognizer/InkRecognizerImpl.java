package com.aozainkmc.core.recognizer;

import com.aozainkmc.core.AozaiInkCoreApi;
import com.aozainkmc.core.api.EngineType;
import com.aozainkmc.core.api.InkCandidate;
import com.aozainkmc.core.api.InkMark;
import com.aozainkmc.core.api.InkRecognitionRequest;
import com.aozainkmc.core.api.InkRecognitionResult;
import com.aozainkmc.core.api.InkRecognizedEvent;
import com.aozainkmc.core.api.InkTarget;
import com.aozainkmc.core.api.InkTrace;
import com.aozainkmc.core.ocr.OcrEngine;
import com.aozainkmc.core.ocr.TrajectoryOcrEngine;
import com.aozainkmc.core.ocr.TrajectoryResult;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;

public final class InkRecognizerImpl implements InkRecognizer {

    @Override
    public InkRecognitionResult recognize(InkRecognitionRequest request) throws Exception {
        EngineType type = AozaiInkCoreApi.engineTypeFor(request.source().sourceId());
        if (type == EngineType.ONLINE_TRAJECTORY) {
            return recognizeTrajectory(request);
        }
        return recognizeImage(request);
    }

    private static InkRecognitionResult recognizeImage(InkRecognitionRequest request) throws Exception {
        OcrEngine engine = AozaiInkCoreApi.imageEngine();
        if (engine == null || request.imageInput() == null) {
            return InkRecognitionResult.empty();
        }

        List<InkCandidate> candidates = engine.recognize(request.imageInput(), 5, candidateList(request));
        return toResult(candidates);
    }

    private static InkRecognitionResult recognizeTrajectory(InkRecognitionRequest request) throws Exception {
        TrajectoryOcrEngine engine = AozaiInkCoreApi.trajectoryEngine();
        if (engine == null || request.trace() == null || request.trace().isEmpty()) {
            return InkRecognitionResult.empty();
        }

        List<String> candidates = candidateList(request);
        TrajectoryResult result = engine.recognizeTrajectory(request.trace(), candidates);
        return toResult(result);
    }

    private static List<String> candidateList(InkRecognitionRequest request) {
        if (!request.candidateWhitelist().isEmpty()) {
            return request.candidateWhitelist();
        }
        return new ArrayList<>(AozaiInkCoreApi.activeGlyphs());
    }

    private static InkRecognitionResult toResult(List<InkCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return InkRecognitionResult.empty();
        }
        InkCandidate top = candidates.getFirst();
        return new InkRecognitionResult(top.word(), top.confidence(), candidates, 0, 0, 0L);
    }

    private static InkRecognitionResult toResult(TrajectoryResult result) {
        if (result == null || result.candidates() == null || result.candidates().isEmpty()) {
            return InkRecognitionResult.empty();
        }
        InkCandidate top = result.candidates().getFirst();
        String word = StrokeCountGlyphPatch.correct(top.word(), result.simplifiedStrokeCount());
        return new InkRecognitionResult(
            word,
            top.confidence(),
            result.candidates(),
            result.simplifiedStrokeCount(),
            result.simplifiedPointCount(),
            result.writingDurationMs()
        );
    }

    @Override
    public InkRecognizedEvent recognizeAndBroadcast(
        InkRecognitionRequest request,
        MinecraftServer server,
        ServerPlayer player
    ) throws Exception {
        InkRecognitionResult result = recognize(request);
        if (result.candidates().isEmpty()) {
            return null;
        }

        String dimension = player.serverLevel().dimension().location().toString();
        InkTarget target = InkTarget.player(dimension, player.getUUID());
        InkMark mark = new InkMark(
            result.topGlyph(),
            result.confidence(),
            player.getUUID(),
            target,
            request.source().sourceId(),
            player.serverLevel().getGameTime(),
            request.ttlTicks()
        );

        AozaiInkCoreApi.markStore().attach(mark);

        InkRecognizedEvent event = new InkRecognizedEvent(
            result,
            request.source(),
            mark,
            player,
            player.serverLevel()
        );
        NeoForge.EVENT_BUS.post(event);

        return event.isCanceled() ? null : event;
    }
}
