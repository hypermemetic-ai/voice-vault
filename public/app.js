(() => {
  "use strict";

  // Elements
  const mainBtn = document.getElementById("mainBtn");
  const recordSymbol = document.getElementById("recordSymbol");
  const stopSymbol = document.getElementById("stopSymbol");
  const timerEl = document.getElementById("timer");
  const gpuSpinner = document.getElementById("gpuSpinner");
  const gpuDot = document.getElementById("gpuDot");
  const resultSection = document.getElementById("resultSection");
  const resultBadge = document.getElementById("resultBadge");
  const transcriptText = document.getElementById("transcriptText");
  const manualCopyBtn = document.getElementById("manualCopyBtn");
  const expandTranscriptBtn = document.getElementById("expandTranscriptBtn");
  const audioPlayback = document.getElementById("audioPlayback");
  const gateSummary = document.getElementById("gateSummary");

  let mediaRecorder = null;
  let audioChunks = [];
  let recordingStartTime = 0;
  let timerInterval = null;
  let wakeLock = null;
  let isRecording = false;
  let isProcessing = false;

  function renderState() {
    if (isRecording) {
      // Big Red Stop Button with Large Square
      mainBtn.className = "btn-red";
      recordSymbol.style.display = "none";
      stopSymbol.style.display = "block";
      mainBtn.disabled = false;
    } else {
      // Subtle Dark Idle Button with Red Recording Circle
      mainBtn.className = "btn-idle";
      recordSymbol.style.display = "block";
      stopSymbol.style.display = "none";
      mainBtn.disabled = isProcessing;
    }

    if (isProcessing) {
      gpuSpinner.hidden = false;
      gpuDot.hidden = true;
    } else {
      gpuSpinner.hidden = true;
      gpuDot.hidden = false;
    }
  }

  function formatTimer(ms) {
    const totalSec = Math.floor(ms / 1000);
    const m = Math.floor(totalSec / 60).toString().padStart(2, "0");
    const s = (totalSec % 60).toString().padStart(2, "0");
    return `${m}:${s}`;
  }

  function startTimer() {
    clearInterval(timerInterval);
    timerEl.textContent = "00:00";
    timerInterval = setInterval(() => {
      const elapsed = Date.now() - recordingStartTime;
      timerEl.textContent = formatTimer(elapsed);
    }, 200);
  }

  function stopTimer() {
    clearInterval(timerInterval);
    timerInterval = null;
  }

  async function requestWakeLock() {
    if ("wakeLock" in navigator) {
      try {
        wakeLock = await navigator.wakeLock.request("screen");
      } catch (e) {}
    }
  }

  function releaseWakeLock() {
    if (wakeLock) {
      wakeLock.release().catch(() => {});
      wakeLock = null;
    }
  }

  async function copyToClipboard(text) {
    if (!text) return;
    try {
      await navigator.clipboard.writeText(text);
      if ("vibrate" in navigator) {
        navigator.vibrate([60, 40, 60]);
      }
      resultBadge.textContent = "COPIED TO CLIPBOARD";
    } catch (e) {
      transcriptText.select();
      document.execCommand("copy");
    }
  }

  async function startRecording() {
    if (isRecording || isProcessing) return;

    try {
      const stream = await navigator.mediaDevices.getUserMedia({
        audio: {
          channelCount: 1,
          echoCancellation: true,
          noiseSuppression: true,
          autoGainControl: true
        }
      });

      const mimeType = MediaRecorder.isTypeSupported("audio/webm;codecs=opus")
        ? "audio/webm;codecs=opus"
        : MediaRecorder.isTypeSupported("audio/mp4")
          ? "audio/mp4"
          : "";

      mediaRecorder = new MediaRecorder(stream, mimeType ? { mimeType } : {});
      audioChunks = [];

      mediaRecorder.ondataavailable = (e) => {
        if (e.data && e.data.size > 0) {
          audioChunks.push(e.data);
        }
      };

      mediaRecorder.start(250);
      recordingStartTime = Date.now();
      isRecording = true;
      await requestWakeLock();

      if ("vibrate" in navigator) {
        navigator.vibrate(50);
      }

      renderState();
      startTimer();

    } catch (err) {
      console.error("Mic error:", err);
      alert("Microphone error: " + err.message);
      isRecording = false;
      renderState();
    }
  }

  async function stopRecording() {
    if (!isRecording || !mediaRecorder) return;

    isRecording = false;
    isProcessing = true;
    renderState();
    stopTimer();
    releaseWakeLock();

    const durationMs = Date.now() - recordingStartTime;

    mediaRecorder.onstop = async () => {
      // Safety watchdog: up to 10 minutes for long audio files
      const watchdog = setTimeout(() => {
        if (isProcessing) {
          isProcessing = false;
          renderState();
        }
      }, 600000);

      try {
        mediaRecorder.stream.getTracks().forEach(t => t.stop());

        const mimeType = mediaRecorder.mimeType || "audio/webm";
        const audioBlob = new Blob(audioChunks, { type: mimeType });

        const formData = new FormData();
        const ext = mimeType.includes("mp4") ? "m4a" : "webm";
        formData.append("audio", audioBlob, `rec.${ext}`);
        formData.append("durationMs", String(durationMs));
        formData.append("device", "Phone Web");

        const res = await fetch("/api/transcribe", {
          method: "POST",
          body: formData,
          headers: { "X-Duration-Ms": String(durationMs) }
        });

        const data = await res.json();

        if (data && data.ok && data.rejected) {
          // The voiceprint gate dropped every segment: another speaker was
          // talking. Show the reason without copying anything to the clipboard.
          transcriptText.value = "";
          resultSection.hidden = false;
          setTranscriptExpanded(false);
          resultBadge.textContent = "OTHER SPEAKER REJECTED";
          resultBadge.classList.add("rejected");
          gateSummary.hidden = false;
          gateSummary.textContent = `Voice gate rejected ${data.gate?.rejected ?? 1} segment(s) · ${data.gate?.gateMs ?? 0}ms`;
          return;
        }

        if (data && data.ok && data.text && data.text.trim()) {
          const text = data.text.trim();
          resultBadge.classList.remove("rejected");
          transcriptText.value = text;
          resultSection.hidden = false;
          setTranscriptExpanded(false);
          audioPlayback.src = data.audioUrl || URL.createObjectURL(audioBlob);

          const gate = data.gate;
          if (gate && gate.enabled && gate.rejected > 0) {
            gateSummary.hidden = false;
            gateSummary.textContent = `Voice gate kept ${gate.kept}/${gate.segmentCount} segments · removed ${gate.rejected} from another speaker · ${gate.gateMs}ms`;
          } else {
            gateSummary.hidden = true;
          }

          await copyToClipboard(text);
        }
      } catch (err) {
        console.error("Transcription error:", err);
      } finally {
        clearTimeout(watchdog);
        isProcessing = false;
        renderState();
      }
    };

    mediaRecorder.stop();
  }

  // Consistent 1-2 button toggle:
  // Not recording -> Idle button starts recording
  // Recording -> Red button stops recording
  mainBtn.addEventListener("click", () => {
    if (!isRecording) {
      startRecording();
    } else {
      stopRecording();
    }
  });

  manualCopyBtn.addEventListener("click", () => {
    copyToClipboard(transcriptText.value);
    manualCopyBtn.textContent = "COPIED";
    setTimeout(() => manualCopyBtn.textContent = "COPY", 2000);
  });

  // Compact 3-line transcript preview by default; EXPAND reveals the full
  // scrollable text, COLLAPSE returns to the preview. The full text always
  // lives in transcriptText.value, so COPY works in both states.
  let transcriptExpanded = false;

  function setTranscriptExpanded(expanded) {
    transcriptExpanded = expanded;
    transcriptText.classList.toggle("expanded", expanded);
    expandTranscriptBtn.textContent = expanded ? "COLLAPSE" : "EXPAND";
    expandTranscriptBtn.setAttribute("aria-expanded", String(expanded));
    if (!expanded) transcriptText.scrollTop = 0;
  }

  expandTranscriptBtn.addEventListener("click", () => {
    setTranscriptExpanded(!transcriptExpanded);
  });

  // History Drawer
  const openHistoryBtn = document.getElementById("openHistoryBtn");
  const closeHistoryBtn = document.getElementById("closeHistoryBtn");
  const drawerScrim = document.getElementById("drawerScrim");
  const historyDrawer = document.getElementById("historyDrawer");
  const historyList = document.getElementById("historyList");

  function openDrawer() {
    drawerScrim.hidden = false;
    historyDrawer.classList.add("open");
    loadHistory();
  }

  function closeDrawer() {
    historyDrawer.classList.remove("open");
    setTimeout(() => {
      drawerScrim.hidden = true;
    }, 240);
  }

  openHistoryBtn.addEventListener("click", openDrawer);
  closeHistoryBtn.addEventListener("click", closeDrawer);
  drawerScrim.addEventListener("click", closeDrawer);

  async function loadHistory() {
    historyList.innerHTML = '<div style="color:#666; font-size:0.8rem; padding:12px 0;">Loading transcripts…</div>';
    try {
      const res = await fetch("/api/history?limit=50");
      const data = await res.json();
      if (!data || !data.ok || !data.recordings || data.recordings.length === 0) {
        historyList.innerHTML = '<div style="color:#555; font-size:0.8rem; padding:20px 0;">No transcripts recorded yet</div>';
        return;
      }

      // Filter out no_speech / empty transcripts
      const items = data.recordings.filter(r => r.transcript && r.transcript.trim());
      if (items.length === 0) {
        historyList.innerHTML = '<div style="color:#555; font-size:0.8rem; padding:20px 0;">No speech detected in recent recordings</div>';
        return;
      }

      // Group by date
      const groups = {};
      const today = new Date().toDateString();
      const yesterdayDate = new Date();
      yesterdayDate.setDate(yesterdayDate.getDate() - 1);
      const yesterday = yesterdayDate.toDateString();

      for (const item of items) {
        const d = new Date(item.created_at);
        const dString = d.toDateString();
        let key = d.toLocaleDateString(undefined, { month: "short", day: "numeric", year: "numeric" }).toUpperCase();
        if (dString === today) key = "TODAY";
        else if (dString === yesterday) key = "YESTERDAY";

        if (!groups[key]) groups[key] = [];
        groups[key].push(item);
      }

      historyList.innerHTML = "";
      for (const [groupName, recs] of Object.entries(groups)) {
        const header = document.createElement("div");
        header.className = "history-group-header";
        header.textContent = groupName;
        historyList.appendChild(header);

        for (const rec of recs) {
          const card = document.createElement("div");
          card.className = "history-card";

          const d = new Date(rec.created_at);
          const timeStr = d.toLocaleTimeString([], { hour: "numeric", minute: "2-digit" });
          const durSec = Math.round((rec.duration_ms || 0) / 1000);

          card.innerHTML = `
            <div class="history-card-header">
              <span class="history-card-time">${timeStr}</span>
              ${durSec > 0 ? `<span class="history-card-duration">· ${durSec}s</span>` : ""}
              <div class="history-card-spacer"></div>
              <span class="history-card-badge">COPY</span>
            </div>
            <div class="history-card-text">${escapeHtml(rec.transcript)}</div>
          `;

          card.addEventListener("click", async () => {
            await copyToClipboard(rec.transcript);
            card.classList.add("copied");
            const badge = card.querySelector(".history-card-badge");
            if (badge) badge.textContent = "✓ COPIED";
            setTimeout(() => {
              card.classList.remove("copied");
              if (badge) badge.textContent = "COPY";
            }, 1500);

            // Also load into transcript editor
            transcriptText.value = rec.transcript;
            resultSection.hidden = false;
            setTranscriptExpanded(false);
          });

          historyList.appendChild(card);
        }
      }
    } catch (err) {
      console.error("Error loading history:", err);
      historyList.innerHTML = '<div style="color:#ef4444; font-size:0.8rem; padding:12px 0;">Failed to load history</div>';
    }
  }

  function escapeHtml(str) {
    return str
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;")
      .replace(/'/g, "&#039;");
  }

  // ---------------------------------------------------------------------------
  // Voice enrollment ("Enroll Voice" flow)
  // ---------------------------------------------------------------------------

  const voiceProfileBtn = document.getElementById("voiceProfileBtn");
  const voiceChipText = document.getElementById("voiceChipText");
  const enrollScrim = document.getElementById("enrollScrim");
  const enrollModal = document.getElementById("enrollModal");
  const closeEnrollBtn = document.getElementById("closeEnrollBtn");
  const enrollSlots = document.getElementById("enrollSlots");
  const enrollLevel = document.getElementById("enrollLevel");
  const enrollTimer = document.getElementById("enrollTimer");
  const enrollRecordBtn = document.getElementById("enrollRecordBtn");
  const enrollSubmitBtn = document.getElementById("enrollSubmitBtn");
  const enrollTestBtn = document.getElementById("enrollTestBtn");
  const enrollResetBtn = document.getElementById("enrollResetBtn");
  const enrollStatus = document.getElementById("enrollStatus");
  const enrollResult = document.getElementById("enrollResult");

  const RECOMMENDED_CLIPS = 3;
  const MAX_CLIPS = 8;
  const MAX_CLIP_MS = 8000;

  const voice = {
    status: null,
    clips: [],
    recording: false,
    busy: false,
    recorder: null,
    stream: null,
    chunks: [],
    audioContext: null,
    analyser: null,
    meterFrame: null,
    timerInterval: null,
    startedAt: 0,
    purpose: "enroll",
  };

  function formatScore(value) {
    return Number.isFinite(value) ? value.toFixed(3) : "–";
  }

  function renderVoiceStatus() {
    const enrolled = Boolean(voice.status && voice.status.enrolled);
    voiceProfileBtn.classList.toggle("enrolled", enrolled);
    voiceChipText.textContent = enrolled ? "VOICE ON" : "VOICE";
    enrollResetBtn.hidden = !enrolled;
    enrollTestBtn.disabled = !enrolled || voice.recording || voice.busy;

    enrollSlots.innerHTML = "";
    for (let i = 0; i < Math.max(RECOMMENDED_CLIPS, voice.clips.length); i += 1) {
      const captured = Boolean(voice.clips[i]);
      const recording = voice.recording && i === voice.clips.length && voice.purpose === "enroll";
      const slot = document.createElement("div");
      slot.className = `enroll-slot${captured ? " captured" : ""}${recording ? " recording" : ""}`;
      const dot = document.createElement("span");
      dot.className = "enroll-slot-dot";
      const label = document.createElement("span");
      if (captured) {
        label.textContent = `Clip ${i + 1} captured · ${(voice.clips[i].durationMs / 1000).toFixed(1)}s`;
      } else if (recording) {
        label.textContent = `Recording clip ${i + 1}…`;
      } else {
        label.textContent = `Clip ${i + 1} · not recorded`;
      }
      slot.append(dot, label);
      enrollSlots.appendChild(slot);
    }

    enrollSubmitBtn.disabled = voice.busy || voice.recording || voice.clips.length === 0;
    enrollRecordBtn.disabled = voice.busy || voice.clips.length >= MAX_CLIPS;
    enrollRecordBtn.textContent = voice.recording
      ? "■ STOP CLIP"
      : voice.purpose === "test"
        ? "● RECORD TEST CLIP"
        : "● RECORD CLIP";

    if (voice.recording || voice.busy) return;
    if (enrolled) {
      const status = voice.status;
      enrollStatus.textContent =
        `Enrolled · ${status.sampleCount} clip(s) · ${status.modelId} (${status.dim}-d)\n` +
        `Threshold ${formatScore(status.threshold)} = μ ${formatScore(status.mu)} − 3σ ${formatScore(status.sigma)}` +
        `${status.backend ? ` · ${status.backend}` : ""}`;
    } else if (voice.status) {
      enrollStatus.textContent =
        "Not enrolled — the speaker gate is off and every voice is transcribed. " +
        `Record ${RECOMMENDED_CLIPS} clips of ~5s to activate it.`;
    }
  }

  async function refreshVoiceStatus() {
    try {
      const res = await fetch("/api/profile/status");
      const data = await res.json();
      if (data && data.ok) {
        voice.status = data;
        renderVoiceStatus();
      }
    } catch (err) {
      voiceChipText.textContent = "VOICE ?";
    }
  }

  function openEnrollModal() {
    enrollScrim.hidden = false;
    enrollModal.hidden = false;
    enrollResult.hidden = true;
    refreshVoiceStatus();
  }

  function closeEnrollModal() {
    if (voice.recording) stopEnrollClip();
    enrollScrim.hidden = true;
    enrollModal.hidden = true;
  }

  function startMeter(stream) {
    try {
      const AudioContextClass = window.AudioContext || window.webkitAudioContext;
      if (!AudioContextClass) return;
      voice.audioContext = new AudioContextClass();
      const source = voice.audioContext.createMediaStreamSource(stream);
      voice.analyser = voice.audioContext.createAnalyser();
      voice.analyser.fftSize = 512;
      source.connect(voice.analyser);
      const buffer = new Uint8Array(voice.analyser.frequencyBinCount);
      const tick = () => {
        if (!voice.analyser) return;
        voice.analyser.getByteFrequencyData(buffer);
        let sum = 0;
        for (const value of buffer) sum += value;
        const level = Math.min(100, (sum / buffer.length) * 1.8);
        enrollLevel.style.width = `${level.toFixed(0)}%`;
        voice.meterFrame = requestAnimationFrame(tick);
      };
      tick();
    } catch (err) {
      // Level meter is cosmetic only.
    }
  }

  function stopMeter() {
    if (voice.meterFrame) cancelAnimationFrame(voice.meterFrame);
    voice.meterFrame = null;
    voice.analyser = null;
    enrollLevel.style.width = "0%";
    if (voice.audioContext) {
      voice.audioContext.close().catch(() => {});
      voice.audioContext = null;
    }
  }

  async function startEnrollClip(purpose = "enroll") {
    if (voice.recording || voice.busy) return;
    voice.purpose = purpose;
    try {
      voice.stream = await navigator.mediaDevices.getUserMedia({
        audio: {
          channelCount: 1,
          echoCancellation: true,
          noiseSuppression: true,
          autoGainControl: true,
        },
      });
    } catch (err) {
      enrollResult.hidden = false;
      enrollResult.className = "enroll-result bad";
      enrollResult.textContent = `Microphone error: ${err.message}`;
      return;
    }

    const mimeType = MediaRecorder.isTypeSupported("audio/webm;codecs=opus")
      ? "audio/webm;codecs=opus"
      : MediaRecorder.isTypeSupported("audio/mp4")
        ? "audio/mp4"
        : "";

    voice.recorder = new MediaRecorder(voice.stream, mimeType ? { mimeType } : {});
    voice.chunks = [];
    voice.recorder.ondataavailable = (event) => {
      if (event.data && event.data.size > 0) voice.chunks.push(event.data);
    };
    voice.recorder.onstop = () => {
      const durationMs = Date.now() - voice.startedAt;
      const blob = new Blob(voice.chunks, { type: voice.recorder.mimeType || "audio/webm" });
      voice.stream.getTracks().forEach((track) => track.stop());
      voice.recording = false;
      stopMeter();
      clearInterval(voice.timerInterval);
      enrollTimer.textContent = "00:00";

      if (voice.purpose === "test") {
        submitVoiceTest(blob, durationMs);
      } else if (blob.size > 0) {
        voice.clips.push({ blob, durationMs });
        if (voice.clips.length >= MAX_CLIPS) {
          enrollResult.hidden = false;
          enrollResult.className = "enroll-result";
          enrollResult.textContent = `Maximum ${MAX_CLIPS} clips reached.`;
        }
      }
      renderVoiceStatus();
    };

    voice.recorder.start(250);
    voice.startedAt = Date.now();
    voice.recording = true;
    startMeter(voice.stream);
    voice.timerInterval = setInterval(() => {
      const elapsed = Date.now() - voice.startedAt;
      enrollTimer.textContent = formatTimer(elapsed);
      if (elapsed >= MAX_CLIP_MS) stopEnrollClip();
    }, 100);
    renderVoiceStatus();
  }

  function stopEnrollClip() {
    if (!voice.recording || !voice.recorder) return;
    try {
      voice.recorder.stop();
    } catch (err) {}
  }

  async function submitEnrollment() {
    if (voice.clips.length === 0 || voice.busy) return;
    voice.busy = true;
    enrollResult.hidden = false;
    enrollResult.className = "enroll-result";
    enrollResult.textContent = `Extracting voiceprint from ${voice.clips.length} clip(s)…`;
    renderVoiceStatus();

    try {
      const formData = new FormData();
      voice.clips.forEach((clip, index) => {
        const ext = clip.blob.type.includes("mp4") ? "m4a" : "webm";
        formData.append("sample", clip.blob, `enroll_${index + 1}.${ext}`);
      });
      const res = await fetch("/api/profile/enroll", { method: "POST", body: formData });
      const data = await res.json();
      if (!res.ok || !data.ok) throw new Error(data.error || `HTTP ${res.status}`);

      const calibration = data.calibration || {};
      enrollResult.className = "enroll-result good";
      enrollResult.textContent =
        `Voice profile enrolled.\n` +
        `Model: ${data.modelId} (${data.dim}-d, ${data.backend})\n` +
        `Clips: ${data.clipsUsed} · durations ${(data.durationsMs || []).map((ms) => `${(ms / 1000).toFixed(1)}s`).join(", ")}\n` +
        `Calibration: μ ${formatScore(data.mu)} · σ ${formatScore(data.sigma)} · ` +
        `threshold ${formatScore(data.threshold)} (μ − 3σ, ${calibration.pairCount ?? 0} pairs)\n` +
        `Other speakers are now rejected before transcription.`;
      voice.clips = [];
    } catch (err) {
      enrollResult.className = "enroll-result bad";
      enrollResult.textContent = `Enrollment failed: ${err.message}`;
    } finally {
      voice.busy = false;
      await refreshVoiceStatus();
      renderVoiceStatus();
    }
  }

  async function submitVoiceTest(blob, durationMs) {
    voice.busy = true;
    enrollResult.hidden = false;
    enrollResult.className = "enroll-result";
    enrollResult.textContent = "Scoring your voice against the enrolled gallery…";
    renderVoiceStatus();
    try {
      const formData = new FormData();
      const ext = blob.type.includes("mp4") ? "m4a" : "webm";
      formData.append("sample", blob, `verify.${ext}`);
      const res = await fetch("/api/profile/verify", { method: "POST", body: formData });
      const data = await res.json();
      if (!res.ok || !data.ok) throw new Error(data.error || `HTTP ${res.status}`);
      enrollResult.className = `enroll-result ${data.accepted ? "good" : "bad"}`;
      enrollResult.textContent =
        `${data.accepted ? "✓ That sounds like you" : "✕ Different voice detected"}\n` +
        `Score ${formatScore(data.score)} vs threshold ${formatScore(data.threshold)} ` +
        `(z ${formatScore(data.z)}, ${(data.durationMs / 1000).toFixed(1)}s)\n` +
        `${data.shortUtteranceRelaxed ? "Length-adaptive relaxation was applied for this short clip.\n" : ""}` +
        `Embedding took ${data.embedMs}ms`;
    } catch (err) {
      enrollResult.className = "enroll-result bad";
      enrollResult.textContent = `Verification failed: ${err.message}`;
    } finally {
      voice.busy = false;
      renderVoiceStatus();
    }
  }

  async function resetVoiceProfile() {
    voice.busy = true;
    renderVoiceStatus();
    try {
      await fetch("/api/profile", { method: "DELETE" });
      voice.clips = [];
      enrollResult.hidden = false;
      enrollResult.className = "enroll-result";
      enrollResult.textContent = "Voice profile removed — all speakers are transcribed again.";
    } finally {
      voice.busy = false;
      await refreshVoiceStatus();
      renderVoiceStatus();
    }
  }

  voiceProfileBtn.addEventListener("click", openEnrollModal);
  closeEnrollBtn.addEventListener("click", closeEnrollModal);
  enrollScrim.addEventListener("click", closeEnrollModal);
  enrollRecordBtn.addEventListener("click", () => {
    if (voice.recording) stopEnrollClip();
    else startEnrollClip("enroll");
  });
  enrollSubmitBtn.addEventListener("click", submitEnrollment);
  enrollTestBtn.addEventListener("click", () => {
    if (voice.busy || voice.recording) return;
    startEnrollClip("test");
  });
  enrollResetBtn.addEventListener("click", resetVoiceProfile);

  // Initial State
  renderState();
  refreshVoiceStatus();
  renderVoiceStatus();
})();
