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

test("key service wires timing to both paths and cancels callbacks on mode/service changes", () => {
  const source = fs.readFileSync(path.join(java, "VoiceVaultKeyService.java"), "utf8");
  assert.match(source, /mVolUpTiming\.inactivePress\(now\)/);
  assert.match(source, /mVolUpTiming\.activePress\(SystemClock\.uptimeMillis\(\)\)/);
  assert.match(source, /ActivePress\.EXPIRED_FIRST[\s\S]*?removeCallbacks\(mPendingVolUpRunnable\)[\s\S]*?toggleDictation\(\)/);
  assert.match(source, /postDelayed\(single, VolumeUpTiming\.UP_WINDOW_MS \+ 1\)/);
  assert.match(source, /mVolDnTiming\.activePress\(SystemClock\.uptimeMillis\(\)\)/);
  assert.match(source, /ActivePress\.EXPIRED_FIRST[\s\S]*?removeCallbacks\(mPendingVolDnRunnable\)[\s\S]*?finishAndInsert\(\)/);
  assert.match(source, /mVolDnTiming\.activeSingleFinished\(\)/);
  assert.equal([...source.matchAll(/postDelayed\(single, VolumeUpTiming\.UP_WINDOW_MS \+ 1\)/g)].length, 2);
  assert.doesNotMatch(source, /DOWN_WINDOW_MS/);
  assert.match(source, /PREF_DICTATION_MODE\.equals\(key\)\) \{\s*cancelPendingKeyCallbacks\(\)/);
  assert.match(source, /void cancelPendingKeyCallbacks\(\) \{[\s\S]*?mVolUpTiming\.reset\(\);\s*mVolDnTiming\.reset\(\)/);
  for (const hook of ["onServiceConnected", "onUnbind", "onDestroy", "onInterrupt"]) {
    assert.match(source, new RegExp(`${hook}\\([^)]*\\)[\\s\\S]*?cancelPendingKeyCallbacks\\(\\)`));
  }
});
