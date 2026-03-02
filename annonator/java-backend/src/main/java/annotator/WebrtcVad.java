package annotator;

import io.github.givimad.libfvadjni.VoiceActivityDetector;
import java.util.*;

public class WebrtcVad {

    private static volatile Boolean available;

    public static boolean isAvailable() {
        if (available == null) {
            synchronized (WebrtcVad.class) {
                if (available == null) {
                    try {
                        VoiceActivityDetector.loadLibrary();
                        VoiceActivityDetector test = VoiceActivityDetector.newInstance();
                        test.close();
                        available = true;
                    } catch (Throwable t) {
                        System.err.println("WebRTC VAD (libfvad) not available: " + t.getMessage());
                        available = false;
                    }
                }
            }
        }
        return available;
    }

    public record Segment(double start, double end) {}

    public static List<Segment> run(byte[] pcm16, int sampleRate, int frameDurMs,
                                     int vadMode, float threshold, int padMs) {
        if (!isAvailable()) return List.of();
        try {
            VoiceActivityDetector vad = VoiceActivityDetector.newInstance();
            vad.setMode(VoiceActivityDetector.Mode.values()[vadMode]);
            vad.setSampleRate(VoiceActivityDetector.SampleRate.fromValue(sampleRate));

            int frameSamples = sampleRate * frameDurMs / 1000;
            int frameBytes = frameSamples * 2;
            double frameSec = frameDurMs / 1000.0;
            int padFrames = padMs / frameDurMs;

            List<Boolean> speeches = new ArrayList<>();
            List<double[]> times = new ArrayList<>();
            double ts = 0.0;

            for (int off = 0; off + frameBytes <= pcm16.length; off += frameBytes) {
                short[] frame = new short[frameSamples];
                for (int i = 0; i < frameSamples; i++)
                    frame[i] = (short) ((pcm16[off + i * 2] & 0xff) | (pcm16[off + i * 2 + 1] << 8));
                boolean speech = vad.process(frame);
                speeches.add(speech);
                times.add(new double[]{ts, frameSec});
                ts += frameSec;
            }
            vad.close();
            return collect(speeches, times, threshold, padFrames);
        } catch (Exception e) {
            System.err.println("WebRTC VAD error: " + e.getMessage());
            return List.of();
        }
    }

    private static List<Segment> collect(List<Boolean> speeches, List<double[]> times,
                                          float threshold, int padFrames) {
        List<Segment> result = new ArrayList<>();
        Deque<Integer> ring = new ArrayDeque<>();
        boolean triggered = false;
        double start = 0;

        for (int i = 0; i < speeches.size(); i++) {
            boolean speech = speeches.get(i);

            if (!triggered) {
                ring.addLast(speech ? 1 : 0);
                if (ring.size() > padFrames) ring.removeFirst();
                if (ring.size() == padFrames) {
                    int cnt = ring.stream().mapToInt(Integer::intValue).sum();
                    if (cnt > threshold * padFrames) {
                        triggered = true;
                        start = times.get(Math.max(0, i - ring.size() + 1))[0];
                        ring.clear();
                    }
                }
            } else {
                ring.addLast(speech ? 0 : 1);
                if (ring.size() > padFrames) ring.removeFirst();
                if (ring.size() == padFrames) {
                    int cnt = ring.stream().mapToInt(Integer::intValue).sum();
                    if (cnt > threshold * padFrames) {
                        triggered = false;
                        double end = times.get(i)[0] + times.get(i)[1];
                        result.add(new Segment(
                                Math.round(start * 1000.0) / 1000.0,
                                Math.round(end * 1000.0) / 1000.0));
                        ring.clear();
                    }
                }
            }
        }

        if (triggered && !times.isEmpty()) {
            int last = times.size() - 1;
            double end = times.get(last)[0] + times.get(last)[1];
            result.add(new Segment(
                    Math.round(start * 1000.0) / 1000.0,
                    Math.round(end * 1000.0) / 1000.0));
        }
        return result;
    }
}
