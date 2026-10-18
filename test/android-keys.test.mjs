import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { spawnSync } from "node:child_process";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const java = path.join(root, "android/src/ai/hypermemetic/voicevault");
const source = () => fs.readFileSync(path.join(java, "VoiceVaultKeyService.java"), "utf8");

test("volume keys: taps act on release, 500ms holds replace double presses", () => {
  const s = source();
  assert.match(s, /static final long HOLD_MS = 500;/);
  assert.match(s, /mKeyHandler\.postDelayed\(hold, HOLD_MS\)/);
  assert.match(s, /adjustSuggestedStreamVolume\(AudioManager\.ADJUST_RAISE,\s*AudioManager\.USE_DEFAULT_STREAM_TYPE, AudioManager\.FLAG_SHOW_UI\)/);
  assert.doesNotMatch(s, /VolumeUpTiming|UP_WINDOW_MS|DOUBLE_PRESS_WINDOW_MS/);
  assert.ok(!fs.existsSync(path.join(java, "VolumeUpTiming.java")));
});

test("production key events: taps, holds, repeats, mode-off pass-through and mid-press mode changes", () => {
  const classes = fs.mkdtempSync(path.join(os.tmpdir(), "voice-vault-keys-"));
  try {
    const s = source();
    const start = s.indexOf("    @Override\n    protected boolean onKeyEvent(");
    const end = s.indexOf("    private void stopRecordingService()", start);
    assert.ok(start >= 0 && end > start);
    const methods = s.slice(start, end).replaceAll("super.onKeyEvent(event)", "superKey(event)");
    const file = path.join(classes, "VolumeKeyHarness.java");
    fs.writeFileSync(file, fs.readFileSync(path.join(root, "test/fixtures/VolumeKeyHarness.java"), "utf8")
      .replace("    // PRODUCTION_KEY_EVENT", methods));
    const compiled = spawnSync("javac", ["--release", "11", "-d", classes, file], { encoding: "utf8" });
    assert.equal(compiled.status, 0, compiled.stderr || compiled.error?.message);
    const run = spawnSync("java", ["-cp", classes, "ai.hypermemetic.voicevault.VolumeKeyHarness"], { encoding: "utf8" });
    assert.equal(run.status, 0, run.stderr || run.error?.message);
  } finally { fs.rmSync(classes, { recursive: true, force: true }); }
});
