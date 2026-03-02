package benchmark;

import javax.sound.sampled.*;
import java.io.File;
import java.lang.management.ManagementFactory;
import com.sun.management.OperatingSystemMXBean;
import java.util.*;
import java.util.stream.Collectors;

public class VadBenchmark {

    private static final int SAMPLE_RATE = 16000;
    private static final int WINDOW_SIZE = 512;
    private static final float THRESHOLD = 0.5f;
    private static final int WARMUP_ITERATIONS = 50;

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
        System.out.println("SILERO VAD BENCHMARK");
        System.out.println("=" .repeat(80));
        System.out.printf("Model:       %s%n", modelPath);
        System.out.printf("Sample rate: %d Hz%n", SAMPLE_RATE);
        System.out.printf("Window size: %d samples (%.1f ms)%n", WINDOW_SIZE, WINDOW_SIZE * 1000.0 / SAMPLE_RATE);
        System.out.printf("Threshold:   %.2f%n", THRESHOLD);
        System.out.printf("WAV files:   %d%n", wavFiles.length);
        System.out.printf("JVM:         %s %s%n", System.getProperty("java.vendor"), System.getProperty("java.version"));
        System.out.printf("OS:          %s %s%n", System.getProperty("os.name"), System.getProperty("os.arch"));
        System.out.println();

        long loadStart = System.nanoTime();
        SileroVadOnnxModel model = new SileroVadOnnxModel(modelPath);
        long loadTime = System.nanoTime() - loadStart;
        System.out.printf("Model load time: %.2f ms%n%n", loadTime / 1e6);

        System.out.println("--- WARMUP (" + WARMUP_ITERATIONS + " frames) ---");
        float[] warmupChunk = new float[WINDOW_SIZE];
        model.resetStates();
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            model.call(warmupChunk, SAMPLE_RATE);
        }
        System.out.println("Warmup complete.\n");

        OperatingSystemMXBean osBean = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

        List<FileResult> results = new ArrayList<>();
        List<Double> allLatencies = new ArrayList<>();

        for (File wavFile : wavFiles) {
            FileResult fr = benchmarkFile(model, wavFile, osBean);
            results.add(fr);
            allLatencies.addAll(fr.latenciesUs);
        }

        System.out.println("\n" + "=" .repeat(80));
        System.out.println("PER-FILE RESULTS");
        System.out.println("=" .repeat(80));
        System.out.printf("%-45s %8s %10s %10s %10s %10s %8s%n",
                "File", "Frames", "Mean(us)", "P50(us)", "P95(us)", "P99(us)", "Speech%");
        System.out.println("-".repeat(108));

        for (FileResult fr : results) {
            List<Double> sorted = fr.latenciesUs.stream().sorted().collect(Collectors.toList());
            double mean = sorted.stream().mapToDouble(d -> d).average().orElse(0);
            double p50 = percentile(sorted, 50);
            double p95 = percentile(sorted, 95);
            double p99 = percentile(sorted, 99);
            double speechPct = fr.speechFrames * 100.0 / fr.totalFrames;

            System.out.printf("%-45s %8d %10.1f %10.1f %10.1f %10.1f %7.1f%%%n",
                    fr.filename, fr.totalFrames, mean, p50, p95, p99, speechPct);
        }

        System.out.println("\n" + "=" .repeat(80));
        System.out.println("AGGREGATE STATISTICS");
        System.out.println("=" .repeat(80));

        Collections.sort(allLatencies);
        double totalFrames = allLatencies.size();
        double mean = allLatencies.stream().mapToDouble(d -> d).average().orElse(0);
        double p50 = percentile(allLatencies, 50);
        double p95 = percentile(allLatencies, 95);
        double p99 = percentile(allLatencies, 99);
        double min = allLatencies.get(0);
        double max = allLatencies.get(allLatencies.size() - 1);
        double stddev = Math.sqrt(allLatencies.stream().mapToDouble(d -> (d - mean) * (d - mean)).average().orElse(0));

        System.out.printf("Total frames:         %.0f%n", totalFrames);
        System.out.printf("Mean latency:         %.1f us (%.3f ms)%n", mean, mean / 1000);
        System.out.printf("Median latency (P50): %.1f us (%.3f ms)%n", p50, p50 / 1000);
        System.out.printf("P95 latency:          %.1f us (%.3f ms)%n", p95, p95 / 1000);
        System.out.printf("P99 latency:          %.1f us (%.3f ms)%n", p99, p99 / 1000);
        System.out.printf("Min latency:          %.1f us%n", min);
        System.out.printf("Max latency:          %.1f us%n", max);
        System.out.printf("Std dev:              %.1f us%n", stddev);
        System.out.printf("Throughput:           %.0f frames/sec%n", 1_000_000.0 / mean);

        double frameDurationUs = WINDOW_SIZE * 1_000_000.0 / SAMPLE_RATE;
        double rtf = mean / frameDurationUs;
        System.out.printf("Real-time factor:     %.4f (%.1fx faster than real-time)%n", rtf, 1.0 / rtf);

        System.out.println("\n--- CPU UTILIZATION DURING BENCHMARK ---");
        double cpuAvg = results.stream().mapToDouble(r -> r.cpuLoad).average().orElse(0);
        for (FileResult fr : results) {
            System.out.printf("  %-45s CPU: %.1f%%%n", fr.filename, fr.cpuLoad * 100);
        }
        System.out.printf("  Average process CPU load: %.1f%%%n", cpuAvg * 100);

        long totalSpeech = results.stream().mapToLong(r -> r.speechFrames).sum();
        long totalAll = results.stream().mapToLong(r -> r.totalFrames).sum();
        System.out.printf("%nOverall speech detection rate: %.1f%% (%d/%d frames)%n",
                totalSpeech * 100.0 / totalAll, totalSpeech, totalAll);

        System.out.println("\n--- LATENCY HISTOGRAM (us) ---");
        printHistogram(allLatencies);

        model.close();
        System.out.println("\n" + "=" .repeat(80));
        System.out.println("BENCHMARK COMPLETE");
        System.out.println("=" .repeat(80));
    }

    private static FileResult benchmarkFile(SileroVadOnnxModel model, File wavFile,
                                             OperatingSystemMXBean osBean) throws Exception {
        float[] audio = readWav(wavFile);
        String filename = wavFile.getName();
        double durationSec = audio.length / (double) SAMPLE_RATE;

        System.out.printf("Processing: %-40s (%.1fs, %d samples)%n", filename, durationSec, audio.length);

        model.resetStates();
        List<Double> latencies = new ArrayList<>();
        int speechFrames = 0;
        int totalFrames = 0;

        double cpuBefore = osBean.getProcessCpuLoad();
        long wallStart = System.nanoTime();

        for (int offset = 0; offset + WINDOW_SIZE <= audio.length; offset += WINDOW_SIZE) {
            float[] chunk = new float[WINDOW_SIZE];
            System.arraycopy(audio, offset, chunk, 0, WINDOW_SIZE);

            long t0 = System.nanoTime();
            float prob = model.call(chunk, SAMPLE_RATE);
            long t1 = System.nanoTime();

            latencies.add((t1 - t0) / 1000.0);
            if (prob >= THRESHOLD) speechFrames++;
            totalFrames++;
        }

        long wallEnd = System.nanoTime();
        double cpuAfter = osBean.getProcessCpuLoad();
        double cpuLoad = (cpuBefore + cpuAfter) / 2.0;

        double wallMs = (wallEnd - wallStart) / 1e6;
        double avgUs = latencies.stream().mapToDouble(d -> d).average().orElse(0);
        System.out.printf("  -> %d frames, avg %.1f us/frame, wall %.1f ms, speech %.1f%%%n",
                totalFrames, avgUs, wallMs, speechFrames * 100.0 / totalFrames);

        FileResult fr = new FileResult();
        fr.filename = filename;
        fr.totalFrames = totalFrames;
        fr.speechFrames = speechFrames;
        fr.latenciesUs = latencies;
        fr.wallTimeMs = wallMs;
        fr.cpuLoad = cpuLoad;
        fr.audioDurationSec = durationSec;
        return fr;
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

    private static void printHistogram(List<Double> latencies) {
        double[] buckets = {0, 25, 50, 75, 100, 150, 200, 300, 500, 1000, 2000, 5000};
        int[] counts = new int[buckets.length];
        for (double lat : latencies) {
            for (int i = buckets.length - 1; i >= 0; i--) {
                if (lat >= buckets[i]) { counts[i]++; break; }
            }
        }
        int maxCount = Arrays.stream(counts).max().orElse(1);
        for (int i = 0; i < buckets.length; i++) {
            String label = i < buckets.length - 1
                    ? String.format("%5.0f-%5.0f", buckets[i], buckets[Math.min(i + 1, buckets.length - 1)])
                    : String.format("%5.0f+     ", buckets[i]);
            int barLen = (int) (40.0 * counts[i] / maxCount);
            System.out.printf("  %s us: %s %d%n", label, "#".repeat(barLen), counts[i]);
        }
    }

    static class FileResult {
        String filename;
        int totalFrames;
        int speechFrames;
        List<Double> latenciesUs;
        double wallTimeMs;
        double cpuLoad;
        double audioDurationSec;
    }
}
