#!/usr/bin/env -S PYTHONUNBUFFERED=1 uv run --script
# /// script
# requires-python = ">=3.11"
# dependencies = [
#   "flask>=3.0",
#   "pydub>=0.25.1",
#   "webrtcvad-wheels",
#   "torch",
#   "numpy",
#   "torchaudio",
#   "packaging",
#   "fire>=0.7.1",
# ]
# ///
import json, collections, io
from pathlib import Path
from flask import Flask, Blueprint, send_from_directory, jsonify, request
from pydub import AudioSegment
import numpy as np, torch

DIR = Path(__file__).parent
AUDIOS = DIR / "audios"
ANNOTATIONS_FILE = DIR / "annotations.json"
CONFIG_FILE = DIR / "config.json"


def load_config():
    cfg = json.loads(CONFIG_FILE.read_text()) if CONFIG_FILE.exists() else {}
    base = (cfg.get("base_path") or "").strip().rstrip("/") or "/"
    return {"base_path": base if base != "/" else ""}


config = load_config()
BASE_PATH = config["base_path"]

app = Flask(__name__, static_folder=str(DIR / "static"))
bp = Blueprint("main", __name__, url_prefix=BASE_PATH)

silero_model, silero_utils = None, None

def get_silero():
    global silero_model, silero_utils
    if silero_model is None:
        silero_model, utils = torch.hub.load(repo_or_dir="snakers4/silero-vad", model="silero_vad", trust_repo=True)
        silero_utils = utils
    return silero_model, silero_utils


class Frame:
    __slots__ = ("bytes", "timestamp", "duration")
    def __init__(self, b, ts, d): self.bytes, self.timestamp, self.duration = b, ts, d


def _frames(pcm, sr, dur_ms):
    n = int(sr * dur_ms / 1000) * 2
    off, ts, d = 0, 0.0, dur_ms / 1000.0
    while off + n <= len(pcm):
        yield Frame(pcm[off:off+n], ts, d)
        ts += d; off += n


def _collect(sr, dur_ms, vad, frames, thr, pad_ms=300):
    pad = int(pad_ms / dur_ms)
    ring = collections.deque(maxlen=pad)
    triggered, voiced, start = False, [], None
    for f in frames:
        sp = vad.is_speech(f.bytes, sr)
        if not triggered:
            ring.append((f, sp))
            if sum(s for _, s in ring) > thr * ring.maxlen:
                triggered = True
                start = ring[0][0].timestamp
                voiced.extend(fr for fr, _ in ring)
                ring.clear()
        else:
            voiced.append(f)
            ring.append((f, sp))
            if sum(not s for _, s in ring) > thr * ring.maxlen:
                triggered = False
                yield start, f.timestamp + f.duration
                ring.clear(); voiced = []
    if voiced:
        yield start, voiced[-1].timestamp + voiced[-1].duration


def run_webrtc_vad(path, params=None):
    import webrtcvad
    params = params or dict(sample_rate=8000, channels=1, frame_bits=16, frame_dur_ms=20, vad_mode=0, threshold=0.6, pad_ms=300)
    audio = AudioSegment.from_file(path).set_channels(params["channels"]).set_frame_rate(params["sample_rate"]).set_sample_width(2)
    vad = webrtcvad.Vad(params["vad_mode"])
    sr, dur = params["sample_rate"], params["frame_dur_ms"]
    pad_ms = params.get("pad_ms", 300)
    return [{"start": round(s, 3), "end": round(e, 3)} for s, e in _collect(sr, dur, vad, list(_frames(audio.raw_data, sr, dur)), params["threshold"], pad_ms)]


def run_silero_vad(path, params=None):
    params = params or {}
    model, utils = get_silero()
    get_speech_timestamps = utils[0]
    audio = AudioSegment.from_file(path).set_channels(1).set_frame_rate(16000).set_sample_width(2)
    samples = np.frombuffer(audio.raw_data, dtype=np.int16).astype(np.float32) / 32768.0
    wav = torch.from_numpy(samples)
    stamps = get_speech_timestamps(
        wav, model, sampling_rate=16000, return_seconds=True,
        threshold=params.get("threshold", 0.5),
        min_speech_duration_ms=params.get("min_speech_duration_ms", 250),
        min_silence_duration_ms=params.get("min_silence_duration_ms", 100),
        speech_pad_ms=params.get("speech_pad_ms", 30),
    )
    return [{"start": round(s["start"], 3), "end": round(s["end"], 3)} for s in stamps]


def load_annotations():
    if ANNOTATIONS_FILE.exists():
        return json.loads(ANNOTATIONS_FILE.read_text())
    return {}


def save_annotations(data):
    ANNOTATIONS_FILE.write_text(json.dumps(data, indent=2))


@bp.route("/")
def index():
    html = (DIR / "static" / "index.html").read_text()
    return html.replace("__BASE_PATH__", BASE_PATH)


@bp.route("/static/<path:filename>")
def serve_static(filename):
    return send_from_directory(str(DIR / "static"), filename)


@bp.route("/api/audios")
def list_audios():
    files = []
    for subdir in sorted(AUDIOS.iterdir()):
        if subdir.is_dir():
            for f in sorted(subdir.iterdir()):
                if f.suffix in (".mp3", ".wav", ".ogg", ".flac"):
                    files.append({"name": f.name, "category": subdir.name, "path": f"{subdir.name}/{f.name}"})
    return jsonify(files)


@bp.route("/api/audio/<path:filepath>")
def serve_audio(filepath):
    return send_from_directory(str(AUDIOS), filepath)


@bp.route("/api/duration/<path:filepath>")
def get_duration(filepath):
    audio = AudioSegment.from_file(AUDIOS / filepath)
    return jsonify({"duration": len(audio) / 1000.0})


@bp.route("/api/vad/<path:filepath>")
def run_vad(filepath):
    path = AUDIOS / filepath
    webrtc_params = dict(
        sample_rate=int(request.args.get("webrtc_sample_rate", 8000)),
        channels=1, frame_bits=16,
        frame_dur_ms=int(request.args.get("webrtc_frame_dur_ms", 20)),
        vad_mode=int(request.args.get("webrtc_vad_mode", 0)),
        threshold=float(request.args.get("webrtc_threshold", 0.6)),
        pad_ms=int(request.args.get("webrtc_pad_ms", 300)),
    )
    silero_params = dict(
        threshold=float(request.args.get("silero_threshold", 0.5)),
        min_speech_duration_ms=int(request.args.get("silero_min_speech_ms", 250)),
        min_silence_duration_ms=int(request.args.get("silero_min_silence_ms", 100)),
        speech_pad_ms=int(request.args.get("silero_speech_pad_ms", 30)),
    )
    webrtc_segs = run_webrtc_vad(path, webrtc_params)
    silero_segs = run_silero_vad(path, silero_params)
    return jsonify({"webrtc": webrtc_segs, "silero": silero_segs})


@bp.route("/api/annotations/<path:filepath>", methods=["GET"])
def get_annotations(filepath):
    data = load_annotations()
    return jsonify(data.get(filepath, []))


@bp.route("/api/annotations/<path:filepath>", methods=["POST"])
def set_annotations(filepath):
    data = load_annotations()
    data[filepath] = request.json
    save_annotations(data)
    return jsonify({"ok": True})


app.register_blueprint(bp)


def main(port: int = 9596):
    app.run(host="0.0.0.0", port=port, debug=True)


if __name__ == "__main__":
    import fire
    fire.Fire(main)
