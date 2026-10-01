import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { spawnSync } from "node:child_process";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const java = path.join(root, "android/src/ai/hypermemetic/voicevault");

test("Up and Down 220ms uptime deadlines, delayed handlers, repeats and mode transitions", () => {
  const classes = fs.mkdtempSync(path.join(os.tmpdir(), "voice-vault-keys-"));
  try {
    const compiled = spawnSync("javac", ["--release", "11", "-d", classes,
      path.join(java, "VolumeUpTiming.java"),
      path.join(root, "test/fixtures/VolumeUpTimingHarness.java")], { encoding: "utf8" });
    assert.equal(compiled.status, 0, compiled.error?.message || compiled.stderr);
    const run = spawnSync("java", ["-cp", classes,
      "ai.hypermemetic.voicevault.VolumeUpTimingHarness"], { encoding: "utf8" });
    assert.equal(run.status, 0, run.error?.message || run.stderr);
  } finally {
    fs.rmSync(classes, { recursive: true, force: true });
  }
});

test("key service debounces Down without toggling auto-send and clears key state on mode changes", () => {
  const source = fs.readFileSync(path.join(java, "VoiceVaultKeyService.java"), "utf8");
  assert.match(source, /mVolUpTiming\.inactivePress\(now\)/);
  assert.match(source, /mVolUpTiming\.activePress\(SystemClock\.uptimeMillis\(\)\)/);
  assert.match(source, /ActivePress\.EXPIRED_FIRST[\s\S]*?removeCallbacks\(mPendingVolUpRunnable\)[\s\S]*?toggleDictation\(\)/);
  assert.match(source, /postDelayed\(single, VolumeUpTiming\.UP_WINDOW_MS \+ 1\)/);
  assert.match(source, /mLastVolDownTime >= 0 && now - mLastVolDownTime < VolumeUpTiming\.UP_WINDOW_MS/);
  assert.match(source, /mLastVolDownTime = now;\s*finishAndInsert\(\)/);
  assert.doesNotMatch(source, /mVolDnTiming|mPendingVolDnRunnable|setAutoSendEnabled/);
  assert.equal([...source.matchAll(/postDelayed\(single, VolumeUpTiming\.UP_WINDOW_MS \+ 1\)/g)].length, 1);
  assert.doesNotMatch(source, /DOWN_WINDOW_MS/);
  assert.match(source, /PREF_DICTATION_MODE\.equals\(key\)\) \{\s*cancelPendingKeyCallbacks\(\)/);
  assert.match(source, /void cancelPendingKeyCallbacks\(\) \{[\s\S]*?mVolUpTiming\.reset\(\);\s*mLastVolDownTime = -1/);
  for (const hook of ["onServiceConnected", "onUnbind", "onDestroy", "onInterrupt"]) {
    assert.match(source, new RegExp(`${hook}\\([^)]*\\)[\\s\\S]*?cancelPendingKeyCallbacks\\(\\)`));
  }
});

test("production key events make Down immediate, debounce repeats, and preserve auto-send preference", () => {
  const classes = fs.mkdtempSync(path.join(os.tmpdir(), "voice-vault-down-"));
  try {
    const source = fs.readFileSync(path.join(java, "VoiceVaultKeyService.java"), "utf8");
    const start = source.indexOf("    @Override\n    protected boolean onKeyEvent(");
    const end = source.indexOf("    private void stopRecordingService()", start);
    assert.ok(start >= 0 && end > start);
    const file = path.join(classes, "VolumeDownHarness.java");
    fs.writeFileSync(file, fs.readFileSync(path.join(root, "test/fixtures/VolumeDownHarness.java"), "utf8")
      .replace("    // PRODUCTION_KEY_EVENT", source.slice(start, end)));
    const compiled = spawnSync("javac", ["--release", "11", "-d", classes,
      path.join(java, "VolumeUpTiming.java"), file], { encoding: "utf8" });
    assert.equal(compiled.status, 0, compiled.stderr || compiled.error?.message);
    const run = spawnSync("java", ["-cp", classes, "ai.hypermemetic.voicevault.VolumeDownHarness"], { encoding: "utf8" });
    assert.equal(run.status, 0, run.stderr || run.error?.message);
  } finally { fs.rmSync(classes, { recursive: true, force: true }); }
});
