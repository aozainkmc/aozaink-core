package com.aozainkmc.core.ocr;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.aozainkmc.core.AozaiInkCore;
import com.aozainkmc.core.api.InkCandidate;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;

public final class OnnxOcrEngine implements OcrEngine {
    private static final ResourceLocation MODEL_ID = ResourceLocation.fromNamespaceAndPath(AozaiInkCore.MOD_ID, "ocr/cup_ocr_64.onnx");
    private static final ResourceLocation LABELS_ID = ResourceLocation.fromNamespaceAndPath(AozaiInkCore.MOD_ID, "ocr/labels.json");

    private final OrtEnvironment environment;
    private final OrtSession session;
    private final List<String> labels;
    private final String inputName;

    public OnnxOcrEngine() throws Exception {
        this.environment = OrtEnvironment.getEnvironment();
        this.session = environment.createSession(readBytes(MODEL_ID), new OrtSession.SessionOptions());
        this.labels = readLabels();
        this.inputName = session.getInputNames().iterator().next();
    }

    @Override
    public List<InkCandidate> recognize(float[] input, int topK, List<String> candidateWhitelist) throws Exception {
        long[] shape = {1L, 1L, 64L, 64L};
        try (OnnxTensor tensor = OnnxTensor.createTensor(environment, FloatBuffer.wrap(input), shape);
             OrtSession.Result result = session.run(java.util.Map.of(inputName, tensor))) {
            float[][] logits = (float[][]) result.get(0).getValue();
            return topK(softmax(logits[0]), topK, candidateWhitelist);
        }
    }

    @Override
    public void close() throws Exception {
        session.close();
    }

    private List<InkCandidate> topK(float[] probabilities, int topK, List<String> candidateWhitelist) {
        List<Integer> indices = new ArrayList<>(probabilities.length);
        for (int i = 0; i < probabilities.length; i++) {
            indices.add(i);
        }
        indices.sort(Comparator.comparingDouble((Integer index) -> probabilities[index]).reversed());

        boolean hasWhitelist = candidateWhitelist != null && !candidateWhitelist.isEmpty();
        Set<String> whitelist = hasWhitelist ? new HashSet<>(candidateWhitelist) : Set.of();
        int scanLimit = hasWhitelist
            ? Math.min(labels.size(), indices.size())
            : Math.min(Math.max(topK * 20, 200), Math.min(labels.size(), indices.size()));
        List<InkCandidate> candidates = new ArrayList<>(topK);
        for (int i = 0; i < scanLimit && candidates.size() < topK; i++) {
            int index = indices.get(i);
            String label = labels.get(index);
            if (!isHan(label)) continue;
            if (hasWhitelist && !whitelist.contains(label)) continue;
            candidates.add(new InkCandidate(label, probabilities[index]));
        }
        return candidates;
    }

    private static boolean isHan(String label) {
        if (label == null || label.codePointCount(0, label.length()) != 1) {
            return false;
        }
        return Character.UnicodeScript.of(label.codePointAt(0)) == Character.UnicodeScript.HAN;
    }

    private static float[] softmax(float[] logits) {
        float max = Float.NEGATIVE_INFINITY;
        for (float logit : logits) {
            max = Math.max(max, logit);
        }

        double sum = 0.0D;
        float[] out = new float[logits.length];
        for (int i = 0; i < logits.length; i++) {
            out[i] = (float) Math.exp(logits[i] - max);
            sum += out[i];
        }
        if (sum <= 0.0D) {
            return out;
        }
        for (int i = 0; i < out.length; i++) {
            out[i] = (float) (out[i] / sum);
        }
        return out;
    }

    private static byte[] readBytes(ResourceLocation id) throws Exception {
        String path = "/assets/" + id.getNamespace() + "/" + id.getPath();
        try (InputStream stream = OnnxOcrEngine.class.getResourceAsStream(path)) {
            if (stream == null) {
                throw new RuntimeException("Missing ONNX model resource: " + path);
            }
            return stream.readAllBytes();
        }
    }

    private static List<String> readLabels() throws Exception {
        String path = "/assets/" + LABELS_ID.getNamespace() + "/" + LABELS_ID.getPath();
        try (InputStream stream = OnnxOcrEngine.class.getResourceAsStream(path);
             InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            if (stream == null) {
                throw new RuntimeException("Missing labels resource: " + path);
            }
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            JsonArray chars = root.getAsJsonArray("chars");
            List<String> labels = new ArrayList<>(chars.size());
            for (int i = 0; i < chars.size(); i++) {
                labels.add(chars.get(i).getAsString());
            }
            return labels;
        }
    }
}
