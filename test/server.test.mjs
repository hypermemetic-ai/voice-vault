import test, { after } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { fixtures, speechFixturesAvailable, tempDbPath } from "./helpers/audio.mjs";

const hasFixtures = speechFixturesAvailable();
const paths = tempDbPath("server");
process.env.VOICE_VAULT_DB = paths.db;
process.env.VOICE_VAULT_STORAGE = paths.raw;

const { createAppServer } = await import("../src/server.mjs");
const { disposeSharedEmbedder } = await import("../src/speaker.mjs");
const { decodeWav } = await import("../src/wav.mjs");

const whisperCalls = [];
let gatedMixedMs = 0;
const server = createAppServer({
  rawDir: paths.raw,
  transcribeOptions: {
    transcribe: async (wavPath, reqId) => {
      const decoded = decodeWav(fs.readFileSync(wavPath));
      const durationMs = decoded ? (decoded.samples.length / decoded.sampleRate) * 1000 : 0;
      whisperCalls.push({ reqId, durationMs });
      return { text: `heard ${Math.round(durationMs)}ms`, transcribe_ms: 1, backend: "stub" };
    },
  },
});
await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
const base = `http://127.0.0.1:${server.address().port}`;
after(async () => {
  server.closeAllConnections?.();
  server.close();
  await disposeSharedEmbedder();
});

function audioForm(files, field = "sample") {
  const form = new FormData();
  for (const file of files) {
    form.append(field, new Blob([fs.readFileSync(file)], { type: "audio/wav" }), path.basename(file));
  }
  return form;
}

async function getStatus() {
  const response = await fetch(`${base}/api/profile/status`);
  assert.equal(response.status, 200);
  return response.json();
}

async function enroll(files, field = "sample") {
  const response = await fetch(`${base}/api/profile/enroll`, {
    method: "POST",
    body: audioForm(files, field),
  });
  return { status: response.status, body: await response.json() };
}

async function transcribe(file) {
  const form = new FormData();
  form.append("audio", new Blob([fs.readFileSync(file)], { type: "audio/wav" }), path.basename(file));
  const response = await fetch(`${base}/api/transcribe`, { method: "POST", body: form });
  return { status: response.status, body: await response.json() };
}

async function verify(file) {
  const response = await fetch(`${base}/api/profile/verify`, {
    method: "POST",
    body: audioForm([file]),
  });
  return { status: response.status, body: await response.json() };
}

test("status reports an unenrolled profile with configuration", async () => {
  const status = await getStatus();
  assert.equal(status.enrolled, false);
  assert.equal(status.gating.enabled, false);
  assert.equal(status.gating.reason, "not_enrolled");
  assert.equal(status.configuration.strictness, 3);
  assert.equal(status.configuration.shortUtteranceMs, 3000);
  assert.ok(status.models.available.length >= 3);
  assert.ok(status.models.default);
});

test("enroll rejects a payload without audio", async () => {
  const response = await fetch(`${base}/api/profile/enroll`, {
    method: "POST",
    body: JSON.stringify({ samples: [] }),
    headers: { "Content-Type": "application/json" },
  });
  assert.equal(response.status, 400);
  const body = await response.json();
  assert.equal(body.ok, false);
});

test("enroll requires clips long enough to carry speaker identity", { skip: !hasFixtures }, async () => {
  const files = fixtures();
  const { status, body } = await enroll([files.aTiny, files.aTiny]);
  assert.equal(status, 400);
  assert.match(body.error, /shorter than/i);
});

test("enroll builds and persists a calibrated voiceprint", { skip: !hasFixtures }, async () => {
  const files = fixtures();
  const { status, body } = await enroll([files.a1, files.a2, files.a3]);
  assert.equal(status, 200);
  assert.equal(body.ok, true);
  assert.equal(body.enrolled, true);
  assert.equal(body.sampleCount, 3);
  assert.equal(body.clipsReceived, 3);
  assert.equal(body.calibration.mode, "self");
  assert.equal(body.calibration.pairCount, 3);
  assert.ok([192, 512].includes(body.dim), `dim ${body.dim}`);
  const expectedThreshold = Math.min(0.85, Math.max(0.2, body.mu - 3 * body.sigma));
  assert.ok(
    Math.abs(body.threshold - expectedThreshold) < 1e-4,
    `threshold ${body.threshold} vs mu-3sigma ${expectedThreshold}`,
  );
  assert.ok(body.embedder.backend);
  assert.deepEqual(body.durationsMs.length, 3);

  const status2 = await getStatus();
  assert.equal(status2.enrolled, true);
  assert.equal(status2.threshold, body.threshold);
  assert.equal(status2.mu, body.mu);
  assert.equal(status2.sigma, body.sigma);
  assert.equal(status2.modelId, body.modelId);
  assert.equal(status2.dim, body.dim);
  // Never leak gallery vectors over the API.
  const serialized = JSON.stringify(status2);
  assert.ok(!serialized.includes("gallery"));
  assert.ok(!serialized.includes("embedding"));
});

test("verify accepts the enrolled speaker and rejects another", { skip: !hasFixtures }, async () => {
  const files = fixtures();
  const own = await verify(files.a2);
  assert.equal(own.status, 200);
  assert.equal(own.body.accepted, true);
  assert.ok(own.body.score >= own.body.threshold);
  assert.equal(own.body.durationMs > 1000, true);

  const other = await verify(files.b2);
  assert.equal(other.status, 200);
  assert.equal(other.body.accepted, false);
  assert.ok(other.body.score < other.body.threshold);
  assert.equal(other.body.durationMs > 1000, true);
});

test("transcription gates out a different speaker and keeps the enrolled one", { skip: !hasFixtures }, async () => {
  const files = fixtures();
  whisperCalls.length = 0;

  const own = await transcribe(files.a1);
  assert.equal(own.status, 200);
  assert.equal(own.body.rejected, false);
  assert.ok(own.body.text.length > 0);
  assert.equal(own.body.gate.enabled, true);
  assert.ok(own.body.gate.kept >= 1);

  const other = await transcribe(files.b1);
  assert.equal(other.status, 200);
  assert.equal(other.body.rejected, true);
  assert.equal(other.body.text, "");
  assert.equal(other.body.backend, "speaker_gate_rejected");
  assert.equal(other.body.gate.kept, 0);

  const history = await (await fetch(`${base}/api/history?limit=5`)).json();
  const statuses = history.recordings.map((recording) => recording.status);
  assert.ok(statuses.includes("speaker_rejected"));
  assert.ok(statuses.includes("transcribed"));
});

test("mixed audio only forwards the enrolled speaker's segments to Whisper", { skip: !hasFixtures }, async () => {
  const files = fixtures();
  whisperCalls.length = 0;
  const result = await transcribe(files.mixed);
  assert.equal(result.status, 200);
  assert.equal(result.body.gate.enabled, true);
  assert.equal(result.body.gate.kept, 2);
  assert.equal(result.body.gate.rejected, 1);
  assert.equal(whisperCalls.length, 1);
  assert.ok(whisperCalls[0].durationMs < 11000, `whisper heard ${whisperCalls[0].durationMs}ms`);
  assert.ok(whisperCalls[0].durationMs > 7000);
  gatedMixedMs = whisperCalls[0].durationMs;
});

test("JSON base64 enrollment is supported", { skip: !hasFixtures }, async () => {
  const files = fixtures();
  const payload = {
    samples: [files.a1, files.a2, files.a3].map((file) => ({
      wavBase64: fs.readFileSync(file).toString("base64"),
    })),
  };
  const response = await fetch(`${base}/api/profile/enroll`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload),
  });
  assert.equal(response.status, 200);
  const body = await response.json();
  assert.equal(body.enrolled, true);
  assert.equal(body.sampleCount, 3);
});

test("deleting the profile restores ungated transcription", { skip: !hasFixtures }, async () => {
  const files = fixtures();
  const deleted = await fetch(`${base}/api/profile`, { method: "DELETE" });
  assert.equal(deleted.status, 200);

  const status = await getStatus();
  assert.equal(status.enrolled, false);

  whisperCalls.length = 0;
  const result = await transcribe(files.mixed);
  assert.equal(result.body.gate.enabled, false);
  assert.equal(result.body.gate.reason, "not_enrolled");
  assert.equal(result.body.rejected, false);
  assert.equal(whisperCalls.length, 1);
  assert.ok(
    whisperCalls[0].durationMs > gatedMixedMs + 2000,
    `ungated ${whisperCalls[0].durationMs}ms vs gated ${gatedMixedMs}ms`,
  );

  const verifyWithoutProfile = await verify(files.a1);
  assert.equal(verifyWithoutProfile.status, 409);
});

test("unknown API and static paths return 404", async () => {
  const api = await fetch(`${base}/api/does-not-exist`);
  assert.equal(api.status, 404);
  const page = await fetch(`${base}/not-a-real-page`);
  assert.equal(page.status, 404);
});
