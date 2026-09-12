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
  const audioPlayback = document.getElementById("audioPlayback");

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

        if (data && data.ok && data.text && data.text.trim()) {
          const text = data.text.trim();
          transcriptText.value = text;
          resultSection.hidden = false;
          audioPlayback.src = data.audioUrl || URL.createObjectURL(audioBlob);

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

  // Initial State
  renderState();
})();
