import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public final class RuntimeSmoke {
    private RuntimeSmoke() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: RuntimeSmoke <unified_dynamic.onnx>");
        }

        try (OrtEnvironment environment = OrtEnvironment.getEnvironment();
             OrtSession.SessionOptions options = new OrtSession.SessionOptions();
             OrtSession session = environment.createSession(Path.of(args[0]).toString(), options)) {
            runImage(environment, session);
            runTrajectory(environment, session);
        }
    }

    private static void runImage(OrtEnvironment environment, OrtSession session) throws Exception {
        float[] image = new float[64 * 64];
        for (int i = 0; i < image.length; i++) {
            image[i] = ((i * 37) % 251) / 250.0f;
        }
        long[] candidates = candidates();

        try (OnnxTensor imageTensor = OnnxTensor.createTensor(environment, FloatBuffer.wrap(image), new long[]{1, 1, 64, 64});
             OnnxTensor candidateTensor = OnnxTensor.createTensor(environment, LongBuffer.wrap(candidates), new long[]{1, candidates.length})) {
            Map<String, OnnxTensor> inputs = new LinkedHashMap<>();
            inputs.put("image", imageTensor);
            inputs.put("candidate_ids", candidateTensor);
            try (OrtSession.Result result = session.run(inputs, java.util.Set.of("image_logits"))) {
                report("image_logits", (float[][]) result.get("image_logits").orElseThrow().getValue());
            }
        }
    }

    private static void runTrajectory(OrtEnvironment environment, OrtSession session) throws Exception {
        int time = 37;
        float[] trajectory = new float[time * 6];
        float[] mask = new float[time];
        for (int t = 0; t < time; t++) {
            mask[t] = 1.0f;
            for (int feature = 0; feature < 6; feature++) {
                trajectory[t * 6 + feature] = (float) Math.sin((t + 1) * (feature + 1) * 0.07);
            }
        }
        long[] candidates = candidates();

        try (OnnxTensor trajectoryTensor = OnnxTensor.createTensor(environment, FloatBuffer.wrap(trajectory), new long[]{1, time, 6});
             OnnxTensor maskTensor = OnnxTensor.createTensor(environment, FloatBuffer.wrap(mask), new long[]{1, time});
             OnnxTensor candidateTensor = OnnxTensor.createTensor(environment, LongBuffer.wrap(candidates), new long[]{1, candidates.length})) {
            Map<String, OnnxTensor> inputs = new LinkedHashMap<>();
            inputs.put("trajectory", trajectoryTensor);
            inputs.put("trajectory_mask", maskTensor);
            inputs.put("candidate_ids", candidateTensor);
            try (OrtSession.Result result = session.run(inputs, java.util.Set.of("trajectory_logits"))) {
                report("trajectory_logits", (float[][]) result.get("trajectory_logits").orElseThrow().getValue());
            }
        }
    }

    private static long[] candidates() {
        long[] candidates = new long[64];
        for (int i = 0; i < candidates.length; i++) {
            candidates[i] = i;
        }
        return candidates;
    }

    private static void report(String name, float[][] values) {
        double checksum = 0.0;
        for (int i = 0; i < values[0].length; i++) {
            checksum += values[0][i] * (i + 1);
        }
        System.out.printf("%s shape=%dx%d checksum=%.9f%n", name, values.length, values[0].length, checksum);
    }
}
