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
const SETUP_ACTIVITY = "android/src/ai/hypermemetic/voicevault/SetupActivity.java";
const SETUP_DIAGNOSTICS = "android/src/ai/hypermemetic/voicevault/SetupDiagnostics.java";
const SETUP_LAYOUT = "android/res/layout/activity_setup.xml";

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

test("auto-send on paste preference defaults to true in UI and service", () => {
  const activity = read(MAIN_ACTIVITY);
  const layout = read(LAYOUT);
  const keyService = read(KEY_SERVICE);
  assert.ok(
    activity.includes('prefs.getBoolean("pref_auto_send_on_paste", true)'),
    "MainActivity.java must default pref_auto_send_on_paste to true",
  );
  assert.ok(
    keyService.includes("prefs.getBoolean(PREF_AUTO_SEND, true)"),
    "VoiceVaultKeyService.java must default PREF_AUTO_SEND to true",
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
    manifest.includes('android:versionCode="18"'),
    "expected versionCode 18",
  );
  assert.ok(
    manifest.includes('android:versionName="1.2.14"'),
    "expected versionName 1.2.14",
  );
});

test("VoiceVaultKeyService implements auto-focus, 0ms block insert, and send button auto-send", () => {
  const source = read(KEY_SERVICE);

  // Auto-focus target field via findEditableNode DFS
  assert.ok(
    source.includes("findFocus") && source.includes("!target.isEditable() && !isEditClass"),
    "findFocus result must be verified as editable and discarded if non-editable",
  );
  assert.ok(
    source.includes("findEditableNode("),
    "VoiceVaultKeyService must define findEditableNode",
  );
  assert.ok(
    source.includes("node.isEditable()") && source.includes('.contains("EditText")'),
    "findEditableNode must match isEditable and EditText className",
  );
  assert.ok(
    source.includes("target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)"),
    "pasteIntoFocusedField must auto-focus discovered editable field",
  );

  // 0ms block text insertion via ACTION_SET_TEXT with ACTION_PASTE fallback
  assert.ok(
    source.includes("ACTION_SET_TEXT"),
    "VoiceVaultKeyService must use ACTION_SET_TEXT",
  );
  assert.ok(
    source.includes("ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE"),
    "VoiceVaultKeyService must pass ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE",
  );
  assert.ok(
    source.includes("ClipboardManager"),
    "VoiceVaultKeyService must read clipboard content",
  );
  assert.ok(
    source.includes("ACTION_PASTE"),
    "VoiceVaultKeyService must retain ACTION_PASTE fallback",
  );

  // Auto-send: findAndClickSendButton and clickable parent traversal
  assert.ok(
    source.includes("findAndClickSendButton("),
    "VoiceVaultKeyService must define findAndClickSendButton",
  );
  for (const keyword of ["send", "submit", "send message", "send sms"]) {
    assert.ok(
      source.toLowerCase().includes(keyword),
      `VoiceVaultKeyService must search for send keyword: ${keyword}`,
    );
  }
  assert.ok(
    source.includes("getContentDescription") && source.includes("getText") && source.includes("getViewIdResourceName"),
    "findAndClickSendButton must check contentDescription, text, and view ID",
  );
  assert.ok(
    source.includes("getParent()") && source.includes("isClickable()"),
    "findAndClickSendButton must traverse up to clickable parent wrappers",
  );
  assert.ok(
    source.includes("IME_ENTER_DELAY_MS") && source.includes("ACTION_IME_ENTER"),
    "auto-send must dispatch ACTION_IME_ENTER with delay",
  );
  assert.ok(
    source.includes("CODEX_EXEC_DELAY_MS") && source.includes("stage2"),
    "auto-send must provide Stage 2 follow-up dispatch for Codex/terminal execution",
  );
  assert.ok(
    source.includes("recycle()"),
    "VoiceVaultKeyService must safely recycle traversed accessibility nodes",
  );
});

test("SetupDiagnostics inspects real system states and builds deep links", () => {
  const source = read(SETUP_DIAGNOSTICS);
  for (const token of [
    "isMicGranted",
    "isAccessibilityEnabled",
    "isOverlayGranted",
    "isBatteryOptimizationIgnored",
    "isNotificationBlocked",
    "ENABLED_ACCESSIBILITY_SERVICES",
    "VoiceVaultKeyService",
    "Settings.canDrawOverlays",
    "isIgnoringBatteryOptimizations",
    "RECORD_AUDIO",
    "areNotificationsEnabled",
    "ACTION_ACCESSIBILITY_SETTINGS",
    "ACTION_MANAGE_OVERLAY_PERMISSION",
    "ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
    "ACTION_APPLICATION_DETAILS_SETTINGS",
    "ActivityNotFoundException",
    "requestAddTileService",
  ]) {
    assert.ok(source.includes(token), `SetupDiagnostics.java must reference ${token}`);
  }
});

test("SetupActivity renders six live cards and refreshes on resume", () => {
  const source = read(SETUP_ACTIVITY);
  const layout = read(SETUP_LAYOUT);
  for (const token of [
    "onResume",
    "refreshStatuses",
    "OPEN ACCESSIBILITY SETTINGS",
    "GRANT PERMISSION",
    "OPEN OVERLAY SETTINGS",
    "DISABLE BATTERY OPTIMIZATION",
    "ADD TILE TO QUICK SETTINGS",
    "NOTIFICATION SETTINGS",
    "Allow restricted settings",
    "Blocked (Recommended)",
  ]) {
    assert.ok(source.includes(token), `SetupActivity.java must reference ${token}`);
  }
  assert.ok(
    layout.includes("layout_setup_cards"),
    "activity_setup.xml must contain the cards container",
  );
  assert.ok(
    layout.includes("SETUP"),
    "activity_setup.xml must contain the guide header",
  );
});

test("MainActivity exposes setup guide entry and critical warning banner", () => {
  const activity = read(MAIN_ACTIVITY);
  const layout = read(LAYOUT);
  for (const token of [
    "SetupActivity",
    "banner_setup_warning",
    "refreshSetupBanner",
    "Dictation keys unavailable",
    "btn_open_setup",
  ]) {
    assert.ok(activity.includes(token), `MainActivity.java must reference ${token}`);
  }
  for (const token of ["btn_open_setup", "banner_setup_warning", "tv_setup_warning"]) {
    assert.ok(layout.includes(token), `activity_main.xml must contain ${token}`);
  }
});

test("manifest declares SetupActivity and battery permission", () => {
  const manifest = read(MANIFEST);
  assert.ok(
    manifest.includes('android:name=".SetupActivity"'),
    "manifest must declare SetupActivity",
  );
  assert.ok(
    manifest.includes("android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"),
    "manifest must request REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
  );
});
