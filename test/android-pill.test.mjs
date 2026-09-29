import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.join(path.dirname(fileURLToPath(import.meta.url)), "..");
const source = (name) => fs.readFileSync(path.join(root, "android/src/ai/hypermemetic/voicevault", name), "utf8");
const overlay = source("FloatingPillOverlay.java");

test("persistent mode is independent of FloatingStatus and its feedback expiry", () => {
  assert.match(overlay, /sStatus\.snapshot\(now\)/);
  assert.match(overlay, /getSharedPreferences\(PREFS_NAME, Context.MODE_PRIVATE\)\s*\.getBoolean\(PREF_DICTATION_MODE, false\)/);
  assert.match(overlay, /status\.kind == FloatingStatus\.Kind\.IDLE && !mode/);
  assert.match(overlay, /sModeText\.setVisibility\(mode \? View.VISIBLE : View.GONE\)/);
  assert.match(overlay, /status\.expiresAt > now\) sMainHandler\.postDelayed\(sRefresh, status\.expiresAt - now\)/);
  assert.match(overlay, /sStatus\.feedback\(message, enabled, SystemClock\.uptimeMillis\(\)\)/);
  assert.match(overlay, /idle \? 28 : 40/);
  assert.match(overlay, /modeParams\.setMarginStart\(idle \? 0/);
});

test("mode is refreshed at tile toggle/listening, accessibility reconnect/change, dashboard resume", () => {
  const tile = source("VoiceVaultTileService.java");
  const key = source("VoiceVaultKeyService.java");
  const main = source("MainActivity.java");
  assert.match(tile, /setDictationModeEnabled\(enabled\);\s*FloatingPillOverlay\.refreshMode\(this\)/);
  assert.match(tile, /void onStartListening\(\)[\s\S]*?updateTileState\(\);\s*FloatingPillOverlay\.refreshMode\(this\)/);
  assert.match(key, /void onServiceConnected\(\)[\s\S]*?FloatingPillOverlay\.refreshMode\(this\)/);
  assert.match(key, /registerOnSharedPreferenceChangeListener\(mModeListener\)/);
  assert.match(key, /PREF_DICTATION_MODE\.equals\(key\)\) FloatingPillOverlay\.refreshMode\(this\)/);
  assert.match(key, /unregisterOnSharedPreferenceChangeListener\(mModeListener\)/);
  assert.match(key, /getApplicationRoot\(\)[\s\S]*?TYPE_INPUT_METHOD[\s\S]*?TYPE_APPLICATION && window\.isFocused\(\)/);
  assert.match(main, /void onResume\(\)[\s\S]*?FloatingPillOverlay\.refreshMode\(this\)/);
});

test("pill is compact, display-only and permission gated; bubble remains separate", () => {
  const xml = fs.readFileSync(path.join(root, "android/res/layout/overlay_floating_pill.xml"), "utf8");
  assert.match(xml, /android:text="DICT"/);
  assert.doesNotMatch(xml, /android:clickable="true"|android:onClick=/);
  assert.doesNotMatch(overlay, /setOnClickListener|ACTION_STOP|startService|startForegroundService/);
  assert.match(overlay, /FLAG_NOT_TOUCHABLE/);
  assert.match(overlay, /params\.alpha = 0\.7f/);
  assert.match(overlay, /Settings\.canDrawOverlays\(sContext\)/);
  assert.match(source("FloatingBubbleService.java"), /setOnTouchListener|setOnClickListener/);
});

test("no native Orca-specific insertion, framing or setting ships", () => {
  for (const file of ["VoiceVaultKeyService.java", "MainActivity.java"]) {
    assert.doesNotMatch(source(file), /Orca|orca|bracketed_paste_submit/);
  }
  const layout = fs.readFileSync(path.join(root, "android/res/layout/activity_main.xml"), "utf8");
  assert.doesNotMatch(layout, /Orca|bracketed_submit/);
  for (const file of ["OrcaAccessibilityComposer.java", "OrcaComposerFlow.java", "OrcaSubmitFraming.java"]) {
    assert.ok(!fs.existsSync(path.join(root, "android/src/ai/hypermemetic/voicevault", file)));
  }
});
