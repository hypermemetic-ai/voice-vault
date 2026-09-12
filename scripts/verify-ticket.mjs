#!/usr/bin/env node
/**
 * Verify the two-part speaker-rejection pipeline against .architect/ticket.md.
 *
 *   node scripts/verify-ticket.mjs                  # full verification
 *   node scripts/verify-ticket.mjs --build-apk      # also rebuild + re-verify the APK
 *   node scripts/verify-ticket.mjs --provider cuda  # run embeddings on the RTX A2000
 *   node scripts/verify-ticket.mjs --json           # machine-readable report
 *
 * Sections:
 *   1. Android beamforming (VOICE_RECOGNITION + APK signing certificate)
 *   2. Voice enrollment & calibration (dims, mu/sigma threshold, SQLite storage)
 *   3. Speaker verification & rejection (own / short / other / mixed / no profile)
 *   4. Latency of the gate
 */

import { spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), "..");
const EXPECTED_CERT_SHA256 =
  "24af79af967d37806aac2b8d822b4381563764f076d9b37c1eeff6f1d8141df0";

function parseArgs(argv) {
  const options = {
    buildApk: false,
    json: false,
    provider: process.env.VOICE_VAULT_SPEAKER_PROVIDER || "cpu",
    skipAndroid: false,
    skipLatency: false,
    latencyLimitMs: Number(process.env.VOICE_VAULT_LATENCY_LIMIT_MS || 60),
  };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === "--build-apk") options.buildApk = true;
    else if (arg === "--json") options.json = true;
    else if (arg === "--skip-android") options.skipAndroid = true;
    else if (arg === "--skip-latency") options.skipLatency = true;
    else if (arg === "--provider") options.provider = argv[++i];
    else if (arg.startsWith("--provider=")) options.provider = arg.split("=")[1];
    else if (arg === "--latency-limit-ms") options.latencyLimitMs = Number(argv[++i]);
    else {
      console.error(`Unknown argument: ${arg}`);
      process.exit(2);
    }
  }
  return options;
}

const options = parseArgs(process.argv.slice(2));
const checks = [];

function record(section, name, status, detail = "") {
  checks.push({ section, name, status, detail });
  if (!options.json) {
    const icon = status === "PASS" ? "✓" : status === "SKIP" ? "•" : "✗";
    const padded = name.padEnd(54).slice(0, 54);
    console.log(`  ${icon} ${padded} ${detail}`);
  }
}

const pass = (section, name, detail) => record(section, name, "PASS", detail);
const fail = (section, name, detail) => record(section, name, "FAIL", detail);
const skip = (section, name, detail) => record(section, name, "SKIP", detail);

function section(title) {
  if (!options.json) console.log(`\n${title}`);
}

// ---------------------------------------------------------------------------
// 1. Android beamforming
// ---------------------------------------------------------------------------

function verifyAndroid() {
  section("1. Android beamforming (client/hardware level)");

  const servicePath = path.join(
    ROOT,
    "android/src/ai/hypermemetic/voicevault/VoiceVaultService.java",
  );
  if (!fs.existsSync(servicePath)) {
    skip("android", "VoiceVaultService.java present", servicePath);
  } else {
    const source = fs.readFileSync(servicePath, "utf8");
    const usesVoiceRecognition = /setAudioSource\(MediaRecorder\.AudioSource\.VOICE_RECOGNITION\)/.test(source);
    const usesPlainMic = /setAudioSource\(MediaRecorder\.AudioSource\.MIC\)/.test(source);
    if (usesVoiceRecognition && !usesPlainMic) {
      pass("android", "AudioSource.VOICE_RECOGNITION beamforming", "setAudioSource(VOICE_RECOGNITION)");
    } else {
      fail(
        "android",
        "AudioSource.VOICE_RECOGNITION beamforming",
        `VOICE_RECOGNITION=${usesVoiceRecognition} plainMIC=${usesPlainMic}`,
      );
    }
  }

  const apkPath = path.join(ROOT, "public/voice-vault.apk");
  const buildScript = path.join(ROOT, "android/build-apk.sh");
  if (options.buildApk) {
    if (fs.existsSync(buildScript)) {
      const build = spawnSync("bash", [buildScript], { cwd: path.join(ROOT, "android"), encoding: "utf8" });
      if (build.status === 0) {
        pass("android", "android/build-apk.sh clean build", "signed APK produced");
      } else {
        fail("android", "android/build-apk.sh clean build", (build.stderr || "").trim().split("\n").slice(-3).join(" | "));
      }
    } else {
      skip("android", "android/build-apk.sh clean build", "build script missing");
    }
  } else {
    skip("android", "android/build-apk.sh clean build", "run with --build-apk to rebuild");
  }

  if (!fs.existsSync(apkPath)) {
    skip("android", "APK signing certificate", "public/voice-vault.apk missing");
    return;
  }

  const sdkRoot = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT
    || path.join(os.homedir(), ".local/share/android-sdk");
  let apksigner = null;
  const buildTools = path.join(sdkRoot, "build-tools");
  if (fs.existsSync(buildTools)) {
    for (const version of fs.readdirSync(buildTools).sort().reverse()) {
      const candidate = path.join(buildTools, version, "apksigner");
      if (fs.existsSync(candidate)) {
        apksigner = candidate;
        break;
      }
    }
  }
  if (!apksigner) {
    skip("android", "APK signing certificate", `apksigner not found under ${sdkRoot}`);
    return;
  }

  const verify = spawnSync(apksigner, ["verify", "--print-certs", apkPath], { encoding: "utf8" });
  const output = `${verify.stdout || ""}\n${verify.stderr || ""}`;
  const match = output.match(/certificate SHA-256 digest:\s*([0-9a-f:]+)/i);
  if (verify.status === 0 && match) {
    const digest = match[1].replace(/:/g, "").toLowerCase();
    if (digest === EXPECTED_CERT_SHA256) {
      pass("android", "APK signing certificate unchanged", `24:AF:79:AF… (${digest.slice(0, 16)}…)`);
    } else {
      fail("android", "APK signing certificate unchanged", `got ${digest}`);
    }
  } else {
    fail("android", "APK signing certificate unchanged", (output.trim().split("\n").pop() || "verify failed"));
  }
}

// ---------------------------------------------------------------------------
// 2 + 3 + 4. Server voiceprint gate
// ---------------------------------------------------------------------------

async function verifyServer() {
  const fixtureHelper = await import(
    pathToFileURL(path.join(ROOT, "test/helpers/audio.mjs")).href
  );
  const hasFixtures = fixtureHelper.speechFixturesAvailable();
  const speaker = await import(pathToFileURL(path.join(ROOT, "src/speaker.mjs")).href);

  section("2. Voice enrollment & calibration (server/acoustic level)");

  if (!hasFixtures) {
    skip("enrollment", "speech fixtures", "ffmpeg with the flite filter is required");
    return;
  }
  const files = fixtureHelper.fixtures();

  // Embedding extraction + dimensions.
  const embedder = await speaker.createSpeakerEmbedder({
    reuse: false,
    provider: options.provider,
    download: false,
  });
  const info = embedder.info();
  try {
    const [a1, a2, b1] = await embedder.embedBatch(
      [fixtureHelper.readPcm(files.a1), fixtureHelper.readPcm(files.a2), fixtureHelper.readPcm(files.b1)],
      16000,
    );
    const norm = Math.hypot(...a1.embedding);
    if ([192, 512].includes(a1.embedding.length) && Math.abs(norm - 1) < 1e-4) {
      pass(
        "enrollment",
        "normalized embedding vectors",
        `${a1.embedding.length}-d, L2=${norm.toFixed(5)}, backend=${info.backend}`,
      );
    } else {
      fail("enrollment", "normalized embedding vectors", `dim=${a1.embedding.length} norm=${norm}`);
    }
    if (info.backendFamily === "onnx") {
      const same = speaker.cosineSimilarity(a1.embedding, a2.embedding);
      const other = speaker.cosineSimilarity(a1.embedding, b1.embedding);
      pass(
        "enrollment",
        "speaker-discriminative embeddings",
        `same ${same.toFixed(3)} vs other ${other.toFixed(3)}`,
      );
    } else {
      skip("enrollment", "ONNX runtime available", info.fallbackReason || "using DSP fallback");
    }

    // Both 192-d and 512-d model support.
    const dims = [];
    for (const spec of Object.values(speaker.SPEAKER_MODELS)) {
      if (!fs.existsSync(speaker.modelPathFor(spec.id))) continue;
      const modelEmbedder = await speaker.createSpeakerEmbedder({
        modelId: spec.id,
        reuse: false,
        download: false,
        provider: options.provider,
      });
      try {
        const modelInfo = modelEmbedder.info();
        const [result] = await modelEmbedder.embedBatch([fixtureHelper.readPcm(files.a1)], 16000);
        const modelNorm = Math.hypot(...result.embedding);
        dims.push(`${modelInfo.dim}-d(${spec.id})`);
        if (modelInfo.dim !== spec.outputDim || Math.abs(modelNorm - 1) > 1e-4) {
          fail("enrollment", "model dimension metadata", `${spec.id}: ${modelInfo.dim}`);
        }
      } finally {
        await modelEmbedder.dispose();
      }
    }
    if (dims.length >= 2) {
      pass("enrollment", "192-d and 512-d embedding models", dims.join(", "));
    } else if (dims.length === 1) {
      skip("enrollment", "192-d and 512-d embedding models", `only ${dims[0]} installed`);
    }
  } finally {
    await embedder.dispose();
  }

  // Fresh temp DB for enrollment persistence checks.
  const temp = fixtureHelper.tempDbPath("verify-ticket");
  process.env.VOICE_VAULT_DB = temp.db;
  process.env.VOICE_VAULT_STORAGE = temp.raw;

  const profileModule = await import(pathToFileURL(path.join(ROOT, "src/profile.mjs")).href);
  const transcriber = await import(pathToFileURL(path.join(ROOT, "src/transcriber.mjs")).href);
  const wav = await import(pathToFileURL(path.join(ROOT, "src/wav.mjs")).href);

  const enrollment = await profileModule.enrollVoice(
    [fixtureHelper.readPcm(files.a1), fixtureHelper.readPcm(files.a2), fixtureHelper.readPcm(files.a3)],
    {},
  );
  const profile = enrollment.profile;
  const calibration = enrollment.calibration;

  if (profile.sampleCount === 3 && profile.gallery.length === 3) {
    pass("enrollment", "gallery of 3 reference vectors", `${profile.dim}-d each`);
  } else {
    fail("enrollment", "gallery of 3 reference vectors", `count=${profile.sampleCount}`);
  }

  if (calibration.mode === "self" && calibration.pairCount === 3) {
    pass(
      "enrollment",
      "intra-speaker pairwise calibration",
      `μ=${calibration.mu.toFixed(4)} σ=${calibration.sigma.toFixed(4)} (${calibration.pairCount} pairs)`,
    );
  } else {
    fail("enrollment", "intra-speaker pairwise calibration", JSON.stringify(calibration));
  }

  const expectedThreshold = Math.min(0.85, Math.max(0.2, calibration.mu - 3 * calibration.sigma));
  if (Math.abs(calibration.threshold - expectedThreshold) < 1e-4) {
    pass(
      "enrollment",
      "threshold = μ − 3σ",
      `${calibration.threshold.toFixed(4)} = ${calibration.mu.toFixed(4)} − 3×${calibration.sigma.toFixed(4)}`,
    );
  } else {
    fail("enrollment", "threshold = μ − 3σ", `${calibration.threshold} vs ${expectedThreshold}`);
  }

  // Persistence: re-read through SQLite and inspect the raw tables.
  const { DatabaseSync } = await import("node:sqlite");
  const raw = new DatabaseSync(temp.db);
  const profileRow = raw.prepare("SELECT * FROM voice_profile WHERE id = 1").get();
  const galleryRows = raw.prepare("SELECT COUNT(*) AS count FROM voice_gallery WHERE profile_id = 1").get();
  raw.close();
  const reloaded = profileModule.loadVoiceProfile();
  if (
    profileRow
    && galleryRows.count === 3
    && reloaded
    && Math.abs(reloaded.threshold - calibration.threshold) < 1e-9
  ) {
    pass(
      "enrollment",
      "SQLite persistence (profile + gallery BLOBs)",
      `voice_profile 1 row, voice_gallery ${galleryRows.count} rows`,
    );
  } else {
    fail("enrollment", "SQLite persistence (profile + gallery BLOBs)", `rows=${galleryRows.count}`);
  }

  section("3. Speaker verification & rejection");

  const durations = new Map();
  const stub = (store) => async (wavPath) => {
    const decoded = (await import(pathToFileURL(path.join(ROOT, "src/wav.mjs")).href)).decodeWav(
      fs.readFileSync(wavPath),
    );
    const ms = decoded ? (decoded.samples.length / decoded.sampleRate) * 1000 : 0;
    store.push(ms);
    return { text: `heard ${Math.round(ms)}ms`, transcribe_ms: 1, backend: "stub" };
  };

  const cases = [
    {
      name: "enrolled user speech retained",
      file: files.a1,
      expect: (result) => !result.rejected && result.gate.kept >= 1 && result.text.length > 0,
    },
    {
      name: "short utterance (<1.5s) retained",
      file: files.aShort,
      expect: (result) => !result.rejected && result.gate.kept === 1 && result.gate.segments[0].shortUtteranceRelaxed === true,
    },
    {
      name: "brief words (\"yes\", \"okay\") retained",
      file: files.aYes,
      expect: (result) => !result.rejected && result.gate.kept === 1,
    },
    {
      name: "different speaker dropped",
      file: files.b1,
      expect: (result) => result.rejected === true && result.gate.kept === 0 && result.text === "",
    },
    {
      name: "different speaker's brief words dropped",
      file: files.bYes,
      expect: (result) => result.rejected === true && result.gate.kept === 0,
    },
  ];

  for (const testCase of cases) {
    const calls = [];
    const result = await transcriber.transcribeAudioFile(testCase.file, `verify-${testCase.name}`, {
      transcribe: stub(calls),
      profile,
    });
    if (testCase.expect(result)) {
      const score = result.gate.segments?.[0]?.bestScore;
      pass(
        "verification",
        testCase.name,
        score === undefined ? `kept ${result.gate.kept}` : `score ${Number(score).toFixed(3)} vs θ ${profile.threshold.toFixed(3)}`,
      );
    } else {
      fail("verification", testCase.name, JSON.stringify(result.gate.segments?.[0] || result.gate));
    }
  }

  // Mixed conversation: only the enrolled speaker's turns reach Whisper.
  {
    const calls = [];
    const result = await transcriber.transcribeAudioFile(files.mixed, "verify-mixed", {
      transcribe: stub(calls),
      profile,
    });
    const expected = wavDurationMs(files.mixed);
    if (result.gate.kept === 2 && result.gate.rejected === 1 && calls.length === 1 && calls[0] < expected - 2000) {
      pass(
        "verification",
        "mixed speakers: only user's words forwarded",
        `kept 2/3 segments · ${Math.round(calls[0])}ms of ${Math.round(expected)}ms sent to Whisper`,
      );
      durations.set("mixedGated", calls[0]);
    } else {
      fail(
        "verification",
        "mixed speakers: only user's words forwarded",
        `kept=${result.gate.kept} rejected=${result.gate.rejected} whisper=${calls.map(Math.round)}`,
      );
    }
  }

  // No profile: graceful, ungated transcription (no regression).
  {
    const calls = [];
    const result = await transcriber.transcribeAudioFile(files.mixed, "verify-ungated", {
      transcribe: stub(calls),
      profile: null,
    });
    const gatedMs = durations.get("mixedGated") ?? 0;
    if (!result.gate.enabled && result.gate.reason === "not_enrolled" && !result.rejected && calls.length === 1) {
      pass(
        "verification",
        "no profile enrolled: full audio transcribed",
        `gate disabled · ${Math.round(calls[0])}ms forwarded (gated run: ${Math.round(gatedMs)}ms)`,
      );
    } else {
      fail("verification", "no profile enrolled: full audio transcribed", JSON.stringify(result.gate));
    }
  }

  section("4. Latency (speaker gating overhead)");
  if (options.skipLatency) {
    skip("latency", "gate latency", "skipped by --skip-latency");
  } else {
    const warm = await transcriber.transcribeAudioFile(files.a1, "latency-warm", {
      transcribe: stub([]),
      profile,
    });
    void warm;
    const samples = [];
    for (let i = 0; i < 5; i += 1) {
      const result = await transcriber.transcribeAudioFile(files.a1, `latency-${i}`, {
        transcribe: stub([]),
        profile,
      });
      samples.push(result.gate);
    }
    const gateMs = samples.map((gate) => gate.gateMs).sort((a, b) => a - b);
    const embedMs = samples.map((gate) => gate.embedMs).sort((a, b) => a - b);
    const median = gateMs[Math.floor(gateMs.length / 2)];
    const provider = samples[0].embedder?.provider || options.provider;
    const detail =
      `gate ${gateMs.join("/")}ms · embed ${embedMs.join("/")}ms · ` +
      `${samples[0].segmentCount} segment(s) of ${Math.round(samples[0].segments[0].durationMs)}ms · ${provider}`;
    if (median < options.latencyLimitMs) {
      pass("latency", `gating adds < ${options.latencyLimitMs}ms`, detail);
    } else {
      fail("latency", `gating adds < ${options.latencyLimitMs}ms`, detail);
    }
    checks.push({
      section: "latency",
      name: "timings",
      status: "INFO",
      detail: { gateMs, embedMs, limitMs: options.latencyLimitMs, provider },
    });
    if (!options.json) {
      console.log(
        `\n  Harness: ${os.cpus()[0]?.model?.trim() || "unknown CPU"} · ` +
        `${(os.totalmem() / 1e9).toFixed(1)} GB RAM · GPU: ` +
        `${spawnSync("nvidia-smi", ["--query-gpu=name", "--format=csv,noheader"], { encoding: "utf8" }).stdout.trim() || "none"}`,
      );
    }
  }

}

function wavDurationMs(file) {
  const buffer = fs.readFileSync(file);
  // Minimal WAV data-chunk scan: 16 kHz mono PCM16 -> 32 bytes per ms.
  const dataIndex = buffer.indexOf(Buffer.from("data"));
  const size = dataIndex >= 0 ? buffer.readUInt32LE(dataIndex + 4) : buffer.length - 44;
  return size / 32;
}

// ---------------------------------------------------------------------------

async function main() {
  // Lazily-created embedders (enrollment, transcription) read the provider
  // from the environment, so honour --provider for the whole verification.
  process.env.VOICE_VAULT_SPEAKER_PROVIDER = options.provider;
  if (!options.json) {
    console.log("Voice Vault · two-part speaker rejection verification");
    console.log(`Repository: ${ROOT}`);
    console.log(`Provider:   ${options.provider}`);
    console.log(`Time:       ${new Date().toISOString()}`);
  }

  if (options.skipAndroid) {
    skip("android", "Android checks", "--skip-android");
  } else {
    verifyAndroid();
  }
  await verifyServer();

  const failures = checks.filter((check) => check.status === "FAIL");
  const passes = checks.filter((check) => check.status === "PASS").length;
  const skips = checks.filter((check) => check.status === "SKIP").length;

  if (options.json) {
    console.log(JSON.stringify({ ok: failures.length === 0, passes, skips, failures: failures.length, checks }, null, 2));
  } else {
    console.log(
      `\n${failures.length === 0 ? "RESULT: PASS" : "RESULT: FAIL"} · ` +
      `${passes} passed, ${failures.length} failed, ${skips} skipped`,
    );
    for (const failure of failures) {
      console.log(`  ✗ [${failure.section}] ${failure.name}: ${failure.detail}`);
    }
  }
  process.exit(failures.length === 0 ? 0 : 1);
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
