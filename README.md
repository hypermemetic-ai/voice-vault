# Voice Vault

Ultra-reliable, local-first asynchronous voice recorder and dictation tool for Android (Google Pixel 10) and Web, transcribing through Whisper Large v3 Turbo on local GPU and automatically copying text straight to the clipboard.

---

## Features

- **0ms Headless Quick Tap Dictation**: Double-tap the back of your Pixel to start dictating without leaving your active app. Double-tap again to stop, transcribe on GPU, and copy directly to the system clipboard.
- **Two-Part Speaker Rejection**: Pixel `VOICE_RECOGNITION` beamforming stops room audio at the source, and a server-side ONNX speaker-voiceprint gate drops any non-user speech segments before they reach Whisper.
- **Voice Enrollment & Self-Calibration**: Record three ~5 s reference clips; the server extracts normalized 192-d/512-d CAM++ embeddings, self-calibrates an acceptance threshold (`μ − 3σ` of intra-speaker similarity) and stores the gallery in SQLite. No reference audio is ever persisted.
- **Length-Adaptive Scoring**: Short utterances (`"yes"`, `"okay"`) are scored with a relaxed threshold so brief words are not falsely rejected, while a different voice still fails by a wide margin.
- **Dynamic Floating Status Overlay**: Compact, semi-transparent top status rectangle displaying live recording duration, transcription state (`Processing`), and completion status (`Copied`), positioned clear of the front camera punch-hole.
- **Recording recovery**: Failed/interrupted dictations remain in History with their original capture time. Play or export local audio offline, or explicitly retry transcription. Surviving unlisted `dictation_*.m4a`/`.mp4` files are adopted as recovered audio with previous outcome unknown; enrollment/unrelated files are excluded. Unresolved audio is protected from normal retention. Missing/invalid audio stays visible with unavailable actions.
- **Manual retry ownership**: Recovery belongs to the service, survives Activity recreation, and cannot start while dictation or another retry owns the workflow. Canceling transcription retains finalized audio. Recovery saves text in the same History item and never copies, inserts or auto-sends it; use explicit Copy.
- **Original audio export and deletion**: Export grants read access only to the selected recording URI, initiated by the user. Confirmed deletion suppresses stale History rediscovery and removes selected owned copies. If server cleanup fails, History shows Retry deletion. A known server copy can be retrieved when the local original is unavailable. The current transcript limit applies to resolved items; failure cards remain discoverable.
- **Slide-Out History Drawer**: Access the last 50 transcriptions and cached recordings grouped by date, with 1-tap copy to clipboard and duration metrics.
- **Silent Feedback**: Haptics and the floating pill only; Voice Vault plays no sounds.
- **Physical Side-Button Controls**: Taps act on release; holds (0.5 s) act while pressed, with a haptic. In Dictation Mode, tap Volume Up to start/stop recording and tap Volume Down to finish, add the text to the focused composer and send it when Auto-send is on. Hold Volume Up toggles Dictation Mode (with the mode off, a Volume Up tap still raises the volume and shows the slider; Volume Down is untouched). Hold Volume Down toggles Auto-send; the pill always shows `AUTO-SEND` or `NO SEND` while Dictation Mode is on, and the dashboard checkbox shows the same setting.
- **Composer Auto-send (any app; tuned for T3 Code)**: Adds the transcript to whatever is already in the focused input (never replaces the draft), then clicks that composer's own submit control once — matched by accessible name (`Send`, `Submit message`, `Queue message`, `Steer message`, …) inside the composer, without app-specific view IDs or screen positions. Sends as soon as the control is enabled; allows up to five seconds after confirmed insertion for Stop to become Send. Reports `Sent ✓` / `Queued ✓` when the composer clears, or says why it didn't send.
- **Floating Screen Edge Bubble**: Draggable on-screen toggle button for devices without rear-tap gestures.
- **Bounded Long-Recording Backend**: Local Node.js server with SQLite storage, VAD silence trimming and anti-hallucination sanitization. Recordings longer than one piece are cut at pauses into bounded 45 s pieces and joined into one transcript, so the configured model never receives a whole long recording.
- **Four-Tier Backend Cascade**: Warm daemon → RTX A2000 → Radeon 780M iGPU → CPU. A wedged daemon is capped at 15 s and rejected on any non-200 or `{ ok: false }` reply, so a constrained GPU never leaves the client stuck on "Processing...".
- **Web Dashboard & PWA**: Clean pitch-black web interface with URW Gothic typography, real-time timer, and direct APK download.

---

## Project Structure

```
├── android/                         # Native Android Application
│   ├── AndroidManifest.xml          # App manifest with headless quick-tap target
│   ├── build-apk.sh                 # Zero-dependency build script (aapt2, d8, apksigner)
│   ├── res/                         # Android drawables, layouts, fonts, XML configs
│   └── src/ai/hypermemetic/voicevault/
│       ├── FloatingBubbleService.java    # Draggable screen-edge bubble
│       ├── FloatingPillOverlay.java      # Top rectangular floating status overlay
│       ├── HistoryManager.java           # Local history caching & pruning (50 transcripts)
│       ├── MainActivity.java             # Main dashboard UI & Quick Tap gesture routing
│       ├── ToggleDictationActivity.java  # 0ms headless transparent trigger
│       ├── VoiceEnrollActivity.java      # Native "Enroll Voice" flow (3 clips + test)
│       ├── VoiceVaultApi.java            # Backend endpoints + multipart upload helper
│       ├── VoiceVaultKeyService.java     # Volume-key dictation and auto-send accessibility service
│       ├── VoiceVaultService.java        # Core background recording & transcription service
│       └── VoiceVaultTileService.java    # Quick Settings drop-down tile
├── python/                          # ONNX speaker-embedding sidecar
│   ├── speaker_worker.py            # Persistent JSON-lines worker (onnxruntime)
│   ├── fbank_numpy.py               # Kaldi-compatible 80-dim log-mel filterbank
│   └── validate_fbank.py            # Cross-validation against kaldi_native_fbank
├── models/                          # Downloaded ONNX models (gitignored, ~30 MB each)
├── public/                          # Web UI / PWA & Downloadable APK
│   ├── index.html                   # Web dashboard + voice enrollment modal
│   ├── style.css                    # Pitch-black minimal styling
│   ├── app.js                       # Audio recording, enrollment & history controller
│   └── icon.svg                     # Vector app icon
├── scripts/                         # Setup + verification tooling
│   ├── fetch-speaker-model.mjs      # Download/verify ONNX speaker models (sha256)
│   ├── setup-speaker-runtime.sh     # Create the onnxruntime Python venv
│   └── verify-ticket.mjs            # End-to-end verification of the pipeline
├── src/                             # Backend Server
│   ├── db.mjs                       # SQLite WAL storage (history + voice profile)
│   ├── server.mjs                   # HTTP / REST audio upload & transcription server
│   ├── transcriber.mjs              # Whisper Turbo daemon client & VAD pipeline
│   ├── vad.mjs                      # Multi-segment voice activity detection
│   ├── speaker.mjs                  # ONNX embedder, DSP fallback, calibration, decisions
│   ├── profile.mjs                  # Enrollment, persistence and profile compatibility
│   ├── gate.mjs                     # Target-speaker segment filtering
│   └── wav.mjs                      # PCM16/WAV helpers
├── test/                            # node:test suite (unit + integration)
└── package.json
```

---

## Two-Part Speaker Rejection Pipeline

Dictating in a room with other people used to merge their words into your transcript.
Two independent layers now prevent that:

### 1. Client / hardware: Pixel beamforming

`VoiceVaultService` records with `MediaRecorder.AudioSource.VOICE_RECOGNITION` instead
of `MIC`. On Pixel hardware this selects the multi-microphone beamforming /
target-voice capture path tuned for dictation, so the device steers a beam at the
talker in front of it rather than capturing the whole room. `VoiceEnrollActivity`
uses the same audio source so the enrolled voiceprint matches dictation conditions.

### 2. Server / acoustic: ONNX voiceprint gate

```
audio ──► ffmpeg 16k mono ──► VAD segmentation ──► CAM++ embedding per segment
                                                          │
                        enrolled gallery ◄── cosine similarity ──► length-adaptive
                                                          │            decision
                                              accepted segments only
                                                          ▼
                                                      Whisper
```

- **Embeddings**: 3D-Speaker CAM++ / WeSpeaker ONNX models run through
  `onnxruntime` in a persistent Python sidecar (`python/speaker_worker.py`).
  Embedding dimension (192-d or 512-d) is read from the model metadata. Feature
  extraction is Kaldi-compatible 80-dim log-mel fbank; `python/fbank_numpy.py`
  is validated against `kaldi_native_fbank` to < 1e-3 (`python/validate_fbank.py`).
- **Enrollment**: `POST /api/profile/enroll` accepts 3+ clips (~5 s each),
  extracts one normalized embedding per clip, and self-calibrates the acceptance
  threshold from the intra-speaker pairwise cosine similarities:
  `threshold = μ − 3σ` (with a `σ` floor of 0.05 so a handful of near-identical
  clips cannot produce an unrealistically strict threshold).
- **Storage**: gallery vectors are stored as float32 BLOBs in SQLite
  (`voice_profile`, `voice_gallery`); the reference audio itself is discarded.
- **Gating**: `src/gate.mjs` embeds every VAD segment, scores it by maximum
  cosine similarity against the gallery, and keeps only segments above the
  effective threshold. Rejected audio never reaches Whisper.
- **Length-adaptive scoring**: the effective threshold relaxes linearly from the
  calibrated value at 3 s down to `threshold − 3σ` at zero duration, so brief
  words ("yes", "okay") survive while another voice (typically 0.1–0.3 cosine)
  still fails by a wide margin. A hard `minAbsoluteScore` floor applies.
- **Graceful fallback**: with no profile enrolled, an incompatible model/gallery,
  or a missing ONNX runtime, transcription proceeds ungated — never a regression.
  A dependency-free log-mel DSP embedder (192-d) keeps enrollment usable on bare
  installs, and profiles are only compared against embeddings from the same
  backend/model.

### 3. UI

The web dashboard has a **VOICE** chip in the top bar that opens the enrollment
modal: record three clips with a live level meter, submit, and see the resulting
model, `μ`, `σ` and threshold. "Test my voice" scores a fresh clip against the
gallery, and "Remove profile" disables the gate. The Android dashboard shows the
same state on its Voice Gate row (`On · 3 clips · θ 0.74`) and opens the native
enrollment screen.

---

## Whisper Backend Cascade

Transcription walks a four-tier cascade and stops at the first backend that
answers, so a wedged daemon or a GPU that cannot allocate VRAM never leaves the
Android app or Web UI stuck on "Processing...":

1. **Warm daemon** over `WHISPER_SOCKET` (RTX A2000), hard-capped at **15 s** per request.
2. **Direct `handy`** on the RTX A2000 (`--device-index 1`).
3. **Direct `handy`** on the Radeon 780M iGPU (`--device-index 0`).
4. **Direct `handy`** on the CPU (`--device-index 2`).

A daemon reply is accepted only when it is HTTP 200 with `ok !== false`. Any
transport error, timeout, broken pipe, non-200 status, malformed JSON or
explicit `{ ok: false }` payload rejects that tier immediately and falls through
to the next one. `POST /api/transcribe` reports the winning `backend` plus the
per-tier `backendAttempts` history, and a hung `handy` process is killed at
`HANDY_TIMEOUT_MS` instead of blocking the request forever.

### Long recordings

Speech audio longer than one piece is cut at the quietest pause between 20 and
40 s from the piece start and never later than 45 s. A window without a pause
cuts at 45 s and starts the next piece 1.5 s earlier; words repeated across that
forced seam are removed when the pieces join. A pause seam is never
deduplicated. Each piece runs through the cascade on its own, so a fallback only
repeats the failed piece, and the joined text is cleaned once. Recordings that
fit in one piece stay a single request.

The warm daemon refuses WAV input longer than `WHISPER_MAX_AUDIO_MS` (default
120 s) with HTTP 413, so an oversized request cannot crash the model process.

---

## Audio Storage

Accepted uploads are written with a durable protocol (`<id><ext>.part` → fsync →
rename → directory fsync) and transcribed from that copy. By default it lands in
the raw archive (`VOICE_VAULT_STORAGE`), so the request waits for the archive
volume. When `VOICE_VAULT_STAGING_DIR` points at fast local storage, intake
writes and fsyncs there first, transcription reads the staged copy, and the
audio moves to the archive after the attempt's response. If staging cannot take
the file (for example `ENOSPC`, `EACCES` or a missing folder), intake falls back
to the archive and the request still succeeds.

The move runs on a single serial queue so it never competes with itself on a
slow disk. It copies with the same durable protocol and removes the staged copy
only after re-checking that the attempt has ended and the staged bytes are
unchanged; a move that fails leaves the staged copy retrievable for the next
sweep. Playback, explicit retry, delete and the duplicate-upload check resolve
audio from either location, and delete removes every copy.

At startup and then every `VOICE_VAULT_STAGING_SWEEP_MS`, the server archives
anything left in staging, retries failed moves, and deletes abandoned `.part`
files older than one hour. Files without a matching recording are left alone.
Without `VOICE_VAULT_STAGING_DIR`, behaviour and file locations are unchanged.

---

## API

| Method | Path | Description |
| --- | --- | --- |
| `POST` | `/api/profile/enroll` | Multipart (`sample` parts) or JSON base64 clips → calibrated voiceprint |
| `GET` | `/api/profile/status` | Enrollment state, model, `μ`, `σ`, threshold, gate configuration |
| `POST` | `/api/profile/verify` | Score one clip against the gallery (`accepted`, `score`, `z`) |
| `DELETE` | `/api/profile` | Remove the enrolled voiceprint (`POST /api/profile/reset` also works) |
| `POST` | `/api/transcribe` | Save/index original before transcription; optional `X-Recording-Id` stable identity; existing success fields and `gate` report |
| `GET` | `/api/history?limit=50` | Capture-ordered recordings, including failed/interrupted items and audio availability |
| `GET` | `/api/recording/:id` | Safe recording state and saved terminal `outcome`; no inference |
| `POST` | `/api/recording/:id/transcribe` | Explicit retry of saved failure/interruption; completed outcome replay |
| `GET` | `/api/audio/:id` | Stream the selected original audio |
| `DELETE` | `/api/recording/:id` | Delete selected owned original and row; busy or incomplete cleanup is reported |

Uploads without an identity keep their existing raw/multipart success contract and receive
a generated ID. Updated clients send their recording UUID as `X-Recording-Id` (ASCII
letters, digits, `_` and `-`, at most 100 characters). The same ID and bytes replay
an existing outcome without inference; different bytes return HTTP 409
`identity_conflict`. Processing returns HTTP 409 `busy`; retry is an explicit action.
Lookup returns `{ok:true, recording:{id,status,error_category,audio_available,outcome,...}}`.
A processing error returns `{ok:false,id,status:"failed",errorCategory,saved,audioAvailable}`;
`storage_full` includes temporary-processing ENOSPC. Intake/metadata failure never
claims `saved:true`. Restart marks ownerless intake/processing as interrupted and
never retries automatically. Missing audio is reported separately from the outcome.
Failure responses contain fixed categories, never private paths or raw backend bodies.

```bash
# Enroll three 5-second clips
curl -s -X POST http://localhost:3005/api/profile/enroll \
  -F sample=@clip1.wav -F sample=@clip2.wav -F sample=@clip3.wav

# Status
curl -s http://localhost:3005/api/profile/status | jq '{enrolled, dim, mu, sigma, threshold}'

# Transcribe (gate report tells you how many segments were kept)
curl -s -X POST http://localhost:3005/api/transcribe \
  -H 'Content-Type: audio/wav' --data-binary @dictation.wav | jq '{text, rejected, gate}'
```

---

## Getting Started

### 1. Server Setup

Ensure Node.js 22+ and `ffmpeg` are installed on your host with an accessible GPU:

```bash
# Start the backend server
node src/server.mjs
```

The server listens on `http://0.0.0.0:3005` (or via Tailscale HTTPS).

### 2. Speaker Verification Runtime (optional but recommended)

```bash
npm run speaker:setup        # creates ~/.cache/voice-vault/venv (onnxruntime + numpy)
npm run speaker:fetch-model  # downloads + sha256-verifies the CAM++ model
npm run speaker:list-models  # show all registered 192-d/512-d models
```

Without this runtime (or without a model), Voice Vault still enrolls and gates
using the built-in 192-d log-mel DSP embedder; with it, the trained CAM++
model is used and reported as `onnxruntime:CPUExecutionProvider`.

### 3. Building the Android APK

The project includes a standalone build script that uses Android build-tools directly without requiring Gradle:

```bash
cd android
./build-apk.sh
```

The signed release APK will be generated at `android/build/VoiceVault.apk` and copied to `public/voice-vault.apk`.

> **Release keystore**: `android/release.keystore` is **tracked in git on purpose**
> (alias `voicevault`, store/key password `voicevault`). Android rejects in-place
> updates signed with a different certificate
> (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`), so never regenerate or replace it.
> Expected signing certificate SHA-256:
> `24:AF:79:AF:96:7D:37:80:6A:AC:2B:8D:82:2B:43:81:56:37:64:F0:76:D9:B3:7C:1E:EF:F6:F1:D8:14:1D:F0`.
> Verify a build with
> `apksigner verify --print-certs public/voice-vault.apk`.

### 4. Pixel Quick Tap Setup

1. Install `VoiceVault.apk` on your Google Pixel phone.
2. Grant Microphone and Notification permissions.
3. Open **Settings > System > Gestures > Quick Tap to start actions**.
4. Turn Quick Tap **ON**.
5. Select **Open app** and choose **Voice Vault** (or **Voice Vault (Quick Toggle)**).
6. Double-tap the back of your phone to start recording; double-tap again to transcribe and copy to clipboard.
7. Enroll your voice from the dashboard (Voice Gate row → record 3 clips) or from the web UI.

---

## Verification

```bash
npm test                     # unit + integration suite (node:test)
npm run test:e2e             # real backends: POST /api/transcribe latency (< 2 s)
npm run verify               # full ticket verification, incl. APK cert + benchmarks
npm run verify:build-apk     # same, plus a clean APK rebuild
python3 python/validate_fbank.py --help
```

`test/transcriber.test.mjs` covers the backend cascade hermetically (mock daemon
socket and a stub `handy`): the 15 s cap, the non-200 / `{ ok: false }` rejection
paths, the daemon → dGPU → iGPU → CPU order, and the all-backends-failed report.
`npm run test:e2e` adds the real end-to-end check against the hardware.

`npm run verify` covers the ticket's testing plan end to end: beamforming audio
source, unchanged signing certificate, 192-d/512-d normalized embeddings,
self-calibrated `μ`/`σ`/threshold, SQLite persistence, retained/dropped/mixed
speaker benchmarks, the no-profile fallback, and gate latency. Measured on this
host (AMD Ryzen 7 250 + RTX A2000, ONNX CPU provider, 3.8 s utterance):

| Measurement | Value |
| --- | --- |
| Gate overhead (VAD + embedding + decision) | 31–40 ms |
| Embedding only | 30–39 ms |
| Enrolled speaker score | 1.000 (θ 0.741) |
| Different speaker score | 0.221 (rejected) |
| Brief "yes/okay" same speaker | 0.741 (kept, relaxed) |

---

## Configuration

| Variable | Default | Purpose |
| --- | --- | --- |
| `PORT` / `HOST` | `3005` / `0.0.0.0` | HTTP bind address |
| `VOICE_VAULT_DB` | `/home/qqp/recordings/voice-vault/history.db` | SQLite database |
| `VOICE_VAULT_STORAGE` | `/home/qqp/recordings/voice-vault/raw` | Raw audio archive directory |
| `VOICE_VAULT_STAGING_DIR` | — | Fast staging folder for accepted audio; unset keeps the archive-only path |
| `VOICE_VAULT_STAGING_SWEEP_MS` | `600000` | Interval for archiving leftover staged audio |
| `VOICE_VAULT_SPEAKER_GATE` | `on` | Set `off` to disable gating globally |
| `VOICE_VAULT_SPEAKER_BACKEND` | `auto` | `auto` \| `onnx` \| `dsp` |
| `VOICE_VAULT_SPEAKER_PROVIDER` | `cpu` | `cpu` \| `cuda` \| `tensorrt` |
| `VOICE_VAULT_SPEAKER_MODEL` | — | Explicit path to an ONNX model |
| `VOICE_VAULT_SPEAKER_MODEL_ID` | `campplus-en-voxceleb-512` | Registered model id |
| `VOICE_VAULT_MODEL_DIR` | `./models` | Model cache directory |
| `VOICE_VAULT_SPEAKER_AUTODOWNLOAD` | `on` | Set `off` to never download models |
| `VOICE_VAULT_PYTHON` | auto-detected | Python interpreter for the sidecar |
| `VOICE_VAULT_SPEAKER_THREADS` | `4` | onnxruntime intra-op threads |
| `WHISPER_SOCKET` | `/tmp/voice-vault-asr.sock` | Warm ASR daemon unix socket |
| `WHISPER_SOCKET_TIMEOUT_MS` | `15000` | Daemon cap before falling back to `handy` |
| `WHISPER_MODEL` | `turbo` | Model for the warm daemon and direct `handy` tiers (production: `bark-3.0-clean-Q8_0`) |
| `WHISPER_MAX_AUDIO_MS` | `120000` | Warm daemon refuses WAV input longer than this (HTTP 413) |
| `VOICE_VAULT_PIECE_MAX_MS` | `45000` | Maximum length of one transcribed piece |
| `VOICE_VAULT_PIECE_WINDOW_START_MS` | `20000` | Start of the pause-search window |
| `VOICE_VAULT_PIECE_WINDOW_END_MS` | `40000` | End of the pause-search window |
| `VOICE_VAULT_PIECE_OVERLAP_MS` | `1500` | Overlap before a forced cut |
| `HANDY_BIN` | `/home/qqp/.local/bin/handy` | Executable used by the direct tiers |
| `HANDY_DEVICE_INDEX_GPU` | `1` | `handy --list-devices` index of the RTX A2000 |
| `HANDY_DEVICE_INDEX_IGPU` | `0` | Index of the Radeon 780M iGPU |
| `HANDY_DEVICE_INDEX_CPU` | `2` | Index of the CPU backend |
| `HANDY_TIMEOUT_MS` | `600000` | Cap before a hung `handy` process is killed |

---

## License

MIT
