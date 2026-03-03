package annotator;

import ai.onnxruntime.*;
import java.util.*;

public class SileroVad implements AutoCloseable {

    private final OrtSession session;
    private static final int WINDOW = 512;
    private static final int CTX = 64;
    private static final int SR = 16000;

    public SileroVad(String modelPath) throws OrtException {
        OrtEnvironment env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        opts.setInterOpNumThreads(1);
        opts.setIntraOpNumThreads(1);
        opts.addCPU(true);
        session = env.createSession(modelPath, opts);
    }

    private static float infer(OrtSession session, float[] samples,
                               float[][][] state, float[] context) throws OrtException {
        float[] x = new float[CTX + WINDOW];
        System.arraycopy(context, 0, x, 0, CTX);
        System.arraycopy(samples, 0, x, CTX, Math.min(samples.length, WINDOW));

        OrtEnvironment env = OrtEnvironment.getEnvironment();
        try (OnnxTensor in = OnnxTensor.createTensor(env, new float[][]{x});
             OnnxTensor st = OnnxTensor.createTensor(env, state);
             OnnxTensor sr = OnnxTensor.createTensor(env, new long[]{SR})) {
            Map<String, OnnxTensor> inputs = Map.of("input", in, "state", st, "sr", sr);
            try (OrtSession.Result r = session.run(inputs)) {
                float[][] out = (float[][]) r.get(0).getValue();
                float[][][] newState = (float[][][]) r.get(1).getValue();
                System.arraycopy(newState[0][0], 0, state[0][0], 0, 128);
                System.arraycopy(newState[1][0], 0, state[1][0], 0, 128);
                System.arraycopy(x, x.length - CTX, context, 0, CTX);
                return out[0][0];
            }
        }
    }

    public record Segment(double start, double end) {}

    public List<Segment> getSpeechTimestamps(float[] audio, float threshold,
                                              int minSpeechMs, int minSilenceMs, int speechPadMs) throws OrtException {
        float[][][] localState = new float[2][1][128];
        float[] localContext = new float[CTX];

        float negThreshold = threshold - 0.15f;
        int minSpeechSamples = SR * minSpeechMs / 1000;
        int minSilenceSamples = SR * minSilenceMs / 1000;
        int speechPadSamples = SR * speechPadMs / 1000;

        List<Float> probs = new ArrayList<>();
        for (int i = 0; i + WINDOW <= audio.length; i += WINDOW) {
            float[] chunk = new float[WINDOW];
            System.arraycopy(audio, i, chunk, 0, WINDOW);
            probs.add(infer(session, chunk, localState, localContext));
        }

        boolean triggered = false;
        List<int[]> speeches = new ArrayList<>();
        int[] cur = null;
        int tempEnd = 0;

        for (int i = 0; i < probs.size(); i++) {
            float p = probs.get(i);
            if (p >= threshold && tempEnd != 0) tempEnd = 0;
            if (p >= threshold && !triggered) {
                triggered = true;
                cur = new int[]{WINDOW * i, 0};
                continue;
            }
            if (p < negThreshold && triggered) {
                if (tempEnd == 0) tempEnd = WINDOW * i;
                if (WINDOW * i - tempEnd < minSilenceSamples) continue;
                cur[1] = tempEnd;
                if (cur[1] - cur[0] > minSpeechSamples) speeches.add(cur);
                cur = null;
                tempEnd = 0;
                triggered = false;
            }
        }
        if (cur != null && (audio.length - cur[0]) > minSpeechSamples) {
            cur[1] = audio.length;
            speeches.add(cur);
        }

        for (int i = 0; i < speeches.size(); i++) {
            int[] s = speeches.get(i);
            if (i == 0) s[0] = Math.max(0, s[0] - speechPadSamples);
            if (i < speeches.size() - 1) {
                int[] next = speeches.get(i + 1);
                int gap = next[0] - s[1];
                if (gap < 2 * speechPadSamples) {
                    s[1] += gap / 2;
                    next[0] = Math.max(0, next[0] - gap / 2);
                } else {
                    s[1] = Math.min(audio.length, s[1] + speechPadSamples);
                    next[0] = Math.max(0, next[0] - speechPadSamples);
                }
            } else {
                s[1] = Math.min(audio.length, s[1] + speechPadSamples);
            }
        }

        List<Segment> result = new ArrayList<>();
        for (int[] s : speeches) {
            result.add(new Segment(
                    Math.floor(s[0] * 1000.0 / SR) / 1000.0,
                    Math.floor(s[1] * 1000.0 / SR) / 1000.0));
        }
        return result;
    }

    @Override
    public void close() throws OrtException {
        session.close();
    }
}
