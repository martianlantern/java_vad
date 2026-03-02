package annotator;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import javax.sound.sampled.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

public class Server {

    private static final Gson GSON = new GsonBuilder().create();
    private static Path BASE_DIR;
    private static Path AUDIOS_DIR;
    private static Path ANNOTATIONS_FILE;
    private static Path STATIC_DIR;
    private static String BASE_PATH = "";
    private static String MODEL_PATH;
    private static SileroVad sileroVad;

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 9596;
        BASE_DIR = Path.of(args.length > 1 ? args[1] : "..").toAbsolutePath().normalize();
        MODEL_PATH = args.length > 2 ? args[2] : findModel();
        AUDIOS_DIR = BASE_DIR.resolve("audios");
        ANNOTATIONS_FILE = BASE_DIR.resolve("annotations.json");
        STATIC_DIR = BASE_DIR.resolve("static");

        Path configFile = BASE_DIR.resolve("config.json");
        if (Files.exists(configFile)) {
            Map<String, String> cfg = GSON.fromJson(Files.readString(configFile), new TypeToken<Map<String, String>>(){}.getType());
            String bp = cfg.getOrDefault("base_path", "");
            if (bp != null && !bp.isBlank() && !bp.equals("/")) {
                BASE_PATH = bp.replaceAll("/+$", "");
            }
        }

        System.out.printf("Starting Java VAD Annotator on port %d%n", port);
        System.out.printf("Base dir:   %s%n", BASE_DIR);
        System.out.printf("Audios:     %s%n", AUDIOS_DIR);
        System.out.printf("Model:      %s%n", MODEL_PATH);
        System.out.printf("Base path:  %s%n", BASE_PATH.isEmpty() ? "/" : BASE_PATH);

        sileroVad = new SileroVad(MODEL_PATH);
        System.out.printf("vad4j (WebRTC): %s%n", WebrtcVad.isAvailable() ? "available" : "unavailable (native lib arch mismatch)");

        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));

        String pfx = BASE_PATH.isEmpty() ? "/" : BASE_PATH + "/";
        server.createContext(pfx, Server::handleRequest);
        if (!BASE_PATH.isEmpty()) {
            server.createContext(BASE_PATH, Server::handleRequest);
        }

        server.start();
        System.out.printf("Listening at http://0.0.0.0:%d%s%n", port, BASE_PATH.isEmpty() ? "/" : BASE_PATH);
    }

    private static String findModel() {
        String[] candidates = {
            "../silero-vad/src/silero_vad/data/silero_vad.onnx",
            "../../silero-vad/src/silero_vad/data/silero_vad.onnx",
        };
        for (String c : candidates) {
            Path p = Path.of(c).toAbsolutePath().normalize();
            if (Files.exists(p)) return p.toString();
        }
        return candidates[0];
    }

    private static void handleRequest(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String rel = BASE_PATH.isEmpty() ? path : path.substring(BASE_PATH.length());
        if (rel.isEmpty()) rel = "/";

        try {
            if (rel.equals("/")) serveIndex(ex);
            else if (rel.startsWith("/static/")) serveStatic(ex, rel.substring(8));
            else if (rel.startsWith("/api/audios")) listAudios(ex);
            else if (rel.startsWith("/api/audio/")) serveAudio(ex, decode(rel.substring(11)));
            else if (rel.startsWith("/api/duration/")) getDuration(ex, decode(rel.substring(14)));
            else if (rel.startsWith("/api/vad/")) runVad(ex, decode(rel.substring(9)));
            else if (rel.startsWith("/api/annotations/")) handleAnnotations(ex, decode(rel.substring(17)));
            else sendText(ex, 404, "Not found");
        } catch (Exception e) {
            e.printStackTrace();
            sendJson(ex, 500, Map.of("error", e.getMessage()));
        }
    }

    private static String decode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    private static void serveIndex(HttpExchange ex) throws IOException {
        String html = Files.readString(STATIC_DIR.resolve("index.html"));
        html = html.replace("__BASE_PATH__", BASE_PATH);
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(200, body.length);
        ex.getResponseBody().write(body);
        ex.close();
    }

    private static void serveStatic(HttpExchange ex, String filename) throws IOException {
        Path file = STATIC_DIR.resolve(filename).normalize();
        if (!file.startsWith(STATIC_DIR) || !Files.exists(file)) {
            sendText(ex, 404, "Not found");
            return;
        }
        String ct = switch (getExt(filename)) {
            case "css" -> "text/css";
            case "js" -> "application/javascript";
            case "html" -> "text/html";
            case "png" -> "image/png";
            case "svg" -> "image/svg+xml";
            default -> "application/octet-stream";
        };
        byte[] body = Files.readAllBytes(file);
        ex.getResponseHeaders().set("Content-Type", ct);
        ex.sendResponseHeaders(200, body.length);
        ex.getResponseBody().write(body);
        ex.close();
    }

    private static void listAudios(HttpExchange ex) throws IOException {
        List<Map<String, String>> files = new ArrayList<>();
        if (Files.exists(AUDIOS_DIR)) {
            try (Stream<Path> subdirs = Files.list(AUDIOS_DIR).filter(Files::isDirectory).sorted()) {
                subdirs.forEach(subdir -> {
                    try (Stream<Path> audioFiles = Files.list(subdir).sorted()) {
                        audioFiles.filter(f -> {
                            String ext = getExt(f.getFileName().toString());
                            return ext.equals("mp3") || ext.equals("wav") || ext.equals("ogg") || ext.equals("flac");
                        }).forEach(f -> {
                            String cat = subdir.getFileName().toString();
                            String name = f.getFileName().toString();
                            files.add(Map.of("name", name, "category", cat, "path", cat + "/" + name));
                        });
                    } catch (IOException ignored) {}
                });
            }
        }
        sendJson(ex, 200, files);
    }

    private static void serveAudio(HttpExchange ex, String filepath) throws IOException {
        Path file = AUDIOS_DIR.resolve(filepath).normalize();
        if (!file.startsWith(AUDIOS_DIR) || !Files.exists(file)) {
            sendText(ex, 404, "Audio not found");
            return;
        }
        String ct = switch (getExt(filepath)) {
            case "mp3" -> "audio/mpeg";
            case "wav" -> "audio/wav";
            case "ogg" -> "audio/ogg";
            case "flac" -> "audio/flac";
            default -> "application/octet-stream";
        };
        byte[] body = Files.readAllBytes(file);
        ex.getResponseHeaders().set("Content-Type", ct);
        ex.sendResponseHeaders(200, body.length);
        ex.getResponseBody().write(body);
        ex.close();
    }

    private static void getDuration(HttpExchange ex, String filepath) throws Exception {
        Path file = AUDIOS_DIR.resolve(filepath).normalize();
        AudioInputStream ais = AudioSystem.getAudioInputStream(file.toFile());
        AudioFormat fmt = ais.getFormat();
        long frames = ais.getFrameLength();
        double dur;
        if (frames > 0) {
            dur = frames / (double) fmt.getFrameRate();
        } else {
            byte[] all = ais.readAllBytes();
            dur = (all.length / (double) fmt.getFrameSize()) / fmt.getFrameRate();
        }
        ais.close();
        sendJson(ex, 200, Map.of("duration", Math.round(dur * 1000.0) / 1000.0));
    }

    private static void runVad(HttpExchange ex, String filepath) throws Exception {
        Path file = AUDIOS_DIR.resolve(filepath).normalize();
        Map<String, String> params = parseQuery(ex.getRequestURI().getRawQuery());

        float sileroThr = Float.parseFloat(params.getOrDefault("silero_threshold", "0.5"));
        int minSpeechMs = Integer.parseInt(params.getOrDefault("silero_min_speech_ms", "250"));
        int minSilenceMs = Integer.parseInt(params.getOrDefault("silero_min_silence_ms", "100"));
        int speechPadMs = Integer.parseInt(params.getOrDefault("silero_speech_pad_ms", "30"));

        int webrtcSr = Integer.parseInt(params.getOrDefault("webrtc_sample_rate", "8000"));
        int webrtcFrameMs = Integer.parseInt(params.getOrDefault("webrtc_frame_dur_ms", "20"));
        float webrtcThr = Float.parseFloat(params.getOrDefault("webrtc_threshold", "0.6"));
        int webrtcPadMs = Integer.parseInt(params.getOrDefault("webrtc_pad_ms", "300"));

        float[] audio16k = loadAudioAsFloat(file, 16000);
        List<SileroVad.Segment> sileroSegs;
        synchronized (sileroVad) {
            sileroSegs = sileroVad.getSpeechTimestamps(audio16k, sileroThr, minSpeechMs, minSilenceMs, speechPadMs);
        }

        List<Map<String, Double>> sileroList = sileroSegs.stream()
                .map(s -> Map.of("start", s.start(), "end", s.end()))
                .toList();

        List<Map<String, Double>> webrtcList;
        try {
            byte[] pcmWebrtc = loadAudioAsPcm(file, webrtcSr);
            List<WebrtcVad.Segment> webrtcSegs = WebrtcVad.run(pcmWebrtc, webrtcSr, webrtcFrameMs, webrtcThr, webrtcPadMs);
            webrtcList = webrtcSegs.stream()
                    .map(s -> Map.of("start", s.start(), "end", s.end()))
                    .toList();
        } catch (Exception e) {
            System.err.println("WebRTC VAD (vad4j) failed: " + e.getMessage());
            webrtcList = List.of();
        }

        sendJson(ex, 200, Map.of("webrtc", webrtcList, "silero", sileroList));
    }

    private static void handleAnnotations(HttpExchange ex, String filepath) throws IOException {
        if (ex.getRequestMethod().equalsIgnoreCase("GET")) {
            Map<String, Object> data = loadAnnotations();
            Object segs = data.getOrDefault(filepath, List.of());
            sendJson(ex, 200, segs);
        } else if (ex.getRequestMethod().equalsIgnoreCase("POST")) {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, Object> data = loadAnnotations();
            data.put(filepath, GSON.fromJson(body, List.class));
            Files.writeString(ANNOTATIONS_FILE, GSON.toJson(data));
            sendJson(ex, 200, Map.of("ok", true));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadAnnotations() throws IOException {
        if (Files.exists(ANNOTATIONS_FILE)) {
            return GSON.fromJson(Files.readString(ANNOTATIONS_FILE), Map.class);
        }
        return new HashMap<>();
    }

    private static byte[] loadAudioAsPcm(Path file, int targetSr) throws Exception {
        String ext = getExt(file.toString());
        if (ext.equals("mp3") || ext.equals("ogg") || ext.equals("flac")) {
            ProcessBuilder pb = new ProcessBuilder(
                    "ffmpeg", "-i", file.toString(), "-f", "s16le", "-ar", String.valueOf(targetSr),
                    "-ac", "1", "-acodec", "pcm_s16le", "-");
            pb.redirectErrorStream(false);
            Process proc = pb.start();
            byte[] pcm = proc.getInputStream().readAllBytes();
            proc.waitFor();
            return pcm;
        } else {
            AudioInputStream ais = AudioSystem.getAudioInputStream(file.toFile());
            AudioFormat srcFmt = ais.getFormat();
            if (srcFmt.getSampleRate() != targetSr || srcFmt.getChannels() != 1) {
                AudioFormat targetFmt = new AudioFormat(targetSr, 16, 1, true, false);
                ais = AudioSystem.getAudioInputStream(targetFmt, ais);
            }
            byte[] pcm = ais.readAllBytes();
            ais.close();
            return pcm;
        }
    }

    private static float[] loadAudioAsFloat(Path file, int targetSr) throws Exception {
        String ext = getExt(file.toString());
        byte[] pcmBytes;
        float sampleRate;

        if (ext.equals("mp3") || ext.equals("ogg") || ext.equals("flac")) {
            ProcessBuilder pb = new ProcessBuilder(
                    "ffmpeg", "-i", file.toString(), "-f", "s16le", "-ar", String.valueOf(targetSr),
                    "-ac", "1", "-acodec", "pcm_s16le", "-");
            pb.redirectErrorStream(false);
            Process proc = pb.start();
            pcmBytes = proc.getInputStream().readAllBytes();
            proc.waitFor();
            sampleRate = targetSr;
        } else {
            AudioInputStream ais = AudioSystem.getAudioInputStream(file.toFile());
            AudioFormat srcFmt = ais.getFormat();
            if (srcFmt.getSampleRate() != targetSr || srcFmt.getChannels() != 1) {
                AudioFormat targetFmt = new AudioFormat(targetSr, 16, 1, true, false);
                ais = AudioSystem.getAudioInputStream(targetFmt, ais);
            }
            pcmBytes = ais.readAllBytes();
            ais.close();
            sampleRate = targetSr;
        }

        float[] samples = new float[pcmBytes.length / 2];
        for (int i = 0; i < samples.length; i++) {
            short s = (short) ((pcmBytes[i * 2] & 0xff) | (pcmBytes[i * 2 + 1] << 8));
            samples[i] = s / 32768.0f;
        }
        return samples;
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> params = new HashMap<>();
        if (query == null || query.isBlank()) return params;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) params.put(decode(kv[0]), decode(kv[1]));
        }
        return params;
    }

    private static String getExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1).toLowerCase() : "";
    }

    private static void sendJson(HttpExchange ex, int code, Object data) throws IOException {
        byte[] body = GSON.toJson(data).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(code, body.length);
        ex.getResponseBody().write(body);
        ex.close();
    }

    private static void sendText(HttpExchange ex, int code, String text) throws IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain");
        ex.sendResponseHeaders(code, body.length);
        ex.getResponseBody().write(body);
        ex.close();
    }
}
