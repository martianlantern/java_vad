package benchmark;

import com.orctom.vad4j.VAD;
import javax.sound.sampled.*;
import java.io.*;
import java.lang.management.ManagementFactory;
import com.sun.management.OperatingSystemMXBean;
import java.util.*;
import java.util.stream.Collectors;

public class Vad4jBenchmark {

    private static final int SAMPLE_RATE = 16000;
    private static final int BUFFER_DURATION_MS = 20;
    private static final int BUFFER_BYTES = SAMPLE_RATE * 2 * BUFFER_DURATION_MS / 1000;
    private static final float THRESHOLD = 0.6f;
    private static final int WARMUP_ITERATIONS = 200;

    public static void main(String[] args) throws Exception {
        String wavDir = args.length > 0 ? args[0] : "wav";
        String outFile = args.length > 1 ? args[1] : "vad4j_results.txt";

        File wavFolder = new File(wavDir);
        File[] wavFiles = wavFolder.listFiles((d, n) -> n.endsWith(".wav"));
        if (wavFiles == null || wavFiles.length == 0) {
            System.err.println("No WAV files found in: " + wavFolder.getAbsolutePath());
            return;
        }
        Arrays.sort(wavFiles);

        PrintStream realOut = System.out;
        PrintStream nullOut = new PrintStream(OutputStream.nullOutputStream());

        System.setOut(nullOut);
        System.setErr(nullOut);

        long loadStart = System.nanoTime();
        VAD vad = new VAD();
        long loadTime = System.nanoTime() - loadStart;

        byte[] warmup = new byte[BUFFER_BYTES];
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            vad.speechProbability(warmup);
        }

        OperatingSystemMXBean osBean = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

        List<FileResult> results = new ArrayList<>();
        List<Double> allLatencies = new ArrayList<>();

        for (File wavFile : wavFiles) {
            FileResult fr = benchmarkFile(vad, wavFile, osBean);
            results.add(fr);
            allLatencies.addAll(fr.latenciesUs);
        }

        vad.close();

        System.setOut(realOut);
        System.setErr(realOut);

        PrintStream out = new PrintStream(new FileOutputStream(outFile));

        out.println("=" .repeat(80));
        out.println("VAD4J BENCHMARK");
        out.println("=" .repeat(80));
        out.printf("Backend:     kvad native (JNA)%n");
        out.printf("Sample rate: %d Hz%n", SAMPLE_RATE);
        out.printf("Buffer size: %d bytes (%d ms)%n", BUFFER_BYTES, BUFFER_DURATION_MS);
        out.printf("Threshold:   %.2f%n", THRESHOLD);
        out.printf("WAV files:   %d%n", wavFiles.length);
        out.printf("JVM:         %s %s%n", System.getProperty("java.vendor"), System.getProperty("java.version"));
        out.printf("OS:          %s %s%n", System.getProperty("os.name"), System.getProperty("os.arch"));
        out.printf("%nVAD init time: %.2f ms%n", loadTime / 1e6);
        out.printf("Warmup:      %d frames%n%n", WARMUP_ITERATIONS);

        out.println("=" .repeat(80));
        out.println("PER-FILE RESULTS");
        out.println("=" .repeat(80));
        out.printf("%-45s %8s %10s %10s %10s %10s %8s%n",
                "File", "Frames", "Mean(us)", "P50(us)", "P95(us)", "P99(us)", "Speech%");
        out.println("-".repeat(108));

        for (FileResult fr : results) {
            List<Double> sorted = fr.latenciesUs.stream().sorted().collect(Collectors.toList());
            double mean = sorted.stream().mapToDouble(d -> d).average().orElse(0);
            double p50 = percentile(sorted, 50);
            double p95 = percentile(sorted, 95);
            double p99 = percentile(sorted, 99);
            double speechPct = fr.speechFrames * 100.0 / fr.totalFrames;

            out.printf("%-45s %8d %10.1f %10.1f %10.1f %10.1f %7.1f%%%n",
                    fr.filename, fr.totalFrames, mean, p50, p95, p99, speechPct);
        }

        out.println("\n" + "=" .repeat(80));
        out.println("AGGREGATE STATISTICS");
        out.println("=" .repeat(80));

        Collections.sort(allLatencies);
        double totalFrames = allLatencies.size();
        double mean = allLatencies.stream().mapToDouble(d -> d).average().orElse(0);
        double p50 = percentile(allLatencies, 50);
        double p95 = percentile(allLatencies, 95);
        double p99 = percentile(allLatencies, 99);
        double min = allLatencies.get(0);
        double max = allLatencies.get(allLatencies.size() - 1);
        double stddev = Math.sqrt(allLatencies.stream().mapToDouble(d -> (d - mean) * (d - mean)).average().orElse(0));

        out.printf("Total frames:         %.0f%n", totalFrames);
        out.printf("Mean latency:         %.1f us (%.3f ms)%n", mean, mean / 1000);
        out.printf("Median latency (P50): %.1f us (%.3f ms)%n", p50, p50 / 1000);
        out.printf("P95 latency:          %.1f us (%.3f ms)%n", p95, p95 / 1000);
        out.printf("P99 latency:          %.1f us (%.3f ms)%n", p99, p99 / 1000);
        out.printf("Min latency:          %.1f us%n", min);
        out.printf("Max latency:          %.1f us%n", max);
        out.printf("Std dev:              %.1f us%n", stddev);
        out.printf("Throughput:           %.0f frames/sec%n", 1_000_000.0 / mean);

        double frameDurationUs = BUFFER_DURATION_MS * 1000.0;
        double rtf = mean / frameDurationUs;
        out.printf("Real-time factor:     %.4f (%.1fx faster than real-time)%n", rtf, 1.0 / rtf);

        out.println("\n--- CPU UTILIZATION DURING BENCHMARK ---");
        double cpuAvg = results.stream().mapToDouble(r -> r.cpuLoad).average().orElse(0);
        for (FileResult fr : results) {
            out.printf("  %-45s CPU: %.1f%%%n", fr.filename, fr.cpuLoad * 100);
        }
        out.printf("  Average process CPU load: %.1f%%%n", cpuAvg * 100);

        long totalSpeech = results.stream().mapToLong(r -> r.speechFrames).sum();
        long totalAll = results.stream().mapToLong(r -> r.totalFrames).sum();
        out.printf("%nOverall speech detection rate: %.1f%% (%d/%d frames)%n",
                totalSpeech * 100.0 / totalAll, totalSpeech, totalAll);

        out.println("\n--- LATENCY HISTOGRAM (us) ---");
        printHistogram(allLatencies, out);

        out.println("\n" + "=" .repeat(80));
        out.println("BENCHMARK COMPLETE");
        out.println("=" .repeat(80));
        out.close();

        realOut.println("Results written to: " + outFile);
    }

    private static FileResult benchmarkFile(VAD vad, File wavFile,
                                             OperatingSystemMXBean osBean) throws Exception {
        byte[] audioBytes = readWavBytes(wavFile);
        List<Double> latencies = new ArrayList<>();
        int speechFrames = 0;
        int totalFrames = 0;

        double cpuBefore = osBean.getProcessCpuLoad();
        long wallStart = System.nanoTime();

        for (int offset = 0; offset + BUFFER_BYTES <= audioBytes.length; offset += BUFFER_BYTES) {
            byte[] chunk = new byte[BUFFER_BYTES];
            System.arraycopy(audioBytes, offset, chunk, 0, BUFFER_BYTES);

            long t0 = System.nanoTime();
            float prob = vad.speechProbability(chunk);
            long t1 = System.nanoTime();

            latencies.add((t1 - t0) / 1000.0);
            if (prob >= THRESHOLD) speechFrames++;
            totalFrames++;
        }

        long wallEnd = System.nanoTime();
        double cpuAfter = osBean.getProcessCpuLoad();

        FileResult fr = new FileResult();
        fr.filename = wavFile.getName();
        fr.totalFrames = totalFrames;
        fr.speechFrames = speechFrames;
        fr.latenciesUs = latencies;
        fr.wallTimeMs = (wallEnd - wallStart) / 1e6;
        fr.cpuLoad = (cpuBefore + cpuAfter) / 2.0;
        fr.audioDurationSec = (audioBytes.length / 2) / (double) SAMPLE_RATE;
        return fr;
    }

    private static byte[] readWavBytes(File file) throws Exception {
        AudioInputStream ais = AudioSystem.getAudioInputStream(file);
        byte[] bytes = ais.readAllBytes();
        ais.close();
        return bytes;
    }

    private static double percentile(List<Double> sorted, int pct) {
        int idx = (int) Math.ceil(pct / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }

    private static void printHistogram(List<Double> latencies, PrintStream out) {
        double[] buckets = {0, 5, 10, 15, 20, 25, 50, 75, 100, 150, 200, 500, 1000};
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
            out.printf("  %s us: %s %d%n", label, "#".repeat(barLen), counts[i]);
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
