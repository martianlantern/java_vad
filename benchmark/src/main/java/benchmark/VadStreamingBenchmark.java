package benchmark;

import javax.sound.sampled.*;
import java.io.File;
import java.lang.management.ManagementFactory;
import com.sun.management.OperatingSystemMXBean;
import java.util.*;
import java.util.stream.Collectors;

public class VadStreamingBenchmark {

    private static final int SAMPLE_RATE = 16000;
    private static final int WINDOW_SIZE = 512;
    private static final float START_THRESHOLD = 0.5f;
    private static final float END_THRESHOLD = 0.35f;
    private static final int BUFFER_DURATION_MS = 20;
    private static final int BUFFER_SAMPLES = SAMPLE_RATE * BUFFER_DURATION_MS / 1000;
    private static final int WARMUP_ITERATIONS = 100;

    public static void main(String[] args) throws Exception {
        String modelPath = args.length > 0 ? args[0] : "../silero-vad/src/silero_vad/data/silero_vad.onnx";
        String wavDir = args.length > 1 ? args[1] : "wav";

        File wavFolder = new File(wavDir);
        File[] wavFiles = wavFolder.listFiles((d, n) -> n.endsWith(".wav"));
        if (wavFiles == null || wavFiles.length == 0) {
            System.err.println("No WAV files found in: " + wavFolder.getAbsolutePath());
            return;
        }
        Arrays.sort(wavFiles);

        System.out.println("=" .repeat(80));
        System.out.println("SILERO VAD STREAMING BENCHMARK (vad4j-compatible 20ms buffer pattern)");
        System.out.println("=" .repeat(80));
        System.out.printf("Model:             %s%n", modelPath);
        System.out.printf("Sample rate:       %d Hz%n", SAMPLE_RATE);
        System.out.printf("Input buffer:      %d ms (%d samples, %d bytes PCM16)%n",
                BUFFER_DURATION_MS, BUFFER_SAMPLES, BUFFER_SAMPLES * 2);
        System.out.printf("ONNX window:       %d samples (%.1f ms)%n", WINDOW_SIZE, WINDOW_SIZE * 1000.0 / SAMPLE_RATE);
        System.out.printf("Accumulation ratio: %d buffers per ONNX call (%.0f ms / %d ms = ~%dx)%n",
                WINDOW_SIZE / BUFFER_SAMPLES, WINDOW_SIZE * 1000.0 / SAMPLE_RATE,
                BUFFER_DURATION_MS, WINDOW_SIZE / BUFFER_SAMPLES);
        System.out.printf("Thresholds:        start=%.2f, end=%.2f%n", START_THRESHOLD, END_THRESHOLD);
        System.out.println();

        SileroVadOnnxModel model = new SileroVadOnnxModel(modelPath);

        System.out.println("--- WARMUP ---");
        model.resetStates();
        float[] warmup = new float[WINDOW_SIZE];
        for (int i = 0; i < WARMUP_ITERATIONS; i++) model.call(warmup, SAMPLE_RATE);
        System.out.println("Complete.\n");

        OperatingSystemMXBean osBean = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        List<StreamResult> results = new ArrayList<>();
        List<Double> allBufferLatencies = new ArrayList<>();
        List<Double> allInferenceLatencies = new ArrayList<>();

        for (File wavFile : wavFiles) {
            StreamResult sr = streamBenchmark(model, wavFile, osBean);
            results.add(sr);
            allBufferLatencies.addAll(sr.bufferLatenciesUs);
            allInferenceLatencies.addAll(sr.inferenceLatenciesUs);
        }

        System.out.println("\n" + "=" .repeat(80));
        System.out.println("PER-FILE RESULTS (20ms buffer processing latency)");
        System.out.println("=" .repeat(80));
        System.out.printf("%-45s %8s %8s %10s %10s %10s %8s %8s%n",
                "File", "Buffers", "Infers", "BufAvg(us)", "InfAvg(us)", "InfP95(us)", "Speech%", "Segs");
        System.out.println("-".repeat(115));

        for (StreamResult sr : results) {
            double bufAvg = sr.bufferLatenciesUs.stream().mapToDouble(d -> d).average().orElse(0);
            double infAvg = sr.inferenceLatenciesUs.stream().mapToDouble(d -> d).average().orElse(0);
            List<Double> sorted = sr.inferenceLatenciesUs.stream().sorted().toList();
            double infP95 = percentile(sorted, 95);
            double speechPct = sr.speechBuffers * 100.0 / sr.totalBuffers;
            System.out.printf("%-45s %8d %8d %10.1f %10.1f %10.1f %7.1f%% %8d%n",
                    sr.filename, sr.totalBuffers, sr.inferenceCount, bufAvg, infAvg, infP95, speechPct, sr.segments);
        }

        System.out.println("\n" + "=" .repeat(80));
        System.out.println("AGGREGATE STREAMING STATISTICS");
        System.out.println("=" .repeat(80));

        long totalBuffers = results.stream().mapToLong(r -> r.totalBuffers).sum();
        long totalInferences = results.stream().mapToLong(r -> r.inferenceCount).sum();

        Collections.sort(allBufferLatencies);
        double bufMean = allBufferLatencies.stream().mapToDouble(d -> d).average().orElse(0);
        double bufP50 = percentile(allBufferLatencies, 50);
        double bufP95 = percentile(allBufferLatencies, 95);
        double bufP99 = percentile(allBufferLatencies, 99);

        Collections.sort(allInferenceLatencies);
        double infMean = allInferenceLatencies.stream().mapToDouble(d -> d).average().orElse(0);
        double infP50 = percentile(allInferenceLatencies, 50);
        double infP95 = percentile(allInferenceLatencies, 95);
        double infP99 = percentile(allInferenceLatencies, 99);

        System.out.println("\n--- Per 20ms buffer (includes accumulation + optional inference) ---");
        System.out.printf("Total buffers:  %d%n", totalBuffers);
        System.out.printf("Mean latency:   %.1f us (%.3f ms)%n", bufMean, bufMean / 1000);
        System.out.printf("P50 latency:    %.1f us%n", bufP50);
        System.out.printf("P95 latency:    %.1f us%n", bufP95);
        System.out.printf("P99 latency:    %.1f us%n", bufP99);

        System.out.println("\n--- Per ONNX inference call (every ~32ms) ---");
        System.out.printf("Total inferences: %d%n", totalInferences);
        System.out.printf("Mean latency:     %.1f us (%.3f ms)%n", infMean, infMean / 1000);
        System.out.printf("P50 latency:      %.1f us%n", infP50);
        System.out.printf("P95 latency:      %.1f us%n", infP95);
        System.out.printf("P99 latency:      %.1f us%n", infP99);

        double bufferDurationUs = BUFFER_DURATION_MS * 1000.0;
        System.out.printf("\nBuffer budget:    %.0f us (20ms)%n", bufferDurationUs);
        System.out.printf("Mean buf usage:   %.2f%% of budget%n", bufMean / bufferDurationUs * 100);
        System.out.printf("P99 buf usage:    %.2f%% of budget%n", bufP99 / bufferDurationUs * 100);

        double cpuAvg = results.stream().mapToDouble(r -> r.cpuLoad).average().orElse(0);
        System.out.printf("\nAvg CPU load:     %.1f%%%n", cpuAvg * 100);

        model.close();
        System.out.println("\n" + "=" .repeat(80));
        System.out.println("STREAMING BENCHMARK COMPLETE");
        System.out.println("=" .repeat(80));
    }

    private static StreamResult streamBenchmark(SileroVadOnnxModel model, File wavFile,
                                                 OperatingSystemMXBean osBean) throws Exception {
        float[] audio = readWav(wavFile);
        String filename = wavFile.getName();
        System.out.printf("Streaming: %-40s (%.1fs)%n", filename, audio.length / (double) SAMPLE_RATE);

        model.resetStates();
        List<Double> bufferLatencies = new ArrayList<>();
        List<Double> inferenceLatencies = new ArrayList<>();
        float[] accumulator = new float[WINDOW_SIZE];
        int accOffset = 0;
        int totalBuffers = 0;
        int speechBuffers = 0;
        int inferenceCount = 0;
        int segments = 0;
        boolean inSpeech = false;
        float lastProb = 0f;

        double cpuBefore = osBean.getProcessCpuLoad();

        for (int offset = 0; offset + BUFFER_SAMPLES <= audio.length; offset += BUFFER_SAMPLES) {
            long t0 = System.nanoTime();

            int copyLen = Math.min(BUFFER_SAMPLES, WINDOW_SIZE - accOffset);
            System.arraycopy(audio, offset, accumulator, accOffset, copyLen);
            accOffset += copyLen;

            if (accOffset >= WINDOW_SIZE) {
                long ti0 = System.nanoTime();
                lastProb = model.call(accumulator, SAMPLE_RATE);
                long ti1 = System.nanoTime();
                inferenceLatencies.add((ti1 - ti0) / 1000.0);
                inferenceCount++;
                accOffset = 0;

                boolean wasSpeech = inSpeech;
                if (lastProb >= START_THRESHOLD) inSpeech = true;
                if (lastProb < END_THRESHOLD) inSpeech = false;
                if (!wasSpeech && inSpeech) segments++;
            }

            if (lastProb >= START_THRESHOLD) speechBuffers++;
            totalBuffers++;

            long t1 = System.nanoTime();
            bufferLatencies.add((t1 - t0) / 1000.0);
        }

        double cpuAfter = osBean.getProcessCpuLoad();
        double cpuLoad = (cpuBefore + cpuAfter) / 2.0;

        double bufAvg = bufferLatencies.stream().mapToDouble(d -> d).average().orElse(0);
        System.out.printf("  -> %d buffers, %d inferences, avg buf %.1f us, %d segments%n",
                totalBuffers, inferenceCount, bufAvg, segments);

        StreamResult sr = new StreamResult();
        sr.filename = filename;
        sr.totalBuffers = totalBuffers;
        sr.speechBuffers = speechBuffers;
        sr.inferenceCount = inferenceCount;
        sr.segments = segments;
        sr.bufferLatenciesUs = bufferLatencies;
        sr.inferenceLatenciesUs = inferenceLatencies;
        sr.cpuLoad = cpuLoad;
        return sr;
    }

    private static float[] readWav(File file) throws Exception {
        AudioInputStream ais = AudioSystem.getAudioInputStream(file);
        byte[] bytes = ais.readAllBytes();
        ais.close();
        float[] data = new float[bytes.length / 2];
        for (int i = 0; i < data.length; i++) {
            short sample = (short) ((bytes[i * 2] & 0xff) | (bytes[i * 2 + 1] << 8));
            data[i] = sample / 32768.0f;
        }
        return data;
    }

    private static double percentile(List<Double> sorted, int pct) {
        int idx = (int) Math.ceil(pct / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }

    static class StreamResult {
        String filename;
        int totalBuffers;
        int speechBuffers;
        int inferenceCount;
        int segments;
        List<Double> bufferLatenciesUs;
        List<Double> inferenceLatenciesUs;
        double cpuLoad;
    }
}
