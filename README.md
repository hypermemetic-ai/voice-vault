# Voice Vault

Ultra-reliable, local-first asynchronous voice recorder and dictation tool for Android (Google Pixel 10) and Web, transcribing through Whisper Large v3 Turbo on local GPU and automatically copying text straight to the clipboard.

---

## Features

- **0ms Headless Quick Tap Dictation**: Double-tap the back of your Pixel to start dictating without leaving your active app (e.g. Orca). Double-tap again to stop, transcribe on GPU, and copy directly to the system clipboard.
- **Dynamic Floating Status Overlay**: Compact, semi-transparent top status rectangle displaying live recording duration, transcription state (`Processing`), and completion status (`Copied`), positioned clear of the front camera punch-hole.
- **Slide-Out History Drawer**: Access the last 50 transcriptions and cached recordings grouped by date, with 1-tap copy to clipboard and duration metrics.
- **Zero-Latency Acoustic Feedback**: Directly synthesized 16-bit 44.1kHz mono PCM audio (<3ms latency) with warm liquid start pops and glass bell harmonic completion chimes.
- **Physical Side-Button Trigger**: Optional double-tap Volume Down shortcut via Android Accessibility Service.
- **Floating Screen Edge Bubble**: Draggable on-screen toggle button for devices without rear-tap gestures.
- **Whisper Large v3 Turbo Backend**: Local Node.js server with SQLite storage, VAD silence trimming, anti-hallucination sanitization, and streaming sliding-window support for arbitrarily long recordings.
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
│       ├── SoundEffects.java             # Low-latency synthesized PCM acoustics
│       ├── ToggleDictationActivity.java  # 0ms headless transparent trigger
│       ├── VoiceVaultKeyService.java     # Volume down double-press accessibility listener
│       ├── VoiceVaultService.java        # Core background recording & transcription service
│       └── VoiceVaultTileService.java    # Quick Settings drop-down tile
├── public/                          # Web UI / PWA & Downloadable APK
│   ├── index.html                   # Web dashboard
│   ├── style.css                    # Pitch-black minimal styling
│   ├── app.js                       # Audio recording & history controller
│   └── icon.svg                     # Vector app icon
├── src/                             # Backend Server
│   ├── db.mjs                       # SQLite WAL history storage
│   ├── server.mjs                   # HTTP / REST audio upload & transcription server
│   └── transcriber.mjs              # Whisper Turbo daemon client & VAD pipeline
└── package.json
```

---

## Getting Started

### 1. Server Setup

Ensure Node.js 20+ and `ffmpeg` are installed on your host with an accessible GPU:

```bash
# Start the backend server
node src/server.mjs
```

The server listens on `http://0.0.0.0:3005` (or via Tailscale HTTPS).

### 2. Building the Android APK

The project includes a standalone build script that uses Android build-tools directly without requiring Gradle:

```bash
cd android
./build-apk.sh
```

The signed release APK will be generated at `android/build/VoiceVault.apk` and copied to `public/voice-vault.apk`.

### 3. Pixel Quick Tap Setup

1. Install `VoiceVault.apk` on your Google Pixel phone.
2. Grant Microphone and Notification permissions.
3. Open **Settings > System > Gestures > Quick Tap to start actions**.
4. Turn Quick Tap **ON**.
5. Select **Open app** and choose **Voice Vault** (or **Voice Vault (Quick Toggle)**).
6. Double-tap the back of your phone to start recording; double-tap again to transcribe and copy to clipboard.

---

## License

MIT
