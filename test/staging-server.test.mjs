import test, { after } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

const root = fs.mkdtempSync(path.join(os.tmpdir(), "vv-staging-server-"));
process.env.VOICE_VAULT_DB = path.join(root, "history.db");
process.env.VOICE_VAULT_STORAGE = path.join(root, "raw-default");
process.env.VOICE_VAULT_SPEAKER_AUTODOWNLOAD = "off";
delete process.env.VOICE_VAULT_STAGING_DIR;
delete process.env.VOICE_VAULT_STAGING_SWEEP_MS;

const db = await import("../src/db.mjs");
const { createAppServer } = await import("../src/server.mjs");

const servers = [];
let sequence = 0;
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const stage = label => fs.mkdtempSync(path.join(root, `stage-${label}-`));

/** Start the real routing with a synthetic transcriber and isolated folders. */
async function app(options = {}) {
  const rawDir = options.rawDir || path.join(root, `raw-${sequence++}`);
  const stagingDir = Object.hasOwn(options, "stagingDir") ? options.stagingDir : null;
  const calls = [];
  const transcribeAudioFile = options.transcribeAudioFile || (async (file) => {
    calls.push(file);
    return { text: "synthetic text", hasSpeech: true, transcribeMs: 1, audioSentMs: 5, backend: "stub", gate: { enabled: false } };
  });
  const server = createAppServer({ ...options, rawDir, stagingDir, transcribeAudioFile });
  await new Promise(resolve => server.listen(0, "127.0.0.1", resolve));
  servers.push(server);
  const base = `http://127.0.0.1:${server.address().port}`;
  const request = async (route, init) => {
    const response = await fetch(base + route, init);
    return { status: response.status, body: await response.json() };
  };
  const upload = (id, body = Buffer.from(`synthetic ${id}`), contentType = "audio/wav") => request("/api/transcribe", {
    method: "POST",
    headers: { "Content-Type": contentType, ...(id ? { "X-Recording-Id": id } : {}) },
    body,
  });
  const close = () => new Promise(resolve => { server.closeAllConnections(); server.close(resolve); });
  return {
    server, rawDir, stagingDir, calls, base, request, upload, close,
    raw: file => path.join(rawDir, file),
    staged: file => path.join(stagingDir, file),
  };
}

after(async () => {
  for (const server of servers) { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); }
  fs.rmSync(root, { recursive: true, force: true });
});

test("staging configured: the transcriber reads the staged copy, which is archived afterwards", async () => {
  const stagingDir = stage("intake");
  const a = await app({ stagingDir });
  const bytes = Buffer.from("synthetic staged-intake");
  const result = await a.upload("staged-intake", bytes);
  assert.equal(result.status, 200);
  assert.equal(result.body.ok, true);
  assert.equal(a.calls.length, 1);
  assert.equal(path.dirname(a.calls[0]), stagingDir);
  assert.equal(path.basename(a.calls[0]), "staged-intake.wav");

  await a.server.archiveIdle();
  assert.equal(fs.existsSync(a.staged("staged-intake.wav")), false);
  assert.equal(fs.readFileSync(a.raw("staged-intake.wav")).equals(bytes), true);
});

test("without staging: the transcriber reads the archive copy, as before", async () => {
  const a = await app({ stagingDir: null });
  const result = await a.upload("archive-only");
  assert.equal(result.status, 200);
  assert.equal(a.calls.length, 1);
  assert.equal(a.calls[0], a.raw("archive-only.wav"));
  assert.equal(fs.existsSync(a.raw("archive-only.wav")), true);
});

test("staging write failure falls back to the archive and the request still succeeds", async () => {
  const stagingDir = stage("full");
  const stagingFds = new Set();
  const files = {
    ...fs,
    openSync(file, ...args) {
      const fd = fs.openSync(file, ...args);
      if (String(file).startsWith(stagingDir + path.sep)) stagingFds.add(fd);
      return fd;
    },
    writeFileSync(fd, ...args) {
      if (stagingFds.has(fd)) throw Object.assign(new Error("no space left on device"), { code: "ENOSPC" });
      return fs.writeFileSync(fd, ...args);
    },
    closeSync(fd) {
      stagingFds.delete(fd);
      return fs.closeSync(fd);
    },
  };
  const a = await app({ stagingDir, recordingFiles: files, stagingSweepMs: 0 });
  const result = await a.upload("fallback-full");
  assert.equal(result.status, 200);
  assert.equal(result.body.ok, true);
  assert.equal(a.calls[0], a.raw("fallback-full.wav"));
  assert.equal(fs.readFileSync(a.raw("fallback-full.wav"), "utf8"), "synthetic fallback-full");
  assert.deepEqual(fs.readdirSync(stagingDir), []);
});

test("audio is identical while staged and after archiving, and staging empties", async () => {
  const stagingDir = stage("serve");
  const bytes = Buffer.from("synthetic staged-serve");
  const calls = [];
  let release;
  const gate = new Promise(resolve => { release = resolve; });
  const a = await app({
    stagingDir,
    stagingSweepMs: 0,
    transcribeAudioFile: async (file) => {
      calls.push(file);
      await gate;
      return { text: "served", hasSpeech: true, transcribeMs: 1, backend: "stub" };
    },
  });

  const pending = a.upload("staged-serve", bytes);
  while (calls.length === 0) await sleep(5);
  assert.equal(calls[0], a.staged("staged-serve.wav"));
  const whileStaged = await fetch(`${a.base}/api/audio/staged-serve`);
  assert.equal(whileStaged.status, 200);
  const stagedBytes = Buffer.from(await whileStaged.arrayBuffer());
  assert.deepEqual(stagedBytes, bytes);

  release();
  assert.equal((await pending).status, 200);
  await a.server.archiveIdle();
  const afterArchive = await fetch(`${a.base}/api/audio/staged-serve`);
  assert.equal(afterArchive.status, 200);
  assert.deepEqual(Buffer.from(await afterArchive.arrayBuffer()), stagedBytes);
  assert.equal(fs.existsSync(a.staged("staged-serve.wav")), false);
  assert.equal(fs.existsSync(a.raw("staged-serve.wav")), true);
});

test("delete removes every copy, staged or archived", async () => {
  const stagingDir = stage("delete");
  const a = await app({ stagingDir, stagingSweepMs: 0 });
  const bytes = Buffer.from("synthetic delete-both");
  assert.equal((await a.upload("delete-both", bytes)).status, 200);
  await a.server.archiveIdle();
  assert.equal(fs.existsSync(a.raw("delete-both.wav")), true);

  // Recreate the transient state where both copies exist.
  fs.writeFileSync(a.staged("delete-both.wav"), bytes);
  const deleted = await a.request("/api/recording/delete-both", { method: "DELETE" });
  assert.equal(deleted.status, 200);
  assert.equal(deleted.body.ok, true);
  assert.equal(fs.existsSync(a.raw("delete-both.wav")), false);
  assert.equal(fs.existsSync(a.staged("delete-both.wav")), false);
  assert.equal(db.getRecording("delete-both"), undefined);
});

test("duplicate-upload verification reads a staged recording", async () => {
  const stagingDir = stage("duplicate");
  const a = await app({ stagingDir, stagingSweepMs: 0 });
  const bytes = Buffer.from("synthetic duplicate");
  // Legacy row without a stored fingerprint: the check must read the staged file.
  db.saveRecording({ id: "staged-dup", rawFilename: "staged-dup.wav", status: "transcribed", transcript: "previous text" });
  fs.writeFileSync(a.staged("staged-dup.wav"), bytes);

  const conflict = await a.upload("staged-dup", Buffer.from("different bytes"));
  assert.equal(conflict.status, 409);
  assert.equal(conflict.body.errorCategory, "identity_conflict");

  const replay = await a.upload("staged-dup", bytes);
  assert.equal(replay.status, 200);
  assert.equal(replay.body.text, "previous text");
  assert.equal(a.calls.length, 0);
});

test("failed attempt is archived, and a retry transcribes from the archive", async () => {
  const stagingDir = stage("failure");
  const calls = [];
  let failing = true;
  const a = await app({
    stagingDir,
    transcribeAudioFile: async (file) => {
      calls.push(file);
      if (failing) throw new Error("backend failure");
      return { text: "retried", hasSpeech: true, transcribeMs: 1, backend: "stub" };
    },
  });
  const first = await a.upload("failed-then-retry");
  assert.equal(first.status, 500);
  await a.server.archiveIdle();
  assert.equal(fs.existsSync(a.staged("failed-then-retry.wav")), false);
  assert.equal(fs.existsSync(a.raw("failed-then-retry.wav")), true);

  failing = false;
  const retry = await a.request("/api/recording/failed-then-retry/transcribe", { method: "POST" });
  assert.equal(retry.status, 200);
  assert.equal(retry.body.text, "retried");
  assert.equal(calls[1], a.raw("failed-then-retry.wav"));
});

test("startup sweep archives a leftover staged file with a matching row", async () => {
  const stagingDir = stage("startup");
  const bytes = Buffer.from("synthetic leftover");
  db.saveRecording({ id: "startup-leftover", rawFilename: "startup-leftover.wav", status: "interrupted" });
  fs.writeFileSync(path.join(stagingDir, "startup-leftover.wav"), bytes);

  const a = await app({ stagingDir, stagingSweepMs: 0 });
  await a.server.archiveIdle();
  assert.equal(fs.existsSync(a.staged("startup-leftover.wav")), false);
  assert.equal(fs.readFileSync(a.raw("startup-leftover.wav")).equals(bytes), true);
});

test("an identical archive copy is kept and the staged duplicate removed", async () => {
  const stagingDir = stage("identical");
  const bytes = Buffer.from("synthetic identical");
  const a = await app({ stagingDir, stagingSweepMs: 25 });
  db.saveRecording({ id: "identical-copy", rawFilename: "identical-copy.wav", status: "transcribed" });
  fs.writeFileSync(a.staged("identical-copy.wav"), bytes);
  fs.writeFileSync(a.raw("identical-copy.wav"), bytes);

  const deadline = Date.now() + 3000;
  while (fs.existsSync(a.staged("identical-copy.wav")) && Date.now() < deadline) await sleep(25);
  await a.server.archiveIdle();
  assert.equal(fs.existsSync(a.staged("identical-copy.wav")), false);
  assert.equal(fs.readFileSync(a.raw("identical-copy.wav")).equals(bytes), true);
});

test("a failed move keeps the staged copy retrievable and a later sweep completes it", async () => {
  const stagingDir = stage("move-retry");
  const rawDir = path.join(root, "raw-move-retry");
  let failing = true;
  const files = {
    ...fs,
    openSync(file, ...args) {
      if (failing && String(file).startsWith(rawDir + path.sep) && args[0] === "wx") {
        throw Object.assign(new Error("no space left on device"), { code: "ENOSPC" });
      }
      return fs.openSync(file, ...args);
    },
  };
  const a = await app({ rawDir, stagingDir, recordingFiles: files, stagingSweepMs: 50 });
  const bytes = Buffer.from("synthetic retry-move");
  assert.equal((await a.upload("retry-move", bytes)).status, 200);
  await a.server.archiveIdle();
  assert.equal(fs.existsSync(a.raw("retry-move.wav")), false);
  assert.equal(fs.existsSync(a.staged("retry-move.wav")), true);

  const served = Buffer.from(await (await fetch(`${a.base}/api/audio/retry-move`)).arrayBuffer());
  assert.deepEqual(served, bytes);

  failing = false;
  const deadline = Date.now() + 3000;
  while (!fs.existsSync(a.raw("retry-move.wav")) && Date.now() < deadline) await sleep(25);
  assert.equal(fs.existsSync(a.raw("retry-move.wav")), true);
  assert.equal(fs.existsSync(a.staged("retry-move.wav")), false);
  await a.server.archiveIdle();
});

test("sweep leaves orphan files alone and prunes only abandoned part files", async () => {
  const stagingDir = stage("sweep");
  const orphan = path.join(stagingDir, "orphan-unknown.wav");
  const abandoned = path.join(stagingDir, "abandoned.wav.part");
  const fresh = path.join(stagingDir, "fresh.wav.part");
  fs.writeFileSync(orphan, "orphan bytes");
  fs.writeFileSync(abandoned, "partial");
  fs.writeFileSync(fresh, "partial");
  const old = new Date(Date.now() - 2 * 60 * 60 * 1000);
  fs.utimesSync(abandoned, old, old);

  const a = await app({ stagingDir, stagingSweepMs: 0 });
  await a.server.archiveIdle();
  assert.equal(fs.existsSync(orphan), true);
  assert.equal(fs.existsSync(abandoned), false);
  assert.equal(fs.existsSync(fresh), true);
  assert.equal(fs.existsSync(a.raw("orphan-unknown.wav")), false);
});

test("closing the server stops the periodic sweep", async () => {
  const stagingDir = stage("close");
  const a = await app({ stagingDir, stagingSweepMs: 25 });
  await a.close();

  db.saveRecording({ id: "after-close", rawFilename: "after-close.wav", status: "interrupted" });
  fs.writeFileSync(path.join(stagingDir, "after-close.wav"), "synthetic after-close");
  await sleep(150);
  assert.equal(fs.existsSync(path.join(stagingDir, "after-close.wav")), true);
  assert.equal(fs.existsSync(a.raw("after-close.wav")), false);
});
