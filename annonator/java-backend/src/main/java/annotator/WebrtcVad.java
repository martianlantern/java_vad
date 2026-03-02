package annotator;

import java.util.*;

public class WebrtcVad {

    private static volatile Boolean available;

    public static boolean isAvailable() {
        if (available == null) {
            synchronized (WebrtcVad.class) {
                if (available == null) {
                    try {
                        new Vad4j().close();
                        available = true;
                    } catch (Throwable t) {
                        System.err.println("vad4j (WebRTC VAD) not available: " + t.getMessage());
                        System.err.println("WebRTC VAD will be disabled. Silero VAD will still work.");
                        available = false;
                    }
                }
            }
        }
        return available;
    }

    public record Segment(double start, double end) {}

    public static List<Segment> run(byte[] pcm16, int sampleRate, int frameDurMs,
                                     float threshold, int padMs) {
        if (!isAvailable()) return List.of();
        try (Vad4j vad = new Vad4j()) {
            int frameBytes = sampleRate * 2 * frameDurMs / 1000;
            double frameSec = frameDurMs / 1000.0;
            int padFrames = padMs / frameDurMs;

            List<boolean[]> frameData = new ArrayList<>();
            List<double[]> frameTimes = new ArrayList<>();

            double ts = 0.0;
            for (int off = 0; off + frameBytes <= pcm16.length; off += frameBytes) {
                byte[] chunk = new byte[frameBytes];
                System.arraycopy(pcm16, off, chunk, 0, frameBytes);
                boolean speech = vad.isSpeech(chunk);
                frameData.add(new boolean[]{speech});
                frameTimes.add(new double[]{ts, frameSec});
                ts += frameSec;
            }

            return collect(frameData, frameTimes, threshold, padFrames);
        }
    }

    private static List<Segment> collect(List<boolean[]> frames, List<double[]> times,
                                          float threshold, int padFrames) {
        List<Segment> result = new ArrayList<>();
        Deque<Integer> ring = new ArrayDeque<>();
        boolean triggered = false;
        double start = 0;

        for (int i = 0; i < frames.size(); i++) {
            boolean speech = frames.get(i)[0];

            if (!triggered) {
                ring.addLast(speech ? 1 : 0);
                if (ring.size() > padFrames) ring.removeFirst();

                if (ring.size() == padFrames) {
                    int speechCount = ring.stream().mapToInt(Integer::intValue).sum();
                    if (speechCount > threshold * padFrames) {
                        triggered = true;
                        int oldest = i - ring.size() + 1;
                        start = times.get(Math.max(0, oldest))[0];
                        ring.clear();
                    }
                }
            } else {
                ring.addLast(speech ? 0 : 1);
                if (ring.size() > padFrames) ring.removeFirst();

                if (ring.size() == padFrames) {
                    int silenceCount = ring.stream().mapToInt(Integer::intValue).sum();
                    if (silenceCount > threshold * padFrames) {
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
