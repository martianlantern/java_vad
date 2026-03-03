const BASE = (typeof window !== "undefined" && window.BASE_PATH) || "";
const PX_PER_SEC = 80;
let duration = 0;
let gtSegments = [];
let webrtcSegments = [];
let sileroSegments = [];
let selectedGtIdx = -1;
let isPlaying = false;
let animFrame = null;
let addingSegment = false;
let dragState = null;

const audioEl = document.getElementById("audio-el");
const audioSelect = document.getElementById("audio-select");
const loadBtn = document.getElementById("load-btn");
const statusEl = document.getElementById("status");
const mainContent = document.getElementById("main-content");
const playBtn = document.getElementById("play-btn");
const seekBar = document.getElementById("seek-bar");
const timeDisplay = document.getElementById("time-display");
const gtTrack = document.getElementById("gt-track");
const webrtcTrack = document.getElementById("webrtc-track");
const sileroTrack = document.getElementById("silero-track");
const gtPlayhead = document.getElementById("gt-playhead");
const webrtcPlayhead = document.getElementById("webrtc-playhead");
const sileroPlayhead = document.getElementById("silero-playhead");
const addSegBtn = document.getElementById("add-seg-btn");
const clearGtBtn = document.getElementById("clear-gt-btn");
const saveGtBtn = document.getElementById("save-gt-btn");
const annoStatus = document.getElementById("anno-status");
const metricsGrid = document.getElementById("metrics-grid");
const gtScroll = document.getElementById("gt-scroll");
const webrtcScroll = document.getElementById("webrtc-scroll");
const sileroScroll = document.getElementById("silero-scroll");
const rerunBtn = document.getElementById("rerun-btn");
const rerunStatus = document.getElementById("rerun-status");
const uploadInput = document.getElementById("upload-input");
const gtStatsEl = document.getElementById("gt-stats");
const webrtcStatsEl = document.getElementById("webrtc-stats");
const sileroStatsEl = document.getElementById("silero-stats");

function fmt(s) {
  const m = Math.floor(s / 60);
  const sec = Math.floor(s % 60);
  return `${m}:${sec.toString().padStart(2, "0")}`;
}

async function loadAudioList() {
  const res = await fetch(`${BASE}/api/audios`);
  const files = await res.json();
  files.forEach(f => {
    const opt = document.createElement("option");
    opt.value = f.path;
    opt.textContent = `[${f.category}] ${f.name}`;
    audioSelect.appendChild(opt);
  });
}

audioSelect.addEventListener("change", () => {
  loadBtn.disabled = !audioSelect.value;
});

function buildVadQuery() {
  const p = new URLSearchParams();
  p.set("webrtc_vad_mode", document.getElementById("p-webrtc-mode").value);
  p.set("webrtc_threshold", document.getElementById("p-webrtc-thr").value);
  p.set("webrtc_frame_dur_ms", document.getElementById("p-webrtc-frame").value);
  p.set("webrtc_pad_ms", document.getElementById("p-webrtc-pad").value);
  p.set("webrtc_sample_rate", document.getElementById("p-webrtc-sr").value);
  p.set("silero_threshold", document.getElementById("p-silero-thr").value);
  p.set("silero_min_speech_ms", document.getElementById("p-silero-minspeech").value);
  p.set("silero_min_silence_ms", document.getElementById("p-silero-minsilence").value);
  p.set("silero_speech_pad_ms", document.getElementById("p-silero-pad").value);
  return p.toString();
}

async function runVads(filepath, statusTarget) {
  statusTarget.textContent = "Running VADs...";
  const vadRes = await fetch(`${BASE}/api/vad/${filepath}?${buildVadQuery()}`);
  const vad = await vadRes.json();
  webrtcSegments = vad.webrtc;
  sileroSegments = vad.silero;
  statusTarget.textContent = "Ready";
}

loadBtn.addEventListener("click", async () => {
  const filepath = audioSelect.value;
  if (!filepath) return;
  loadBtn.disabled = true;
  statusEl.textContent = "Loading audio...";

  audioEl.src = `${BASE}/api/audio/${filepath}`;
  await new Promise(r => { audioEl.oncanplaythrough = r; audioEl.load(); });
  duration = audioEl.duration;

  await runVads(filepath, statusEl);

  const gtRes = await fetch(`${BASE}/api/annotations/${filepath}`);
  gtSegments = await gtRes.json();
  if (!Array.isArray(gtSegments)) gtSegments = [];

  selectedGtIdx = -1;
  mainContent.style.display = "block";
  loadBtn.disabled = false;
  timeDisplay.textContent = `0:00 / ${fmt(duration)}`;

  setTrackWidths();
  renderAll();
  computeMetrics();
});

rerunBtn.addEventListener("click", async () => {
  const filepath = audioSelect.value;
  if (!filepath || !duration) return;
  rerunBtn.disabled = true;
  await runVads(filepath, rerunStatus);
  rerunBtn.disabled = false;
  renderSegments(webrtcTrack, webrtcSegments, "webrtc");
  renderSegments(sileroTrack, sileroSegments, "silero");
  computeMetrics();
});

function setTrackWidths() {
  const w = Math.max(duration * PX_PER_SEC, 600);
  [gtTrack, webrtcTrack, sileroTrack].forEach(t => t.style.width = w + "px");
}

function renderSegments(track, segments, cls) {
  track.querySelectorAll(".seg").forEach(e => e.remove());
  const tw = parseFloat(track.style.width);
  segments.forEach((seg, i) => {
    const el = document.createElement("div");
    el.className = `seg ${cls}`;
    el.style.left = (seg.start / duration * tw) + "px";
    el.style.width = Math.max(((seg.end - seg.start) / duration * tw), 3) + "px";
    if (cls === "gt") {
      if (i === selectedGtIdx) el.classList.add("selected");
      el.addEventListener("click", (e) => {
        e.stopPropagation();
        selectedGtIdx = i;
        renderGt();
      });
      const lh = document.createElement("div");
      lh.className = "seg-handle left";
      lh.addEventListener("mousedown", (e) => startDrag(e, i, "left"));
      el.appendChild(lh);
      const rh = document.createElement("div");
      rh.className = "seg-handle right";
      rh.addEventListener("mousedown", (e) => startDrag(e, i, "right"));
      el.appendChild(rh);
    } else {
      el.addEventListener("click", () => {
        audioEl.currentTime = seg.start;
        if (!isPlaying) togglePlay();
      });
    }
    track.appendChild(el);
  });
}

function renderGt() { renderSegments(gtTrack, gtSegments, "gt"); updateStats(); }
function renderAll() {
  renderGt();
  renderSegments(webrtcTrack, webrtcSegments, "webrtc");
  renderSegments(sileroTrack, sileroSegments, "silero");
  updateStats();
}

function togglePlay() {
  if (isPlaying) {
    audioEl.pause();
    isPlaying = false;
    playBtn.innerHTML = "&#9654;";
    cancelAnimationFrame(animFrame);
  } else {
    audioEl.play();
    isPlaying = true;
    playBtn.innerHTML = "&#9646;&#9646;";
    tickPlayhead();
  }
}

playBtn.addEventListener("click", togglePlay);

audioEl.addEventListener("ended", () => {
  isPlaying = false;
  playBtn.innerHTML = "&#9654;";
  cancelAnimationFrame(animFrame);
});

function tickPlayhead() {
  if (!isPlaying) return;
  const t = audioEl.currentTime;
  const tw = parseFloat(gtTrack.style.width) || 600;
  const px = t / duration * tw;
  gtPlayhead.style.left = px + "px";
  webrtcPlayhead.style.left = px + "px";
  sileroPlayhead.style.left = px + "px";

  seekBar.value = (t / duration * 1000) | 0;
  timeDisplay.textContent = `${fmt(t)} / ${fmt(duration)}`;

  const scrollContainers = [gtScroll, webrtcScroll, sileroScroll];
  scrollContainers.forEach(sc => {
    const vis = sc.clientWidth;
    if (px < sc.scrollLeft || px > sc.scrollLeft + vis) {
      sc.scrollLeft = px - vis * 0.1;
    }
  });

  animFrame = requestAnimationFrame(tickPlayhead);
}

seekBar.addEventListener("input", () => {
  audioEl.currentTime = (seekBar.value / 1000) * duration;
  const t = audioEl.currentTime;
  const tw = parseFloat(gtTrack.style.width) || 600;
  const px = t / duration * tw;
  gtPlayhead.style.left = px + "px";
  webrtcPlayhead.style.left = px + "px";
  sileroPlayhead.style.left = px + "px";
  timeDisplay.textContent = `${fmt(t)} / ${fmt(duration)}`;
});

[gtScroll, webrtcScroll, sileroScroll].forEach(sc => {
  sc.addEventListener("scroll", () => {
    const sl = sc.scrollLeft;
    [gtScroll, webrtcScroll, sileroScroll].forEach(other => {
      if (other !== sc) other.scrollLeft = sl;
    });
  });
});

function clickTimeOnTrack(e, track) {
  const tw = parseFloat(track.style.width);
  const scrollEl = track.parentElement;
  const containerRect = scrollEl.getBoundingClientRect();
  const x = e.clientX - containerRect.left + scrollEl.scrollLeft;
  return Math.max(0, Math.min(duration, (x / tw) * duration));
}

gtTrack.addEventListener("click", (e) => {
  const t = clickTimeOnTrack(e, gtTrack);
  if (addingSegment) {
    const halfLen = 1;
    const seg = { start: Math.max(0, t - halfLen), end: Math.min(duration, t + halfLen) };
    gtSegments.push(seg);
    gtSegments.sort((a, b) => a.start - b.start);
    selectedGtIdx = gtSegments.indexOf(seg);
    addingSegment = false;
    addSegBtn.style.background = "";
    renderGt();
    computeMetrics();
  } else {
    audioEl.currentTime = t;
    if (!isPlaying) togglePlay();
  }
});

addSegBtn.addEventListener("click", () => {
  addingSegment = !addingSegment;
  addSegBtn.style.background = addingSegment ? "rgba(138,92,245,0.3)" : "";
});

clearGtBtn.addEventListener("click", () => {
  gtSegments = [];
  selectedGtIdx = -1;
  renderGt();
  computeMetrics();
});

saveGtBtn.addEventListener("click", async () => {
  const filepath = audioSelect.value;
  if (!filepath) return;
  await fetch(`${BASE}/api/annotations/${filepath}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(gtSegments),
  });
  annoStatus.textContent = "Saved!";
  setTimeout(() => annoStatus.textContent = "", 2000);
});

document.addEventListener("keydown", (e) => {
  if (e.key === "Delete" || e.key === "Backspace") {
    if (selectedGtIdx >= 0 && selectedGtIdx < gtSegments.length) {
      gtSegments.splice(selectedGtIdx, 1);
      selectedGtIdx = -1;
      renderGt();
      computeMetrics();
    }
  }
  if (e.key === " " && e.target.tagName !== "INPUT" && e.target.tagName !== "SELECT") {
    e.preventDefault();
    togglePlay();
  }
});

function startDrag(e, idx, side) {
  e.stopPropagation();
  e.preventDefault();
  dragState = { idx, side, startX: e.clientX, origStart: gtSegments[idx].start, origEnd: gtSegments[idx].end };
  document.addEventListener("mousemove", onDrag);
  document.addEventListener("mouseup", endDrag);
}

function onDrag(e) {
  if (!dragState) return;
  const tw = parseFloat(gtTrack.style.width);
  const dx = e.clientX - dragState.startX;
  const dt = (dx / tw) * duration;
  const seg = gtSegments[dragState.idx];
  if (dragState.side === "left") {
    seg.start = Math.max(0, Math.min(dragState.origStart + dt, seg.end - 0.05));
  } else {
    seg.end = Math.min(duration, Math.max(dragState.origEnd + dt, seg.start + 0.05));
  }
  renderGt();
}

function endDrag() {
  dragState = null;
  document.removeEventListener("mousemove", onDrag);
  document.removeEventListener("mouseup", endDrag);
  computeMetrics();
}

function computeMetrics() {
  metricsGrid.innerHTML = "";
  if (!gtSegments.length) {
    metricsGrid.innerHTML = '<p class="no-gt-msg">Annotate ground truth segments to see metrics</p>';
    return;
  }

  const resolution = 0.01;
  const nBins = Math.ceil(duration / resolution);
  const gtBins = new Uint8Array(nBins);
  const webrtcBins = new Uint8Array(nBins);
  const sileroBins = new Uint8Array(nBins);

  const fillBins = (bins, segs) => {
    segs.forEach(s => {
      const si = Math.floor(s.start / resolution);
      const ei = Math.min(Math.ceil(s.end / resolution), nBins);
      for (let i = si; i < ei; i++) bins[i] = 1;
    });
  };

  fillBins(gtBins, gtSegments);
  fillBins(webrtcBins, webrtcSegments);
  fillBins(sileroBins, sileroSegments);

  const calcMetrics = (predBins, name) => {
    let tp = 0, fp = 0, fn = 0, tn = 0;
    for (let i = 0; i < nBins; i++) {
      const g = gtBins[i], p = predBins[i];
      if (g && p) tp++;
      else if (!g && p) fp++;
      else if (g && !p) fn++;
      else tn++;
    }
    const precision = tp + fp > 0 ? tp / (tp + fp) : 0;
    const recall = tp + fn > 0 ? tp / (tp + fn) : 0;
    const f1 = precision + recall > 0 ? 2 * precision * recall / (precision + recall) : 0;
    const accuracy = (tp + tn) / nBins;
    const far = fp + tn > 0 ? fp / (fp + tn) : 0;
    const missRate = tp + fn > 0 ? fn / (tp + fn) : 0;

    return { name, precision, recall, f1, accuracy, far, missRate, tp, fp, fn, tn };
  };

  const webrtcM = calcMetrics(webrtcBins, "WebRTC");
  const sileroM = calcMetrics(sileroBins, "Silero");

  [webrtcM, sileroM].forEach(m => {
    const card = (title, value, detail) => {
      const el = document.createElement("div");
      el.className = "metric-card";
      el.innerHTML = `<div class="metric-title">${m.name} — ${title}</div><div class="metric-value">${value}</div>${detail ? `<div class="metric-detail">${detail}</div>` : ""}`;
      metricsGrid.appendChild(el);
    };
    card("F1 Score", (m.f1 * 100).toFixed(1) + "%", "Harmonic mean of precision & recall");
    card("Accuracy", (m.accuracy * 100).toFixed(1) + "%", `TP=${m.tp} TN=${m.tn} FP=${m.fp} FN=${m.fn}`);
    card("Precision", (m.precision * 100).toFixed(1) + "%", "Of predicted speech, how much is correct");
    card("Recall", (m.recall * 100).toFixed(1) + "%", "Of actual speech, how much is detected");
    card("False Alarm", (m.far * 100).toFixed(1) + "%", "Non-speech predicted as speech");
    card("Miss Rate", (m.missRate * 100).toFixed(1) + "%", "Speech missed by detector");
  });
}

function segStats(segs) {
  if (!segs.length) return "";
  const totalLen = segs.reduce((s, seg) => s + (seg.end - seg.start), 0);
  const avg = totalLen / segs.length;
  return `${segs.length} segs, avg ${avg.toFixed(2)}s`;
}

function updateStats() {
  gtStatsEl.textContent = segStats(gtSegments);
  webrtcStatsEl.textContent = segStats(webrtcSegments);
  sileroStatsEl.textContent = segStats(sileroSegments);
}

uploadInput.addEventListener("change", async () => {
  const files = uploadInput.files;
  if (!files.length) return;
  statusEl.textContent = `Uploading ${files.length} file(s)...`;
  for (const file of files) {
    const form = new FormData();
    form.append("file", file);
    form.append("category", "uploads");
    await fetch(`${BASE}/api/upload`, { method: "POST", body: form });
  }
  uploadInput.value = "";
  const existingPaths = new Set([...audioSelect.options].map(o => o.value));
  const res = await fetch(`${BASE}/api/audios`);
  const allFiles = await res.json();
  allFiles.forEach(f => {
    if (!existingPaths.has(f.path)) {
      const opt = document.createElement("option");
      opt.value = f.path;
      opt.textContent = `[${f.category}] ${f.name}`;
      audioSelect.appendChild(opt);
    }
  });
  statusEl.textContent = `Uploaded ${files.length} file(s)`;
  setTimeout(() => statusEl.textContent = "", 3000);
});

loadAudioList();
