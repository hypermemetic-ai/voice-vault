import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), "..");
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), "utf8");

const MAIN_ACTIVITY = "android/src/ai/hypermemetic/voicevault/MainActivity.java";
const SERVICE = "android/src/ai/hypermemetic/voicevault/VoiceVaultService.java";
const KEY_SERVICE = "android/src/ai/hypermemetic/voicevault/VoiceVaultKeyService.java";
const TILE_SERVICE = "android/src/ai/hypermemetic/voicevault/VoiceVaultTileService.java";
const MANIFEST = "android/AndroidManifest.xml";
const LAYOUT = "android/res/layout/activity_main.xml";

test("MainActivity does not prompt for POST_NOTIFICATIONS", () => {
  const source = read(MAIN_ACTIVITY);
  assert.ok(
    !source.includes("POST_NOTIFICATIONS"),
    "MainActivity.java must not reference POST_NOTIFICATIONS",
  );
  assert.ok(
    source.includes("RECORD_AUDIO"),
    "MainActivity.java must still request RECORD_AUDIO",
  );
});

test("recording notification channel is silent (IMPORTANCE_MIN)", () => {
  const source = read(SERVICE);
  assert.ok(
    source.includes("NotificationManager.IMPORTANCE_MIN"),
    "VoiceVaultService.java must create the channel with IMPORTANCE_MIN",
  );
  assert.ok(
    !source.includes("IMPORTANCE_HIGH") && !source.includes("IMPORTANCE_DEFAULT"),
    "VoiceVaultService.java must not use a noisy channel importance",
  );
});

test("1Hz notification rebuild loop is eliminated, alert-once retained", () => {
  const source = read(SERVICE);
  assert.ok(
    source.includes("setOnlyAlertOnce(true)"),
    "recording notification builder must keep setOnlyAlertOnce(true)",
  );
  const timerStart = source.indexOf("mTimerRunnable = new Runnable()");
  assert.ok(timerStart >= 0, "expected mTimerRunnable to exist");
  const timerBlock = source.slice(timerStart, source.indexOf("mHandler.postDelayed(mTimerRunnable", timerStart));
  assert.ok(
    !timerBlock.includes("nm.notify") && !timerBlock.includes(".notify("),
    "mTimerRunnable must not rebuild the notification every second",
  );
  // The service must still enter the foreground (OS microphone requirement).
  assert.ok(
    source.includes("startForeground(NOTIFICATION_ID"),
    "VoiceVaultService must still call startForeground",
  );
});

test("Quick Settings tile toggles dictation mode", () => {
  const source = read(TILE_SERVICE);
  assert.ok(
    source.includes("pref_dictation_mode_enabled"),
    "tile must toggle pref_dictation_mode_enabled",
  );
  assert.ok(
    source.includes("Tile.STATE_ACTIVE") && source.includes("Tile.STATE_INACTIVE"),
    "tile must sync STATE_ACTIVE / STATE_INACTIVE",
  );
});

test("volume keys honor dictation mode (up: record toggle, down: paste)", () => {
  const source = read(KEY_SERVICE);
  assert.ok(
    !source.includes("DOUBLE_PRESS_WINDOW_MS") && !source.includes("mLastVolumeDownTime"),
    "old 450ms double-tap listener must be removed",
  );
  for (const token of [
    "pref_dictation_mode_enabled",
    "KEYCODE_VOLUME_UP",
    "KEYCODE_VOLUME_DOWN",
    "ACTION_PASTE",
    "ACTION_IME_ENTER",
    "pref_auto_send_on_paste",
    "autoDismissClipboardOverlay",
  ]) {
    assert.ok(source.includes(token), `VoiceVaultKeyService.java must reference ${token}`);
  }
});

test("auto-send on paste preference is exposed in UI and code", () => {
  const activity = read(MAIN_ACTIVITY);
  const layout = read(LAYOUT);
  assert.ok(
    activity.includes("pref_auto_send_on_paste"),
    "MainActivity.java must bind pref_auto_send_on_paste",
  );
  assert.ok(
    /auto.send/i.test(layout),
    "activity_main.xml must contain the auto-send setting",
  );
});

test("manifest wires tile long-press and bumps version", () => {
  const manifest = read(MANIFEST);
  assert.ok(
    manifest.includes("android.service.quicksettings.action.QS_TILE_PREFERENCES"),
    "MainActivity must declare QS_TILE_PREFERENCES",
  );
  assert.ok(
    manifest.includes('android:versionCode="16"'),
    "expected versionCode 16",
  );
  assert.ok(
    manifest.includes('android:versionName="1.2.12"'),
    "expected versionName 1.2.12",
  );
});
