# Silero VAD vs vad4j: Benchmark and Integration Guide

Date: 2026-03-02
Category: feature
Files Changed: benchmark/pom.xml, benchmark/src/main/java/benchmark/*.java, integration/SileroVAD.java, integration/AudioStreamBoundaryDetectorSilero.java

## Summary

Head-to-head benchmark of Silero VAD (ONNX) vs vad4j (kvad native) using identical audio files, same JDK, and same measurement methodology. Silero VAD is 2.4x faster per-inference on same-platform comparison, with tighter tail latencies and no native library deployment requirement.

## Context

Our system uses vad4j (JNA wrapper around native `kvad` library) for VAD. We evaluated Silero VAD (ONNX Runtime) as a replacement. Both were benchmarked with the same 8 audio files (~33 min total, 4 voice + 4 noise) converted to 16kHz mono WAV.

## Test Environment

- Machine: macOS Apple Silicon (aarch64) with Rosetta 2
- Audio: 8 files, ~33 min total, 16kHz mono PCM
- Measurement: per-frame `System.nanoTime()` around inference call, 200-frame warmup

### JDK Note

vad4j bundles `libkvad.dylib` compiled for x86_64 only. To run both benchmarks on the same JDK, we used Oracle JDK 21.0.6 (x86_64) under Rosetta 2. Silero VAD was additionally benchmarked on native aarch64 JDK (Homebrew OpenJDK 25) to show its native-platform performance.

## Head-to-Head Results (Same JDK: Oracle x86_64 under Rosetta)

### Per-Frame Latency

| Metric | vad4j (640B/20ms frames) | Silero VAD (512 samples/32ms frames) | Ratio |
|--------|--------------------------|--------------------------------------|-------|
| Mean | 466.8 us (0.467 ms) | 190.7 us (0.191 ms) | 2.4x faster |
| P50 | 468.4 us | 188.2 us | 2.5x faster |
| P95 | 525.8 us | 197.6 us | 2.7x faster |
| P99 | 538.0 us | 214.0 us | 2.5x faster |
| Min | 82.6 us | 167.0 us | -- |
| Max | 2699.7 us | 26653.2 us | -- |
| Std dev | 46.1 us | 144.8 us | -- |
| Throughput | 2,142 frames/sec | 5,244 frames/sec | 2.4x more |
| Real-time factor | 0.0233 (43x RT) | 0.0060 (168x RT) | 3.9x faster |

### CPU Utilization

| Metric | vad4j | Silero VAD |
|--------|-------|------------|
| Avg process CPU | 6.7% | 6.5% |
| Range | 3.2% - 13.2% | 3.8% - 8.2% |

CPU utilization is comparable. Silero has slightly more consistent CPU usage across files.

### Init / Load Time

| Metric | vad4j | Silero VAD |
|--------|-------|------------|
| Init time (x86_64 Rosetta) | 353 ms | 2,687 ms |
| Init time (aarch64 native) | N/A (no arm64 binary) | 836 ms |

Silero model load is a one-time startup cost. On native aarch64 it takes ~836ms.

### Speech Detection Rate

| Metric | vad4j | Silero VAD |
|--------|-------|------------|
| Overall speech rate | 67.8% | 64.4% |
| Threshold used | 0.6 | 0.5 |

Both detect similar speech proportions. The slight difference is expected given different models and thresholds.

## Per-File Breakdown (x86_64 JDK, same platform)

### vad4j

| File | Frames | Mean(us) | P50(us) | P95(us) | P99(us) | Speech% |
|------|--------|----------|---------|---------|---------|---------|
| noise_40172 | 9,706 | 460.5 | 459.3 | 528.2 | 554.7 | 64.5% |
| noise_40186 | 12,776 | 474.2 | 479.2 | 524.8 | 535.1 | 77.3% |
| noise_40194 | 13,342 | 462.6 | 462.3 | 521.8 | 532.8 | 69.1% |
| noise_40205 | 9,315 | 457.3 | 452.6 | 518.8 | 530.5 | 58.5% |
| voice_40169 | 16,122 | 468.0 | 470.3 | 527.4 | 538.7 | 70.7% |
| voice_40174 | 12,422 | 474.7 | 478.3 | 529.0 | 540.5 | 71.7% |
| voice_40192 | 12,697 | 466.0 | 463.2 | 526.7 | 539.3 | 69.3% |
| voice_40204 | 13,353 | 467.3 | 468.2 | 526.0 | 536.5 | 57.5% |

### Silero VAD (x86_64 JDK, Rosetta)

| File | Frames | Mean(us) | P50(us) | P95(us) | P99(us) | Speech% |
|------|--------|----------|---------|---------|---------|---------|
| noise_40172 | 6,066 | 205.8 | 190.6 | 213.5 | 568.8 | 56.9% |
| noise_40186 | 7,985 | 189.5 | 189.1 | 196.0 | 206.2 | 74.5% |
| noise_40194 | 8,339 | 189.6 | 190.7 | 197.4 | 207.2 | 63.0% |
| noise_40205 | 5,822 | 189.9 | 190.6 | 197.8 | 210.6 | 57.8% |
| voice_40169 | 10,076 | 191.8 | 188.8 | 196.4 | 210.5 | 67.9% |
| voice_40174 | 7,763 | 187.0 | 187.8 | 194.2 | 206.4 | 70.0% |
| voice_40192 | 7,936 | 186.3 | 187.4 | 193.9 | 204.5 | 63.8% |
| voice_40204 | 8,346 | 188.7 | 187.0 | 193.2 | 203.9 | 57.1% |

### Silero VAD (aarch64 native JDK - production target)

| File | Frames | Mean(us) | P50(us) | P95(us) | P99(us) | Speech% |
|------|--------|----------|---------|---------|---------|---------|
| noise_40172 | 6,066 | 122.7 | 118.1 | 144.0 | 163.3 | 56.9% |
| noise_40186 | 7,985 | 120.8 | 116.1 | 139.4 | 153.1 | 74.5% |
| noise_40194 | 8,339 | 120.3 | 116.0 | 139.0 | 151.8 | 63.0% |
| noise_40205 | 5,822 | 120.0 | 116.0 | 139.4 | 153.6 | 57.8% |
| voice_40169 | 10,076 | 119.9 | 115.8 | 138.9 | 152.3 | 67.9% |
| voice_40174 | 7,763 | 120.3 | 116.0 | 139.3 | 152.3 | 70.0% |
| voice_40192 | 7,936 | 120.3 | 116.4 | 139.1 | 152.3 | 63.8% |
| voice_40204 | 8,346 | 121.2 | 116.9 | 140.0 | 152.0 | 57.1% |

## Latency Distributions

### vad4j (x86_64)

```
  200-500 us: ######################################## 72,193  (72.4%)
  500-1000 us: ###############                          27,475  (27.5%)
  1000+ us:                                                 31  (<0.1%)
```

### Silero VAD (x86_64, same JDK)

```
  150-200 us: ######################################## 60,097  (96.4%)
  200-300 us: #                                         2,040  (3.3%)
  300+ us:                                                196  (0.3%)
```

### Silero VAD (aarch64 native)

```
  100-150 us: ######################################## 61,307  (98.4%)
  150-200 us: #                                           969  (1.6%)
  200+ us:                                                 57  (<0.1%)
```

## Summary Comparison Table

| Dimension | vad4j | Silero VAD |
|-----------|-------|------------|
| Backend | Native C (kvad) via JNA | ONNX Runtime |
| Model file | libkvad.dylib/so (platform-specific) | silero_vad.onnx (2.2MB, portable) |
| Frame size | 640 bytes (320 samples, 20ms) | 1024 bytes (512 samples, 32ms) |
| Mean latency (same JDK) | 466.8 us | 190.7 us |
| P99 latency (same JDK) | 538.0 us | 214.0 us |
| Mean latency (native platform) | N/A | 120.6 us |
| CPU usage | ~6.7% | ~6.5% |
| Throughput | 2,142 frames/sec | 5,244 frames/sec (same JDK), 8,291 (native) |
| Real-time factor | 43x RT | 168x RT (same JDK), 265x RT (native) |
| Init time | 353 ms | 836 ms (native) |
| Dependencies | JNA + platform-specific native binary | onnxruntime Maven JAR |
| Platform support | x86_64 only (no arm64 binary available) | All platforms via ONNX Runtime |
| Threshold | 0.6 fixed | Configurable (start + end thresholds) |
| Thread safety | Not thread-safe (per-instance Pointer) | Not thread-safe (per-instance state) |

## Architecture

```
vad4j (current):
+------------+     +------+     +-------------+
| byte[] PCM | --> | JNA  | --> | libkvad.so  |
| 640 bytes  |     | call |     | native C    |
| (20ms)     |     |      |     | x86_64 only |
+------------+     +------+     +-------------+
                                      |
                                 float prob (~467 us)

Silero VAD (proposed):
+------------+     +------------+     +------------------+
| byte[] PCM | --> | byte->float| --> | ONNX Runtime     |
| 1024 bytes |     | normalize  |     | silero_vad.onnx  |
| (32ms)     |     | + context  |     | cross-platform   |
+------------+     +------------+     +------------------+
                                            |
                                       float prob (~121 us native, ~191 us Rosetta)
```

## Integration Snippets

### SileroVAD.java

Drop-in replacement for `com.spr.vad.VAD`:
- Same API: `speechProbability(byte[])`, `isSpeech(byte[])`, `isSilent(byte[])`, `close()`
- Handles byte-to-float conversion internally
- Manages ONNX model state (context + hidden state)

See [integration/SileroVAD.java](../../integration/SileroVAD.java)

### AudioStreamBoundaryDetectorSilero.java

Modified `AudioStreamBoundaryDetector` that:
- Uses `SileroVAD` instead of `VAD`
- Adds accumulation buffer to bridge 20ms input to 32ms ONNX window
- Preserves all existing boundary detection logic

See [integration/AudioStreamBoundaryDetectorSilero.java](../../integration/AudioStreamBoundaryDetectorSilero.java)

## Migration Steps

1. Add Maven dependency:

```xml
<dependency>
    <groupId>com.microsoft.onnxruntime</groupId>
    <artifactId>onnxruntime</artifactId>
    <version>1.23.1</version>
</dependency>
```

2. Bundle `silero_vad.onnx` (2.2MB) as a resource
3. Replace `new VAD(...)` with `new SileroVAD(onnxModelPath)`
4. Remove JNA and libkvad dependencies

## Replication Steps

1. Prerequisites: Java 17+, Maven 3.9+, ffmpeg

2. Convert test audio:

```bash
cd benchmark
for f in ../audios/voice/*.mp3; do
    ffmpeg -y -i "$f" -ar 16000 -ac 1 -sample_fmt s16 "wav/voice_$(basename "$f" .mp3).wav"
done
for f in ../audios/noise/*.mp3; do
    ffmpeg -y -i "$f" -ar 16000 -ac 1 -sample_fmt s16 "wav/noise_$(basename "$f" .mp3).wav"
done
```

3. Build and install vad4j locally:

```bash
cd vad4j && mvn install -DskipTests
```

4. Run Silero benchmark (native aarch64):

```bash
cd benchmark && mvn compile
java -cp "target/classes:$(mvn dependency:build-classpath -q -Dmdep.outputFile=/dev/stdout)" \
    benchmark.VadBenchmark ../silero-vad/src/silero_vad/data/silero_vad.onnx wav
```

5. Run vad4j benchmark (requires x86_64 JDK for libkvad):

```bash
JDK_X86="path/to/x86_64/jdk/bin/java"
$JDK_X86 -Djna.library.path="$(pwd)" -cp "$CP" benchmark.Vad4jBenchmark wav vad4j_results.txt
```

6. Run fair comparison (Silero on same x86_64 JDK):

```bash
$JDK_X86 -cp "$CP" benchmark.VadBenchmark ../silero-vad/src/silero_vad/data/silero_vad.onnx wav
```
