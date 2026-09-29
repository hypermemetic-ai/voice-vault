import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const root = path.join(path.dirname(fileURLToPath(import.meta.url)), "..");
const source = (name) => fs.readFileSync(path.join(root, "android/src/ai/hypermemetic/voicevault", name), "utf8");
const overlay = source("FloatingPillOverlay.java");

test("state transitions retain DICT through recording, processing, feedback expiry and cancel", () => {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "vv-pill-"));
  try {
    const java = `package ai.hypermemetic.voicevault;
public class PillCheck {
  static void check(boolean valid) { if (!valid) throw new AssertionError(); }
  public static void main(String[] args) {
    FloatingPillState s = new FloatingPillState();
    check(!s.isVisible());
    s.setDictationMode(true);
    check(s.isVisible() && s.phase() == FloatingPillState.Phase.IDLE);
    s.recording(); s.timer("03:21");
    check(s.isVisible() && s.isDictationMode() && s.text().equals("03:21"));
    s.processing(); s.timer("04:00");
    check(s.isVisible() && s.text().equals("Processing"));
    s.success("✓ Copied");
    check(s.isVisible() && s.text().equals("✓ Copied"));
    s.idle(); // scheduled feedback expiry
    check(s.isVisible() && s.isDictationMode() && s.text().isEmpty());
    s.recording(); s.setDictationMode(false);
    check(s.isVisible() && !s.isDictationMode());
    s.success("Error"); check(s.isVisible());
    s.idle(); check(!s.isVisible());
    s.setDictationMode(true); s.recording(); s.idle(); // cancel
    check(s.isVisible() && s.phase() == FloatingPillState.Phase.IDLE);
    s.setDictationMode(false); check(!s.isVisible());
  }
}`;
    const checkFile = path.join(tmp, "PillCheck.java");
    fs.writeFileSync(checkFile, java);
    execFileSync("javac", ["-d", tmp, path.join(root, "android/src/ai/hypermemetic/voicevault/FloatingPillState.java"), checkFile]);
    execFileSync("java", ["-cp", tmp, "ai.hypermemetic.voicevault.PillCheck"]);
  } finally {
    fs.rmSync(tmp, { recursive: true, force: true });
  }
});

test("persisted mode is refreshed at tile toggle, tile listening, accessibility reconnect, and dashboard resume", () => {
  const tile = source("VoiceVaultTileService.java");
  const key = source("VoiceVaultKeyService.java");
  const main = source("MainActivity.java");
  assert.match(overlay, /getSharedPreferences\(PREFS_NAME, Context.MODE_PRIVATE\)[\s\S]*?getBoolean\(PREF_DICTATION_MODE, false\)/);
  assert.match(tile, /setDictationModeEnabled\(enabled\);\s*FloatingPillOverlay\.refreshMode\(this\)/);
  assert.match(tile, /void onStartListening\(\)[\s\S]*?updateTileState\(\);\s*FloatingPillOverlay\.refreshMode\(this\)/);
  assert.match(key, /void onServiceConnected\(\)[\s\S]*?FloatingPillOverlay\.refreshMode\(this\)/);
  assert.match(key, /registerOnSharedPreferenceChangeListener\(mModeListener\)/);
  assert.match(key, /PREF_DICTATION_MODE\.equals\(key\)\) FloatingPillOverlay\.refreshMode\(this\)/);
  assert.match(key, /unregisterOnSharedPreferenceChangeListener\(mModeListener\)/);
  assert.match(main, /void onResume\(\)[\s\S]*?FloatingPillOverlay\.refreshMode\(this\)/);
});

test("pill is display-only, pass-through, permission gated, and feedback expiry renders idle", () => {
  const xml = fs.readFileSync(path.join(root, "android/res/layout/overlay_floating_pill.xml"), "utf8");
  assert.match(xml, /android:text="DICT"/);
  assert.doesNotMatch(xml, /android:clickable="true"|android:onClick=/);
  assert.doesNotMatch(overlay, /setOnClickListener|ACTION_STOP|startService|startForegroundService/);
  assert.match(overlay, /FLAG_NOT_TOUCHABLE/);
  assert.match(overlay, /params\.alpha = 0\.7f/);
  assert.match(overlay, /Settings\.canDrawOverlays\(sContext\)/);
  assert.match(overlay, /sState\.idle\(\);\s*render\(\);/);
  assert.match(overlay, /removeCallbacks\(sDismissRunnable\)/);
  assert.match(overlay, /sModeText\.setVisibility\(sState\.isDictationMode\(\)/);
});
