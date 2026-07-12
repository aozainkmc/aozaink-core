package com.aozainkmc.core.ocr;

import ai.onnxruntime.OnnxTensor;
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
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class OnnxTrajectoryOcrEngine implements TrajectoryOcrEngine {

    private final OrtEnvironment environment;
    private final OrtSession singleModel;
    private final OrtSession encoder;
    private final List<OrtSession> blocks;
    private final List<String> chars;
    private final Map<String, Integer> charToId;
    private final TrajectoryPreprocessor preprocessor;
    private final int maxPoints;
    private final int hiddenDim;
    private final boolean usesSpatial;

    public OnnxTrajectoryOcrEngine(String modelDir, int maxPoints, float simplifyEpsRatio,
                                    String progressMode) throws Exception {
        this.environment = OrtEnvironment.getEnvironment();
        JsonObject meta = loadJson(modelDir + "/meta.json");
        this.hiddenDim = getInt(meta, "hidden_dim", 160);
        this.usesSpatial = meta.has("spatial_dim") && !meta.get("spatial_dim").isJsonNull();
        this.blocks = new ArrayList<>();
        if (meta.has("model") && !meta.get("model").getAsString().isBlank()) {
            this.singleModel = createSession(modelDir + "/" + meta.get("model").getAsString());
            this.encoder = null;
        } else {
            this.singleModel = null;
            int numExits = getInt(meta, "num_exits", 6);
            this.encoder = createSession(modelDir + "/encoder.onnx");
            for (int i = 1; i <= numExits; i++) {
                this.blocks.add(createSession(modelDir + "/block_" + i + ".onnx"));
            }
        }
        this.chars = loadVocab(modelDir + "/vocab.json");
        this.charToId = new HashMap<>();
        for (int i = 0; i < chars.size(); i++) {
            this.charToId.put(chars.get(i), i);
        }
        this.maxPoints = maxPoints;
        this.preprocessor = new TrajectoryPreprocessor(maxPoints, simplifyEpsRatio, progressMode);
    }

    private static JsonObject loadJson(String path) throws Exception {
        try (InputStream stream = OnnxTrajectoryOcrEngine.class.getResourceAsStream(path)) {
            if (stream == null) {
                throw new RuntimeException("Missing JSON resource: " + path);
            }
            try (InputStreamReader reader = new InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader).getAsJsonObject();
            }
        }
    }

    private static int getInt(JsonObject object, String name, int fallback) {
        if (!object.has(name) || object.get(name).isJsonNull()) {
            return fallback;
        }
        return object.get(name).getAsInt();
    }

    private OrtSession createSession(String path) throws Exception {
        try (InputStream stream = OnnxTrajectoryOcrEngine.class.getResourceAsStream(path)) {
            if (stream == null) {
                throw new RuntimeException("Missing ONNX resource: " + path);
            }
            byte[] bytes = stream.readAllBytes();
            return environment.createSession(bytes, new OrtSession.SessionOptions());
        }
    }

    private static List<String> loadVocab(String path) throws Exception {
        JsonObject root = loadJson(path);
        JsonArray tags = root.getAsJsonArray("tags");
        if (tags != null) {
            List<String> list = new ArrayList<>(tags.size());
            for (int i = 0; i < tags.size(); i++) {
                list.add(tagToChar(tags.get(i).getAsString()));
            }
            return list;
        }

        JsonArray array = root.getAsJsonArray("chars");
        List<String> list = new ArrayList<>(array.size());
        for (int i = 0; i < array.size(); i++) {
            list.add(array.get(i).getAsString());
        }
        return list;
    }

    private static String tagToChar(String hex) {
        if (hex == null || hex.length() < 4) {
            return "?";
        }
        int hi = Integer.parseInt(hex.substring(0, 2), 16);
        int lo = Integer.parseInt(hex.substring(2, 4), 16);
        if (hi == 0 && lo >= 0x20 && lo < 0x7f) {
            return Character.toString((char) lo);
        }
        byte[] swapped = new byte[] {(byte) lo, (byte) hi};
        String decoded = decodeTag(swapped);
        if (decoded != null) {
            return decoded;
        }
        return decodeTag(new byte[] {(byte) hi, (byte) lo}, "?");
    }

    private static String decodeTag(byte[] bytes) {
        return decodeTag(bytes, null);
    }

    private static String decodeTag(byte[] bytes, String fallback) {
        for (String name : List.of("GB2312", "GBK", "GB18030")) {
            String s = new String(bytes, Charset.forName(name));
            if (!s.isEmpty() && s.indexOf('\u0000') < 0 && s.indexOf('\ufffd') < 0 && !hasPrivateUse(s)) {
                return s;
            }
        }
        return fallback;
    }

    private static boolean hasPrivateUse(String value) {
        for (int i = 0; i < value.length(); ) {
            int cp = value.codePointAt(i);
            if (cp >= 0xE000 && cp <= 0xF8FF) {
                return true;
            }
            i += Character.charCount(cp);
        }
        return false;
    }

    @Override
    public TrajectoryResult recognizeTrajectory(InkTrace trace, List<String> candidateWhitelist) throws Exception {
        TrajectoryPreprocessor.TrajectoryFeatures features = preprocessor.preprocess(trace);
        if (features == null) {
            return new TrajectoryResult(Collections.emptyList(), 0, 0, 0L);
        }

        float[][] feats = features.features();
        DebugDump.onlineTrajectoryInput(feats);

        long writingDurationMs = computeWritingDuration(trace);
        int time = Math.min(feats.length, maxPoints);
        float[] trajectoryFlat = new float[time * 6];
        float[] maskFlat = new float[time];
        for (int i = 0; i < time; i++) {
            System.arraycopy(feats[i], 0, trajectoryFlat, i * 6, 6);
            maskFlat[i] = 1.0f;
        }

        long[] trajShape = {1L, time, 6L};
        long[] maskShape = {1L, time};

        long[] candidateIds = resolveCandidateIds(candidateWhitelist);
        long[] candShape = {1L, candidateIds.length};
        float[] logits = null;
        float[] x = null;
        float[] spatial = null;
        try (OnnxTensor trajectoryTensor = OnnxTensor.createTensor(environment, FloatBuffer.wrap(trajectoryFlat), trajShape);
             OnnxTensor maskTensor = OnnxTensor.createTensor(environment, FloatBuffer.wrap(maskFlat), maskShape)) {
            if (singleModel != null) {
                try (OnnxTensor candTensor = OnnxTensor.createTensor(
                        environment, LongBuffer.wrap(candidateIds), candShape)) {
                    Map<String, OnnxTensor> inputs = new HashMap<>();
                    inputs.put("trajectory", trajectoryTensor);
                    inputs.put("mask", maskTensor);
                    inputs.put("candidate_ids", candTensor);
                    try (OrtSession.Result result = singleModel.run(inputs)) {
                        logits = flatten2d(result.get(0).getValue());
                    }
                }
            } else {
                Map<String, OnnxTensor> inputs = new HashMap<>();
                inputs.put("trajectory", trajectoryTensor);
                inputs.put("mask", maskTensor);
                try (OrtSession.Result result = encoder.run(inputs)) {
                    x = flatten3d(result.get(0).getValue());
                    if (usesSpatial) {
                        spatial = flatten2d(result.get(1).getValue());
                    }
                }
            }
        }

        if (singleModel == null) {
            long[] xShape = {1L, hiddenDim, time};
            for (OrtSession block : blocks) {
                try (OnnxTensor xTensor = OnnxTensor.createTensor(environment, FloatBuffer.wrap(x), xShape);
                     OnnxTensor maskTensor = OnnxTensor.createTensor(environment, FloatBuffer.wrap(maskFlat), maskShape);
                     OnnxTensor candTensor = OnnxTensor.createTensor(environment, LongBuffer.wrap(candidateIds), candShape)) {
                    Map<String, OnnxTensor> inputs = new HashMap<>();
                    inputs.put("x", xTensor);
                    inputs.put("mask", maskTensor);
                    inputs.put("candidate_ids", candTensor);
                    if (spatial == null) {
                        try (OrtSession.Result result = block.run(inputs)) {
                            x = flatten3d(result.get(0).getValue());
                            logits = flatten2d(result.get(1).getValue());
                        }
                    } else {
                        long[] spatialShape = {1L, spatial.length};
                        try (OnnxTensor spatialTensor = OnnxTensor.createTensor(environment, FloatBuffer.wrap(spatial), spatialShape)) {
                            inputs.put("spatial", spatialTensor);
                            try (OrtSession.Result result = block.run(inputs)) {
                                x = flatten3d(result.get(0).getValue());
                                logits = flatten2d(result.get(1).getValue());
                            }
                        }
                    }
                }
            }
        }

        List<InkCandidate> candidates = toCandidates(logits, candidateIds);
        DebugDump.onlineCandidates(candidates, candidateWhitelist == null || candidateWhitelist.isEmpty() ? "full" : "filtered");
        return new TrajectoryResult(
            candidates,
            features.simplifiedStrokeCount(),
            features.simplifiedPointCount(),
            writingDurationMs
        );
    }

    private static long computeWritingDuration(InkTrace trace) {
        long first = Long.MAX_VALUE;
        long last = Long.MIN_VALUE;
        for (List<InkPoint> stroke : trace.strokes()) {
            for (InkPoint point : stroke) {
                long t = point.timeMs();
                if (t < first) first = t;
                if (t > last) last = t;
            }
        }
        return first == Long.MAX_VALUE ? 0L : last - first;
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
        for (String ch : candidateWhitelist) {
            Integer id = charToId.get(ch);
            if (id != null) {
                ids.add(id);
            }
        }
        if (ids.isEmpty()) {
            long[] all = new long[chars.size()];
            for (int i = 0; i < all.length; i++) {
                all[i] = i;
            }
            return all;
        }
        return ids.stream().mapToLong(Integer::longValue).toArray();
    }

    private List<InkCandidate> toCandidates(float[] logits, long[] candidateIds) {
        if (logits == null) {
            return Collections.emptyList();
        }
        float[] probs = softmax(logits);
        List<InkCandidate> candidates = new ArrayList<>(probs.length);
        for (int i = 0; i < probs.length; i++) {
            candidates.add(new InkCandidate(chars.get((int) candidateIds[i]), probs[i]));
        }
        candidates.sort((a, b) -> Float.compare(b.confidence(), a.confidence()));
        return candidates;
    }

    private static float[] flatten3d(Object value) {
        float[][][] array = (float[][][]) value;
        int size = 0;
        for (float[][] mid : array) {
            for (float[] inner : mid) {
                size += inner.length;
            }
        }
        float[] out = new float[size];
        int offset = 0;
        for (float[][] mid : array) {
            for (float[] inner : mid) {
                System.arraycopy(inner, 0, out, offset, inner.length);
                offset += inner.length;
            }
        }
        return out;
    }

    private static float[] flatten2d(Object value) {
        float[][] array = (float[][]) value;
        int size = 0;
        for (float[] row : array) {
            size += row.length;
        }
        float[] out = new float[size];
        int offset = 0;
        for (float[] row : array) {
            System.arraycopy(row, 0, out, offset, row.length);
            offset += row.length;
        }
        return out;
    }

    private static float[] softmax(float[] logits) {
        float max = Float.NEGATIVE_INFINITY;
        for (float v : logits) {
            max = Math.max(max, v);
        }
        double sum = 0.0;
        float[] out = new float[logits.length];
        for (int i = 0; i < logits.length; i++) {
            out[i] = (float) Math.exp(logits[i] - max);
            sum += out[i];
        }
        if (sum <= 0.0) {
            return out;
        }
        for (int i = 0; i < out.length; i++) {
            out[i] = (float) (out[i] / sum);
        }
        return out;
    }

    @Override
    public void close() throws Exception {
        for (OrtSession block : blocks) {
            block.close();
        }
        if (encoder != null) encoder.close();
        if (singleModel != null) singleModel.close();
    }
}


