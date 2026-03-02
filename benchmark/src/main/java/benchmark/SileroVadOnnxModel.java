package benchmark;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import java.util.HashMap;
import java.util.Map;

public class SileroVadOnnxModel implements AutoCloseable {

    private final OrtSession session;
    private float[][][] state;
    private float[][] context;
    private int lastSr = 0;
    private int lastBatchSize = 0;

    public SileroVadOnnxModel(String modelPath) throws OrtException {
        OrtEnvironment env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        opts.setInterOpNumThreads(1);
        opts.setIntraOpNumThreads(1);
        opts.addCPU(true);
        session = env.createSession(modelPath, opts);
        resetStates();
    }

    public void resetStates() {
        state = new float[2][1][128];
        context = new float[0][];
        lastSr = 0;
        lastBatchSize = 0;
    }

    public float call(float[] samples, int sr) throws OrtException {
        int numSamples = sr == 16000 ? 512 : 256;
        int contextSize = sr == 16000 ? 64 : 32;

        if (lastSr != 0 && lastSr != sr) resetStates();
        if (lastBatchSize == 0) lastBatchSize = 1;

        if (context.length == 0) context = new float[1][contextSize];

        float[] xWithContext = new float[contextSize + numSamples];
        System.arraycopy(context[0], 0, xWithContext, 0, contextSize);
        System.arraycopy(samples, 0, xWithContext, contextSize, Math.min(samples.length, numSamples));

        OrtEnvironment env = OrtEnvironment.getEnvironment();
        try (OnnxTensor inputTensor = OnnxTensor.createTensor(env, new float[][]{xWithContext});
             OnnxTensor stateTensor = OnnxTensor.createTensor(env, state);
             OnnxTensor srTensor = OnnxTensor.createTensor(env, new long[]{sr})) {

            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put("input", inputTensor);
            inputs.put("state", stateTensor);
            inputs.put("sr", srTensor);

            try (OrtSession.Result result = session.run(inputs)) {
                float[][] output = (float[][]) result.get(0).getValue();
                state = (float[][][]) result.get(1).getValue();
                System.arraycopy(xWithContext, xWithContext.length - contextSize, context[0], 0, contextSize);
                lastSr = sr;
                return output[0][0];
            }
        }
    }

    @Override
    public void close() throws OrtException {
        session.close();
    }
}
