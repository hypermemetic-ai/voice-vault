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
  assert.match(overlay, /boolean idle = status\.kind == FloatingStatus\.Kind\.IDLE/);
  assert.match(overlay, /sModeText\.setVisibility\(mode && idle \? View.VISIBLE : View.GONE\)/);
  assert.match(overlay, /status\.expiresAt > now\) sMainHandler\.postDelayed\(sRefresh, status\.expiresAt - now\)/);
  assert.match(overlay, /sStatus\.feedback\(message, enabled, SystemClock\.uptimeMillis\(\)\)/);
  assert.match(overlay, /idle \? 28 : 40/);
  assert.match(overlay, /sTvText\.setVisibility\(idle \? View.GONE : View.VISIBLE\)/);
});

test("mode is refreshed at tile toggle/listening, accessibility reconnect/change, dashboard resume", () => {
  const tile = source("VoiceVaultTileService.java");
  const key = source("VoiceVaultKeyService.java");
  const main = source("MainActivity.java");
  assert.match(tile, /setDictationModeEnabled\(enabled\);\s*FloatingPillOverlay\.refreshMode\(this\)/);
  assert.match(tile, /void onStartListening\(\)[\s\S]*?updateTileState\(\);\s*FloatingPillOverlay\.refreshMode\(this\)/);
  assert.match(key, /void onServiceConnected\(\)[\s\S]*?FloatingPillOverlay\.refreshMode\(this\)/);
  assert.match(key, /registerOnSharedPreferenceChangeListener\(mModeListener\)/);
  assert.match(key, /PREF_DICTATION_MODE\.equals\(key\)\) \{\s*cancelPendingKeyCallbacks\(\);\s*FloatingPillOverlay\.refreshMode\(this\)/);
  assert.match(key, /unregisterOnSharedPreferenceChangeListener\(mModeListener\)/);
  assert.match(key, /getApplicationRoot\(\)[\s\S]*?TYPE_APPLICATION && window\.isFocused\(\)/);
  assert.match(main, /void onResume\(\)[\s\S]*?FloatingPillOverlay\.refreshMode\(this\)/);
});

test("pill is compact, display-only and permission gated; bubble remains separate", () => {
  const xml = fs.readFileSync(path.join(root, "android/res/layout/overlay_floating_pill.xml"), "utf8");
  assert.match(xml, /android:text="DICT"/);
  assert.doesNotMatch(xml, /pill_mode[\s\S]*?android:layout_marginStart=/);
  assert.doesNotMatch(xml, /android:clickable="true"|android:onClick=/);
  assert.doesNotMatch(overlay, /setOnClickListener|ACTION_STOP|startService|startForegroundService/);
  assert.match(overlay, /FLAG_NOT_TOUCHABLE/);
  assert.match(overlay, /params\.alpha = 0\.79f/);
  assert.match(overlay, /Settings\.canDrawOverlays\(sContext\)/);
  assert.match(source("FloatingBubbleService.java"), /setOnTouchListener|setOnClickListener/);
});

test("pill backdrop and text stay legible without exceeding touch-through opacity", () => {
  const drawable = fs.readFileSync(path.join(root, "android/res/drawable/bg_floating_square.xml"), "utf8");
  const xml = fs.readFileSync(path.join(root, "android/res/layout/overlay_floating_pill.xml"), "utf8");
  const color = drawable.match(/<solid android:color="#([0-9a-fA-F]{8})"\s*\/>/);
  const alpha = Number(overlay.match(/params\.alpha = (0\.\d+)f;/)?.[1]);
  assert.ok(color, "backdrop must specify ARGB color");
  assert.equal(color[1].slice(0, 2).toUpperCase(), "FF", "no compounded drawable translucency");
  assert.ok(alpha > 0 && alpha <= 0.8, "untrusted non-touchable overlay must pass touches on Android 12+");
  const backdrop = [2, 4, 6].map((i) => parseInt(color[1].slice(i, i + 2), 16));
  assert.ok(backdrop.every((channel) => channel <= 48), "backdrop remains dark");
  assert.match(xml, /android:id="@\+id\/pill_mode"[\s\S]*?android:textColor="#FFFFFF"/);
  assert.match(overlay, /Color\.parseColor\("#4ADE80"\) : Color\.WHITE/);

  // The window alpha composites the *whole* rendered pill over the underlying app.
  const blend = (front, behind) => front.map((v, i) => alpha * v + (1 - alpha) * behind[i]);
  const luminance = (rgb) => {
    const [r, g, b] = rgb.map((v) => {
      const s = v / 255;
      return s <= 0.04045 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
    });
    return 0.2126 * r + 0.7152 * g + 0.0722 * b;
  };
  const contrast = (a, b) => {
    const [lighter, darker] = [luminance(a), luminance(b)].sort((x, y) => y - x);
    return (lighter + 0.05) / (darker + 0.05);
  };
  for (const behind of [[255, 255, 255], [0, 0, 0], [255, 0, 255], [0, 255, 0]]) {
    const pill = blend(backdrop, behind);
    for (const text of [[255, 255, 255], [74, 222, 128]]) {
      assert.ok(contrast(blend(text, behind), pill) >= 4.5,
        `normal-sized text ${text} should have >= 4.5:1 contrast over ${behind}`);
    }
  }
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
