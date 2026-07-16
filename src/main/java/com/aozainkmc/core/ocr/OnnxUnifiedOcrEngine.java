package com.aozainkmc.core.ocr;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.aozainkmc.core.api.InkCandidate;
import com.aozainkmc.core.api.InkPoint;
import com.aozainkmc.core.api.InkTrace;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class OnnxUnifiedOcrEngine implements OcrEngine {
    private static final String MODEL_DIR = "/assets/aozaink_core/ocr/mix_flash_v1";
    private static final int IMAGE_SIZE = 64;
    private static final int IMAGE_VALUES = IMAGE_SIZE * IMAGE_SIZE;
    private static final float[] DUMMY_IMAGE = new float[IMAGE_VALUES];
    private static final float[] DUMMY_TRAJECTORY = new float[12];
    private static final float[] DUMMY_MASK = new float[] {1.0f, 1.0f};

    private final OrtEnvironment environment;
    private final OrtSession session;
    private final List<String> chars;
    private final Map<String, Integer> charToId;
    private final TrajectoryPreprocessor preprocessor;
    private final int maxPoints;

    public OnnxUnifiedOcrEngine() throws Exception {
        JsonObject meta = loadJson(MODEL_DIR + "/meta.json");
        JsonObject trajectoryMeta = meta.getAsJsonObject("trajectory");
        this.maxPoints = trajectoryMeta.get("max_points").getAsInt();
        this.preprocessor = new TrajectoryPreprocessor(
            maxPoints,
            trajectoryMeta.get("simplify_eps").getAsFloat(),
            trajectoryMeta.get("progress_mode").getAsString()
        );
        this.chars = loadVocab(MODEL_DIR + "/vocab.json");
        this.charToId = new HashMap<>();
        for (int i = 0; i < chars.size(); i++) {
            charToId.put(chars.get(i), i);
        }
        this.environment = OrtEnvironment.getEnvironment();
        this.session = createSession(MODEL_DIR + "/" + meta.get("model").getAsString());
    }

    @Override
    public List<InkCandidate> recognizeImage(
        float[] input,
        List<String> candidateWhitelist
    ) throws Exception {
        if (input == null || input.length != IMAGE_VALUES) {
            return Collections.emptyList();
        }
        DebugDump.imageInput(input);
        long[] candidateIds = resolveCandidateIds(candidateWhitelist);
        float[] logits = run(input, DUMMY_TRAJECTORY, DUMMY_MASK, 2, candidateIds, "image_logits");
        return toCandidates(logits, candidateIds, candidateWhitelist);
    }

    @Override
    public TrajectoryResult recognizeTrajectory(
        InkTrace trace,
        List<String> candidateWhitelist
    ) throws Exception {
        TrajectoryPreprocessor.TrajectoryFeatures features = preprocessor.preprocess(trace);
        if (features == null) {
            return new TrajectoryResult(Collections.emptyList(), 0, 0, 0L);
        }

        float[][] rows = features.features();
        DebugDump.trajectoryInput(rows);
        int time = Math.min(rows.length, maxPoints);
        float[] trajectory = new float[time * 6];
        float[] mask = new float[time];
        for (int i = 0; i < time; i++) {
            System.arraycopy(rows[i], 0, trajectory, i * 6, 6);
            mask[i] = 1.0f;
        }

        long[] candidateIds = resolveCandidateIds(candidateWhitelist);
        float[] logits = run(DUMMY_IMAGE, trajectory, mask, time, candidateIds, "trajectory_logits");
        List<InkCandidate> candidates = toCandidates(logits, candidateIds, candidateWhitelist);
        DebugDump.candidates(
            candidates,
            candidateWhitelist == null || candidateWhitelist.isEmpty() ? "full" : "filtered"
        );
        return new TrajectoryResult(
            candidates,
            features.simplifiedStrokeCount(),
            features.simplifiedPointCount(),
            computeWritingDuration(trace)
        );
    }

    private float[] run(
        float[] image,
        float[] trajectory,
        float[] mask,
        int time,
        long[] candidateIds,
        String outputName
    ) throws Exception {
        long[] candidateShape = {1L, candidateIds.length};
        try (
            OnnxTensor imageTensor = OnnxTensor.createTensor(
                environment, FloatBuffer.wrap(image), new long[] {1L, 1L, IMAGE_SIZE, IMAGE_SIZE}
            );
            OnnxTensor trajectoryTensor = OnnxTensor.createTensor(
                environment, FloatBuffer.wrap(trajectory), new long[] {1L, time, 6L}
            );
            OnnxTensor maskTensor = OnnxTensor.createTensor(
                environment, FloatBuffer.wrap(mask), new long[] {1L, time}
            );
            OnnxTensor candidateTensor = OnnxTensor.createTensor(
                environment, LongBuffer.wrap(candidateIds), candidateShape
            )
        ) {
            Map<String, OnnxTensor> inputs = Map.of(
                "image", imageTensor,
                "trajectory", trajectoryTensor,
                "mask", maskTensor,
                "candidate_ids", candidateTensor
            );
            try (OrtSession.Result result = session.run(inputs, Set.of(outputName))) {
                OnnxValue value = result.get(outputName)
                    .orElseThrow(() -> new IllegalStateException("Missing ONNX output: " + outputName));
                return flatten2d(value.getValue());
            }
        }
    }

    private long[] resolveCandidateIds(List<String> candidateWhitelist) {
        if (candidateWhitelist == null || candidateWhitelist.isEmpty()) {
            long[] all = new long[chars.size()];
            for (int i = 0; i < all.length; i++) {
                all[i] = i;
            }
            return all;
        }

        List<Integer> ids = new ArrayList<>();
        for (String glyph : candidateWhitelist) {
            Integer id = charToId.get(glyph);
            if (id != null) {
                ids.add(id);
            }
        }
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("No candidate glyph exists in the unified vocabulary");
        }
        return ids.stream().mapToLong(Integer::longValue).toArray();
    }

    private List<InkCandidate> toCandidates(
        float[] logits,
        long[] candidateIds,
        List<String> candidateWhitelist
    ) {
        if (logits == null || logits.length == 0) {
            return Collections.emptyList();
        }
        float[] probabilities = softmax(logits);
        List<InkCandidate> candidates = new ArrayList<>(probabilities.length);
        for (int i = 0; i < probabilities.length; i++) {
            candidates.add(new InkCandidate(chars.get((int) candidateIds[i]), probabilities[i]));
        }
        candidates.sort((left, right) -> Float.compare(right.confidence(), left.confidence()));
        if ((candidateWhitelist == null || candidateWhitelist.isEmpty()) && candidates.size() > 10) {
            return new ArrayList<>(candidates.subList(0, 10));
        }
        return candidates;
    }

    private static long computeWritingDuration(InkTrace trace) {
        long first = Long.MAX_VALUE;
        long last = Long.MIN_VALUE;
        for (List<InkPoint> stroke : trace.strokes()) {
            for (InkPoint point : stroke) {
                first = Math.min(first, point.timeMs());
                last = Math.max(last, point.timeMs());
            }
        }
        return first == Long.MAX_VALUE ? 0L : last - first;
    }

    private OrtSession createSession(String path) throws Exception {
        try (InputStream stream = OnnxUnifiedOcrEngine.class.getResourceAsStream(path)) {
            if (stream == null) {
                throw new IllegalStateException("Missing ONNX resource: " + path);
            }
            return environment.createSession(stream.readAllBytes(), new OrtSession.SessionOptions());
        }
    }

    private static JsonObject loadJson(String path) throws Exception {
        try (InputStream stream = OnnxUnifiedOcrEngine.class.getResourceAsStream(path)) {
            if (stream == null) {
                throw new IllegalStateException("Missing JSON resource: " + path);
            }
            try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader).getAsJsonObject();
            }
        }
    }

    private static List<String> loadVocab(String path) throws Exception {
        JsonArray array = loadJson(path).getAsJsonArray("chars");
        List<String> result = new ArrayList<>(array.size());
        for (int i = 0; i < array.size(); i++) {
            result.add(array.get(i).getAsString());
        }
        return result;
    }

    private static float[] flatten2d(Object value) {
        float[][] array = (float[][]) value;
        int size = 0;
        for (float[] row : array) {
            size += row.length;
        }
        float[] output = new float[size];
        int offset = 0;
        for (float[] row : array) {
            System.arraycopy(row, 0, output, offset, row.length);
            offset += row.length;
        }
        return output;
    }

    private static float[] softmax(float[] logits) {
        float max = Float.NEGATIVE_INFINITY;
        for (float value : logits) {
            max = Math.max(max, value);
        }
        float[] probabilities = new float[logits.length];
        double sum = 0.0D;
        for (int i = 0; i < logits.length; i++) {
            probabilities[i] = (float) Math.exp(logits[i] - max);
            sum += probabilities[i];
        }
        if (sum > 0.0D) {
            for (int i = 0; i < probabilities.length; i++) {
                probabilities[i] = (float) (probabilities[i] / sum);
            }
        }
        return probabilities;
    }

    @Override
    public void close() throws Exception {
        session.close();
    }
}
