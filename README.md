# java_vad

Silero VAD benchmarking, integration, and annotation toolkit for Java.

Compares Silero VAD (ONNX) against vad4j (kvad native) and provides drop-in integration snippets for migrating from vad4j to Silero VAD in production Java pipelines.

## Repository Structure

```
java_vad/
├── benchmark/           # Latency/CPU benchmarks (Silero VAD vs vad4j)
├── integration/         # Drop-in replacement classes for production
│   ├── SileroVAD.java                        # Replaces com.spr.vad.VAD
│   └── AudioStreamBoundaryDetectorSilero.java
├── annonator/           # Web UI for VAD annotation and comparison
│   ├── server.py        # Python backend (Flask + Silero + WebRTC VAD)
│   ├── java-backend/    # Java backend (ONNX Runtime + Silero VAD)
│   ├── static/          # Frontend (HTML/CSS/JS)
│   └── run.sh           # Launcher (reads config.json for backend choice)
├── snippet1.java        # Reference: existing vad4j VAD wrapper
├── snippet2.java        # Reference: existing AudioStreamBoundaryDetector
├── silero-vad/          # Git submodule: snakers4/silero-vad
├── vad4j/               # Git submodule: orctom/vad4j
└── docs/                # Benchmark results and documentation
```

## Quick Start

### Prerequisites

- Java 17+ (OpenJDK recommended)
- Maven 3.9+
- ffmpeg (for audio format conversion)
- Python 3.11+ with uv (for Python annotator backend only)
- Git (for submodule checkout)

### Clone

```bash
git clone --recurse-submodules https://github.com/martianlantern/java_vad.git
cd java_vad
```

If you already cloned without submodules:

```bash
git submodule update --init --recursive
```

### Audio Files

Audio files are not tracked in git (too large). Place your test audio files in:

```
audios/
├── voice/    # Audio files with speech
└── noise/    # Audio files with noise/silence
```

Supported formats: `.mp3`, `.wav`, `.ogg`, `.flac`

## Benchmark

Run the Silero VAD benchmark:

```bash
cd benchmark

# Convert audio to 16kHz WAV (one-time)
mkdir -p wav
for f in ../audios/voice/*.mp3; do
    ffmpeg -y -i "$f" -ar 16000 -ac 1 -sample_fmt s16 "wav/voice_$(basename "$f" .mp3).wav"
done
for f in ../audios/noise/*.mp3; do
    ffmpeg -y -i "$f" -ar 16000 -ac 1 -sample_fmt s16 "wav/noise_$(basename "$f" .mp3).wav"
done

# Build and run
mvn compile
java -cp "target/classes:$(mvn dependency:build-classpath -q -Dmdep.outputFile=/dev/stdout)" \
    benchmark.VadBenchmark ../silero-vad/src/silero_vad/data/silero_vad.onnx wav
```

See [docs/features/2026-03-02-silero-vad-benchmark-and-integration.md](docs/features/2026-03-02-silero-vad-benchmark-and-integration.md) for full results.

## VAD Annotator

Web UI for visualizing, comparing, and annotating VAD output.

### Setup on a New Machine (AMD/x86_64 CPU)

#### 1. Install system dependencies

Ubuntu/Debian:

```bash
sudo apt update
sudo apt install -y openjdk-21-jdk maven ffmpeg python3 python3-pip
# Install uv (Python script runner)
curl -LsSf https://astral.sh/uv/install.sh | sh
```

macOS:

```bash
brew install openjdk@21 maven ffmpeg
curl -LsSf https://astral.sh/uv/install.sh | sh
```

#### 2. Clone the repository

```bash
git clone --recurse-submodules https://github.com/martianlantern/java_vad.git
cd java_vad
```

#### 3. Place audio files

```bash
mkdir -p audios/voice audios/noise
# Copy your .mp3/.wav files into the appropriate subdirectories

# Symlink for annotator (if audios dir is at root)
cd annonator
ln -sf ../audios audios
cd ..
```

#### 4. Choose and configure backend

Edit `annonator/config.json`:

```json
{
  "base_path": "",
  "backend": "java"
}
```

Set `backend` to `"java"` or `"python"`.

- **java**: Uses ONNX Runtime for Silero VAD. Faster startup for VAD inference, no Python/PyTorch dependency. Does not include WebRTC VAD (Silero only).
- **python**: Uses PyTorch Silero VAD + WebRTC VAD. Both VAD engines shown in the UI. Requires Python 3.11+, uv, and ~2GB for PyTorch download on first run.

#### 5. Build the Java backend (if using Java)

```bash
cd annonator/java-backend
mvn package -q -DskipTests
cd ../..
```

#### 6. Run

```bash
cd annonator
./run.sh
# Or with custom port:
./run.sh 8080
```

Open the URL printed in the terminal (default: `http://localhost:9596`).

If you configured a `base_path`, the URL will be `http://localhost:9596/your/base/path`.

### Annotator Features

- Load audio files and run VAD with configurable parameters
- Three synchronized timelines: ground truth, WebRTC VAD, Silero VAD
- Click to add, drag to resize, Delete to remove ground truth segments
- Metrics: F1, accuracy, precision, recall, false alarm rate, miss rate
- Save/load annotations as JSON

### Backend Differences

| Feature | Python Backend | Java Backend |
|---------|---------------|--------------|
| Silero VAD | Yes (PyTorch) | Yes (ONNX Runtime) |
| WebRTC VAD | Yes | No |
| Dependencies | Python 3.11+, uv, PyTorch | Java 17+, Maven |
| First-run download | ~2GB (PyTorch + model) | ~20MB (ONNX Runtime Maven deps) |
| VAD latency | ~1-5ms/frame | ~0.12ms/frame |

## Integration

To replace vad4j with Silero VAD in your Java project:

1. Add the Maven dependency:

```xml
<dependency>
    <groupId>com.microsoft.onnxruntime</groupId>
    <artifactId>onnxruntime</artifactId>
    <version>1.23.1</version>
</dependency>
```

2. Copy `integration/SileroVAD.java` to replace `com.spr.vad.VAD`
3. Copy `integration/AudioStreamBoundaryDetectorSilero.java` for the boundary detector
4. Bundle `silero_vad.onnx` from `silero-vad/src/silero_vad/data/`
5. Replace `new VAD(...)` with `new SileroVAD(modelPath)`

## License

- Silero VAD: MIT License
- vad4j: Apache 2.0
- This repository: MIT License
