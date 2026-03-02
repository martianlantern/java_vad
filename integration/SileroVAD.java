package com.spr.vad;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import java.io.Closeable;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class SileroVAD implements Closeable {

    public static final float THRESHOLD = 0.5F;
    public static final int PACKET_MIN_SIZE = 320;

    private static final int SAMPLE_RATE = 16000;
    private static final int WINDOW_SIZE = 512;
    private static final int CONTEXT_SIZE = 64;

    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final OrtSession session;
    private float[][][] state;
    private float[] context;

    public SileroVAD(String onnxModelPath) {
        try {
            OrtEnvironment env = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
            opts.setInterOpNumThreads(1);
            opts.setIntraOpNumThreads(1);
            opts.addCPU(true);
            session = env.createSession(onnxModelPath, opts);
            resetStates();
        } catch (OrtException e) {
            throw new IllegalStateException("Failed to init Silero VAD", e);
        }
    }

    private void resetStates() {
        state = new float[2][1][128];
        context = new float[CONTEXT_SIZE];
    }

    public float speechProbability(byte[] pcm) {
        if (pcm == null || pcm.length < PACKET_MIN_SIZE) return 0F;

        float[] samples = toFloatArray(pcm);
        float[] padded = new float[WINDOW_SIZE];
        System.arraycopy(samples, 0, padded, 0, Math.min(samples.length, WINDOW_SIZE));

        try {
            return infer(padded);
        } catch (OrtException e) {
            return 0.0F;
        }
    }

    public boolean isSpeech(byte[] pcm) {
        return speechProbability(pcm) >= THRESHOLD;
    }

    public boolean isSilent(byte[] pcm) {
        return speechProbability(pcm) < THRESHOLD;
    }

    private float infer(float[] samples) throws OrtException {
        float[] xWithContext = new float[CONTEXT_SIZE + WINDOW_SIZE];
        System.arraycopy(context, 0, xWithContext, 0, CONTEXT_SIZE);
        System.arraycopy(samples, 0, xWithContext, CONTEXT_SIZE, WINDOW_SIZE);

        OrtEnvironment env = OrtEnvironment.getEnvironment();
        try (OnnxTensor inputTensor = OnnxTensor.createTensor(env, new float[][]{xWithContext});
             OnnxTensor stateTensor = OnnxTensor.createTensor(env, state);
             OnnxTensor srTensor = OnnxTensor.createTensor(env, new long[]{SAMPLE_RATE})) {

            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put("input", inputTensor);
            inputs.put("state", stateTensor);
            inputs.put("sr", srTensor);

            try (OrtSession.Result result = session.run(inputs)) {
                float[][] output = (float[][]) result.get(0).getValue();
                state = (float[][][]) result.get(1).getValue();
                System.arraycopy(xWithContext, xWithContext.length - CONTEXT_SIZE, context, 0, CONTEXT_SIZE);
                return output[0][0];
            }
        }
    }

    private static float[] toFloatArray(byte[] pcm) {
        float[] result = new float[pcm.length / 2];
        for (int i = 0; i < result.length; i++) {
            short sample = (short) ((pcm[i * 2] & 0xff) | (pcm[i * 2 + 1] << 8));
            result[i] = sample / 32768.0f;
        }
        return result;
    }

    @Override
    public void close() {
        if (stopped.getAndSet(true)) return;
        try {
            session.close();
        } catch (OrtException ignored) {
        }
    }
}
