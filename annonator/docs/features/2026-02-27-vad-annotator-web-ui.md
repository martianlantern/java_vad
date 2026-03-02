# VAD Annotator Web UI

Date: 2026-02-27
Category: feature
Files Changed: server.py, static/index.html, static/style.css, static/app.js

## Summary

Built a web application for comparing WebRTC and Silero Voice Activity Detection (VAD) results against manually annotated ground truth. The app runs both VAD engines on selected audio files and displays the speech segments on synchronized, scrollable timelines with accuracy metrics.

## Context

Need to visually compare how accurately WebRTC VAD and Silero VAD detect speech segments in audio files. Existing code (`vad_example.py`) runs both VADs but only for transcription WER comparison. This tool adds visual annotation and frame-level correctness metrics.

## Changes

### server.py

Flask backend that:
- Serves audio files from the `audios/` directory (noise + voice subdirectories)
- Runs WebRTC VAD (8kHz, mode 0, threshold 0.6) and Silero VAD (16kHz) on demand
- Returns speech segment timestamps as JSON `{start, end}` arrays
- Persists ground truth annotations to `annotations.json`

### static/index.html

Single-page app with:
- Audio file selector dropdown
- Audio player with play/pause, seek bar, time display
- Three timeline rows (ground truth, webrtc, silero)
- Annotation toolbar (add segment, clear, save)
- Metrics panel

### static/style.css

Dark theme matching the user's site (`#161616` background, `#c9d1d9` text, `#8A5CF5` accent). Scrollable timeline tracks with rounded segment blocks.

### static/app.js

Client-side logic for:
- Loading audio and VAD results via API
- Rendering segments on canvas-like div timelines (80px per second)
- Synchronized horizontal scrolling across all 3 timelines
- Manual annotation: click-to-add segments, drag handles to resize, delete/backspace to remove
- Frame-level metrics: F1, accuracy, precision, recall, false alarm rate, miss rate (10ms resolution)

## Implementation Details

```
+------------------+     +------------------+
|   Browser UI     | --> |   Flask Server   |
|  (HTML/CSS/JS)   |     |   (server.py)    |
+------------------+     +------------------+
        |                        |
        v                        v
  Audio playback          WebRTC VAD (8kHz)
  Timeline render         Silero VAD (16kHz)
  GT annotation           Audio file serving
  Metrics compute         Annotation storage
```

1. User selects audio file from dropdown
2. Backend runs both VADs, returns `{webrtc: [{start, end}], silero: [{start, end}]}`
3. Frontend renders segments as positioned divs on timeline tracks
4. User manually annotates ground truth by adding/resizing/deleting segments
5. Metrics computed client-side at 10ms resolution using binary classification

Metrics computation: audio duration is divided into 10ms bins. Each bin is classified as speech/non-speech by GT and each VAD. Standard binary classification metrics (TP, FP, FN, TN) are computed.

## Dependencies

- Flask (web server)
- pydub (audio processing)
- webrtcvad-wheels (WebRTC VAD)
- torch, torchaudio (Silero VAD)
- numpy
- fire (CLI)

## Verification

1. Start server: `./server.py`
2. Open http://localhost:8080
3. Select an audio file and click "Load & Run VADs"
4. Verify WebRTC and Silero timelines show blue segments
5. Click "+ Add Segment" then click on ground truth timeline to add annotation
6. Drag segment handles to adjust boundaries
7. Verify metrics panel updates with F1, accuracy, precision, recall
8. Click "Save GT" and reload page to verify persistence

## Replication Steps

1. Ensure audio files exist in `audios/noise/` and `audios/voice/`
2. Edit `config.json` to set `base_path` (e.g. `"/martian/t4/visualize"` for ingress routing)
3. Run `chmod +x server.py && ./server.py` (default port 9596)
4. Open browser to `http://localhost:9596{base_path}/` (e.g. `http://localhost:9596/martian/t4/visualize/`)
5. Silero model downloads on first VAD request (~10s)
6. Annotations saved to `annotations.json` in project root
