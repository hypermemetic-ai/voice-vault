package ai.hypermemetic.voicevault;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.service.quicksettings.TileService;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.List;
import java.util.Locale;

/**
 * Hardware volume-key controller for Dictation Mode.
 *
 * <ul>
 *   <li>Dictation Mode OFF:
 *     <ul>
 *       <li>Single Volume Up & Down: pass through to the system normally.</li>
 *       <li>Double-press Volume Up: enables Dictation Mode (replaces tapping the tile).</li>
 *     </ul>
 *   </li>
 *   <li>Dictation Mode ON:
 *     <ul>
 *       <li>Single Volume Up: toggles recording (start/stop + Whisper transcription).</li>
 *       <li>Double-press Volume Up: disables Dictation Mode.</li>
 *       <li>Single Volume Down: stops/waits for transcription, then inserts (and auto-sends if enabled).</li>
 *       <li>Double Volume Down toggles auto-send; the dashboard checkbox shows the same setting.</li>
 *     </ul>
 *     Both keys are consumed so the system volume never changes.
 *   </li>
 * </ul>
 *
 * Also provides accessibility capabilities to auto-dismiss the SystemUI
 * clipboard overlay so it doesn't block bottom-left UI buttons.
 */
public class VoiceVaultKeyService extends AccessibilityService {
    private static final String TAG = "VoiceVaultKey";
    private static final String PREFS_NAME = "voice_vault_prefs";
    private static final String PREF_DICTATION_MODE = "pref_dictation_mode_enabled";
    private static final String PREF_AUTO_SEND = "pref_auto_send_on_paste";
    private static final long IME_ENTER_DELAY_MS = 60;
    private static final long FOLLOWUP_SEND_DELAY_MS = 240;

    private static volatile VoiceVaultKeyService sInstance = null;

    private final Handler mKeyHandler = new Handler(Looper.getMainLooper());
    private final VolumeUpTiming mVolUpTiming = new VolumeUpTiming();
    private final VolumeUpTiming mVolDnTiming = new VolumeUpTiming();
    private Runnable mPendingVolDnRunnable;
    private boolean mConsumedUp, mConsumedDown;
    private int mKeyGeneration;
    private Runnable mPendingVolUpRunnable = null;
    private final PendingDictation mPendingDictation = new PendingDictation();
    private AccessibilityNodeInfo mPendingComposer;
    private long mPendingPaseoId = -1;
    private int mPaseoGeneration = 0;
    private AccessibilityNodeInfo mFlowComposer;
    private PaseoSelection.Gate mFlowGate;
    private String mFlowExpected;
    private int mFlowWindow = -1;
    private String mFlowPackage;
    private Runnable mPaseoCheck;
    private long mPaseoDeadline;
    private boolean mFlowDispatched;
    private static final long PASEO_READY_TIMEOUT_MS = 2000L;
    private SharedPreferences mModePrefs;
    private final SharedPreferences.OnSharedPreferenceChangeListener mModeListener = (prefs, key) -> {
        if (PREF_DICTATION_MODE.equals(key)) {
            cancelPendingKeyCallbacks();
            FloatingPillOverlay.refreshMode(this);
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        sInstance = this;
        stopWatchingMode();
        cancelPendingKeyCallbacks();
        mModePrefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        mModePrefs.registerOnSharedPreferenceChangeListener(mModeListener);
        FloatingPillOverlay.refreshMode(this);
        Log.i(TAG, "Accessibility service connected");
    }

    @Override
    public boolean onUnbind(Intent intent) {
        if (sInstance == this) {
            sInstance = null;
        }
        stopWatchingMode();
        cancelPendingKeyCallbacks();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        if (sInstance == this) {
            sInstance = null;
        }
        stopWatchingMode();
        cancelPendingKeyCallbacks();
        super.onDestroy();
    }

    private void stopWatchingMode() {
        if (mModePrefs != null) {
            mModePrefs.unregisterOnSharedPreferenceChangeListener(mModeListener);
            mModePrefs = null;
        }
    }

    private void cancelPendingKeyCallbacks() {
        clearPendingComposer();
        mPendingPaseoId = -1;
        mPaseoGeneration++;
        clearFlowComposer();
        mPendingDictation.cancel();
        mVolUpTiming.reset();
        mVolDnTiming.reset();
        mKeyGeneration++;
        if (mPendingVolDnRunnable != null) mKeyHandler.removeCallbacks(mPendingVolDnRunnable);
        mPendingVolDnRunnable = null;
        if (mPendingVolUpRunnable != null) {
            mKeyHandler.removeCallbacks(mPendingVolUpRunnable);
            mPendingVolUpRunnable = null;
        }

    }

    /**
     * Automatically dismisses Android's bottom-left system clipboard overlay popup
     * immediately after text is copied, preventing it from obscuring bottom-left screen controls.
     */
    public static void autoDismissClipboardOverlay() {
        final VoiceVaultKeyService service = sInstance;
        if (service == null) return;

        Handler handler = new Handler(Looper.getMainLooper());
        for (int delay : new int[]{60, 150, 300, 550, 900}) {
            handler.postDelayed(() -> service.dismissOverlayNode(), delay);
        }
    }

    private void dismissOverlayNode() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                List<AccessibilityWindowInfo> windows = getWindows();
                if (windows != null) {
                    for (AccessibilityWindowInfo window : windows) {
                        AccessibilityNodeInfo root = window.getRoot();
                        if (root != null) {
                            if (tryDismissFromRoot(root)) {
                                root.recycle();
                                return;
                            }
                            root.recycle();
                        }
                    }
                }
            }

            AccessibilityNodeInfo activeRoot = getRootInActiveWindow();
            if (activeRoot != null) {
                tryDismissFromRoot(activeRoot);
                activeRoot.recycle();
            }
        } catch (Throwable ignored) {}
    }

    private boolean tryDismissFromRoot(AccessibilityNodeInfo root) {
        if (root == null) return false;
        List<AccessibilityNodeInfo> dismissNodes = root.findAccessibilityNodeInfosByViewId("com.android.systemui:id/dismiss_button");
        if (dismissNodes != null && !dismissNodes.isEmpty()) {
            for (AccessibilityNodeInfo node : dismissNodes) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                node.recycle();
            }
            return true;
        }

        List<AccessibilityNodeInfo> descNodes = root.findAccessibilityNodeInfosByText("Dismiss clipboard");
        if (descNodes != null && !descNodes.isEmpty()) {
            for (AccessibilityNodeInfo node : descNodes) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                node.recycle();
            }
            return true;
        }
        return false;
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        boolean isVolumeKey = keyCode == KeyEvent.KEYCODE_VOLUME_UP
                || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN;
        if (!isVolumeKey) {
            return super.onKeyEvent(event);
        }

        if (event.getAction() == KeyEvent.ACTION_UP) {
            boolean consumed = keyCode == KeyEvent.KEYCODE_VOLUME_UP ? mConsumedUp : mConsumedDown;
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) mConsumedUp = false; else mConsumedDown = false;
            if (consumed) return true;
        }
        boolean dictationActive = isDictationModeEnabled();
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() > 0) {
            return dictationActive || super.onKeyEvent(event);
        }

        // 1. When Dictation Mode is OFF:
        // Double-pressing Volume Up toggles Dictation Mode ON (replaces tapping the tile).
        // Single Volume Up and all Volume Down presses pass through to the system.
        if (!dictationActive) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    long now = SystemClock.uptimeMillis();
                    if (mVolUpTiming.inactivePress(now)) {
                        Log.i(TAG, "Dictation Mode OFF: Vol Up double-press -> ENABLE dictation mode");
                        setDictationModeEnabled(true);
                        notifyTileStateChanged();
                        provideFeedback(true, "Dictation Mode: ON");
                        mConsumedUp = true;
                        return true; // Consume second press and its release
                    } else {
                        return super.onKeyEvent(event);
                    }
                }
                return super.onKeyEvent(event);
            }
            return super.onKeyEvent(event);
        }

        // 2. When Dictation Mode is ON:
        // Intercept volume keys so system volume never changes.
        if (event.getAction() == KeyEvent.ACTION_UP) {
            return true;
        }

        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) mConsumedUp = true; else mConsumedDown = true;
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                VolumeUpTiming.ActivePress press = mVolUpTiming.activePress(SystemClock.uptimeMillis());
                if (press == VolumeUpTiming.ActivePress.DOUBLE) {
                    // A double is valid only by uptime, not by a late queued callback.
                    mKeyHandler.removeCallbacks(mPendingVolUpRunnable);
                    mPendingVolUpRunnable = null;
                    Log.i(TAG, "Dictation Mode ON: Vol Up double-press -> DISABLE dictation mode");
                    if (VoiceVaultService.isRecording()) {
                        stopRecordingService();
                    }
                    setDictationModeEnabled(false);
                    cancelPendingKeyCallbacks();
                    notifyTileStateChanged();
                    provideFeedback(false, "Dictation Mode: OFF");
                    return true;
                }
                if (press == VolumeUpTiming.ActivePress.EXPIRED_FIRST) {
                    // Handler may be late: honor the first single before starting a new window.
                    mKeyHandler.removeCallbacks(mPendingVolUpRunnable);
                    mPendingVolUpRunnable = null;
                    Log.i(TAG, "Dictation Mode: expired Vol Up single-press -> toggle recording");
                    toggleDictation();
                }
                // Run just after the inclusive 220ms boundary, so a press at 220ms can win.
                Runnable single = new Runnable() {
                    @Override public void run() {
                        if (mPendingVolUpRunnable != this) return;
                        mPendingVolUpRunnable = null;
                        mVolUpTiming.activeSingleFinished();
                        if (!isDictationModeEnabled()) return;
                        Log.i(TAG, "Dictation Mode: Vol Up single-press -> toggle recording");
                        toggleDictation();
                    }
                };
                mPendingVolUpRunnable = single;
                mKeyHandler.postDelayed(single, VolumeUpTiming.UP_WINDOW_MS + 1);
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                VolumeUpTiming.ActivePress press = mVolDnTiming.activePress(SystemClock.uptimeMillis());
                if (press == VolumeUpTiming.ActivePress.DOUBLE) {
                    mKeyHandler.removeCallbacks(mPendingVolDnRunnable);
                    mPendingVolDnRunnable = null;
                    final int generation = mKeyGeneration;
                    mKeyHandler.post(() -> {
                        if (generation != mKeyGeneration || !isDictationModeEnabled()) return;
                        boolean enabled = !isAutoSendEnabled();
                        setAutoSendEnabled(enabled);
                        provideFeedback(enabled, "Auto-send: " + (enabled ? "ON" : "OFF"));
                    });
                    return true;
                }
                if (press == VolumeUpTiming.ActivePress.EXPIRED_FIRST) {
                    mKeyHandler.removeCallbacks(mPendingVolDnRunnable);
                    final int generation = mKeyGeneration;
                    mKeyHandler.post(() -> {
                        if (generation == mKeyGeneration && isDictationModeEnabled()) finishAndInsert();
                    });
                }
                Runnable single = new Runnable() {
                    @Override public void run() {
                        if (mPendingVolDnRunnable != this) return;
                        mPendingVolDnRunnable = null;
                        mVolDnTiming.activeSingleFinished();
                        if (isDictationModeEnabled()) finishAndInsert();
                    }
                };
                mPendingVolDnRunnable = single;
                mKeyHandler.postDelayed(single, VolumeUpTiming.UP_WINDOW_MS + 1);
                return true;
            }
        }

        return true;
    }

    private void stopRecordingService() {
        try {
            Intent intent = new Intent(this, VoiceVaultService.class);
            intent.setAction(VoiceVaultService.ACTION_STOP);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
        } catch (Throwable t) {
            mPendingDictation.cancel();
            clearPendingComposer();
            Log.w(TAG, "Failed to stop recording service", t);
        }
    }

    private void setDictationModeEnabled(boolean enabled) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefs.edit().putBoolean(PREF_DICTATION_MODE, enabled).apply();
    }

    private void notifyTileStateChanged() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                TileService.requestListeningState(
                        this,
                        new ComponentName(this, VoiceVaultTileService.class)
                );
            } catch (Throwable t) {
                Log.w(TAG, "Failed to request tile listening state", t);
            }
        }
    }

    private void provideFeedback(boolean positive, String message) {
        FloatingPillOverlay.showFeedback(this, message, positive);

        try {
            if (positive) {
                SoundEffects.playStartPop();
            } else {
                SoundEffects.playSuccessChime();
            }
        } catch (Throwable ignored) {}

        try {
            Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    if (positive) {
                        long[] timings = new long[]{0, 35, 45, 45};
                        int[] amplitudes = new int[]{0, 180, 0, 255};
                        vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1));
                    } else {
                        vibrator.vibrate(VibrationEffect.createOneShot(65, VibrationEffect.DEFAULT_AMPLITUDE));
                    }
                } else {
                    vibrator.vibrate(positive ? 90 : 50);
                }
            }
        } catch (Throwable ignored) {}
    }

    /** Called on the main thread after the exact recording result is saved/copied. */
    public static void onTranscriptionFinished(long recordingId, String text) {
        VoiceVaultKeyService service = sInstance;
        if (service == null) return;
        service.mKeyHandler.post(() -> service.insertCompletedRecording(recordingId, text));
    }

    private void finishAndInsert() {
        if (!isDictationModeEnabled()) return;
        if (!VoiceVaultService.isRecording() && !VoiceVaultService.isProcessing()) {
            if (mPendingDictation.isPending()) return;
            pasteIntoFocusedField();
            return;
        }
        if (mPendingDictation.isPending()) return;
        AccessibilityNodeInfo root = getApplicationRoot();
        try {
            mPendingDictation.cancel();
            clearPendingComposer();
            mPendingPaseoId = -1;
            if (root != null && root.getPackageName() != null) {
                String pkg = root.getPackageName().toString();
                mPendingDictation.begin(VoiceVaultService.getRecordingStartTime(), pkg, root.getWindowId());
                if (PaseoSelection.isPaseo(pkg)) {
                    mPendingPaseoId = VoiceVaultService.getRecordingStartTime();
                    try (PaseoTree tree = new PaseoTree(root)) {
                        PaseoSelection.Node editor = PaseoSelection.composer(tree.root);
                        if (editor != null) mPendingComposer = AccessibilityNodeInfo.obtain((AccessibilityNodeInfo) editor.handle);
                    }
                }
            }
        } finally {
            if (root != null) root.recycle();
        }
        if (VoiceVaultService.isRecording()) stopRecordingService();
    }

    private void insertCompletedRecording(long recordingId, String text) {
        if (!isDictationModeEnabled()) {
            mPendingDictation.cancel();
            return;
        }
        if (mPendingPaseoId != -1 && recordingId != mPendingPaseoId) return;
        AccessibilityNodeInfo root = getApplicationRoot();
        try {
            String packageName = root == null || root.getPackageName() == null
                    ? null : root.getPackageName().toString();
            int windowId = root == null ? -1 : root.getWindowId();
            boolean sameComposer = true;
            boolean paseo = PaseoSelection.isPaseo(packageName);
            if (paseo) {
                // This input was uniquely validated at recording completion request.
                // Refresh native identity and focus, not the entire chat history.
                sameComposer = currentComposer(root, mPendingComposer) != null;
                if (!sameComposer && root != null && mPendingComposer != null) {
                    try (PaseoTree tree = new PaseoTree(root)) {
                        PaseoSelection.Node editor = PaseoSelection.composer(tree.root);
                        sameComposer = editor != null && mPendingComposer.equals(editor.handle);
                    }
                }
            }
            String result = mPendingDictation.complete(recordingId, text, packageName, windowId);
            if (recordingId == mPendingPaseoId) mPendingPaseoId = -1;
            // A manual reset/navigation can remount Paseo's input while transcription
            // is pending. Keep the copied result, but don't insert or report a failure
            // after the user's action. Identity is never transferred to the new input.
            if (result != null && sameComposer) {
                if (paseo) pasteIntoPaseo(root, result, mPendingComposer);
                else pasteIntoFocusedField(result);
            }
        } finally {
            clearPendingComposer();
            if (root != null) root.recycle();
        }
    }

    /** An IME can hold the active window while the focused application owns the editor. */
    private AccessibilityNodeInfo getApplicationRoot() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        List<AccessibilityWindowInfo> windows = getWindows();
        if (windows == null) return root;
        try {
            // The active accessibility window can be our pill, a system popup,
            // or the IME. Always prefer the actual focused application window.
            for (AccessibilityWindowInfo window : windows) {
                if (window.getType() == AccessibilityWindowInfo.TYPE_APPLICATION && window.isFocused()) {
                    AccessibilityNodeInfo candidate = window.getRoot();
                    if (candidate != null) {
                        if (root != null) root.recycle();
                        return candidate;
                    }
                }
            }
            return root;
        } finally {
            for (AccessibilityWindowInfo window : windows) window.recycle();
        }
    }

    private boolean isDictationModeEnabled() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        return prefs.getBoolean(PREF_DICTATION_MODE, false);
    }

    private void toggleDictation() {
        mPendingDictation.cancel();
        clearPendingComposer();
        mPendingPaseoId = -1;
        mPaseoGeneration++;
        clearFlowComposer();
        Intent intent = new Intent(this, VoiceVaultService.class);
        if (VoiceVaultService.isRecording()) {
            intent.setAction(VoiceVaultService.ACTION_STOP);
        } else {
            intent.setAction(VoiceVaultService.ACTION_START);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    /**
     * Pastes the clipboard into the focused editable field of the active
     * window. Auto-focuses an editable field if none currently has focus.
     * Uses 0ms ACTION_SET_TEXT with ACTION_PASTE fallback.
     * Auto-sends via ACTION_IME_ENTER and findAndClickSendButton when enabled.
     */
    private void pasteIntoFocusedField() {
        pasteIntoFocusedField(null);
    }

    private void pasteIntoFocusedField(String completedTranscript) {
        // Paseo never enters the generic IME / broad-button / repeated-send path.
        AccessibilityNodeInfo appRoot = getApplicationRoot();
        if (appRoot != null) {
            try {
                String pkg = appRoot.getPackageName() == null ? null : appRoot.getPackageName().toString();
                if (PaseoSelection.isPaseo(pkg)) {
                    pasteIntoPaseo(appRoot, completedTranscript);
                    return;
                }
            } finally { appRoot.recycle(); }
        }
        AccessibilityNodeInfo target = null;
        AccessibilityNodeInfo root = null;
        try {
            root = getApplicationRoot();
            if (root == null) {
                Log.i(TAG, "Paste: no active window root");
                return;
            }

            target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (target != null) {
                CharSequence cls = target.getClassName();
                boolean isEditClass = cls != null && cls.toString().contains("EditText");
                if (!target.isEditable() && !isEditClass) {
                    target.recycle();
                    target = null;
                }
            }
            if (target == null) {
                target = findFocusedEditable(root);
            }
            if (target == null) {
                target = findEditableNode(root);
                if (target != null) {
                    target.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                }
            }
            if (target == null) {
                Log.i(TAG, "Paste: no focused editable field");
                FloatingPillOverlay.showFeedback(this, "Copied — open composer, then Vol Down", false);
                return;
            }
            Log.i(TAG, "Paste: found target node: " + target.getClassName() + " / id=" + target.getViewIdResourceName() + " / editable=" + target.isEditable() + " / focused=" + target.isFocused());

            CharSequence clipText = readPasteText(completedTranscript);

            boolean pasted = false;
            if (clipText != null && clipText.length() > 0) {
                Bundle args = new Bundle();
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, clipText);
                pasted = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                Log.i(TAG, "Paste: ACTION_SET_TEXT dispatched, accepted=" + pasted);
            }
            if (!pasted) {
                pasted = target.performAction(AccessibilityNodeInfo.ACTION_PASTE);
                Log.i(TAG, "Paste: ACTION_PASTE dispatched, accepted=" + pasted);
            }

            if (pasted && isAutoSendEnabled()) {
                final AccessibilityNodeInfo sendTarget = AccessibilityNodeInfo.obtain(target);
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            int imeEnter = AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId();
                            boolean sent = sendTarget.performAction(imeEnter);
                            Log.i(TAG, "Paste: ACTION_IME_ENTER dispatched, accepted=" + sent);
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "Paste: auto-send IME_ENTER failed", t);
                    } finally {
                        sendTarget.recycle();
                    }

                    AccessibilityNodeInfo sendRoot = null;
                    try {
                        sendRoot = getRootInActiveWindow();
                        if (sendRoot != null) {
                            boolean clicked = findAndClickSendButton(sendRoot);
                            Log.i(TAG, "Paste: findAndClickSendButton dispatched, accepted=" + clicked);
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "Paste: findAndClickSendButton failed", t);
                    } finally {
                        if (sendRoot != null) {
                            sendRoot.recycle();
                        }
                    }
                }, IME_ENTER_DELAY_MS);

                // Follow-up for editors that first accept an IME action without submitting.
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    AccessibilityNodeInfo stage2Root = null;
                    try {
                        stage2Root = getRootInActiveWindow();
                        if (stage2Root != null) {
                            AccessibilityNodeInfo stage2Focus = stage2Root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                            if (stage2Focus != null) {
                                try {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                        int imeEnter = AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId();
                                        boolean stage2Sent = stage2Focus.performAction(imeEnter);
                                        Log.i(TAG, "Paste: stage 2 IME_ENTER dispatched, accepted=" + stage2Sent);
                                    }
                                } finally {
                                    stage2Focus.recycle();
                                }
                            }
                            boolean stage2Clicked = findAndClickSendButton(stage2Root);
                            Log.i(TAG, "Paste: stage 2 findAndClickSendButton accepted=" + stage2Clicked);
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "Paste: stage 2 auto-send failed", t);
                    } finally {
                        if (stage2Root != null) {
                            stage2Root.recycle();
                        }
                    }
                }, FOLLOWUP_SEND_DELAY_MS);
            }

            autoDismissClipboardOverlay();
        } catch (Throwable t) {
            Log.w(TAG, "Paste: failed", t);
        } finally {
            if (target != null) target.recycle();
            if (root != null) root.recycle();
        }
    }


    private void clearPendingComposer() {
        if (mPendingComposer != null) mPendingComposer.recycle();
        mPendingComposer = null;
    }

    private void clearFlowComposer() {
        if (mFlowGate != null) mFlowGate.cancel();
        mFlowGate = null;
        mFlowExpected = null;
        if (mFlowComposer != null) mFlowComposer.recycle();
        mFlowComposer = null;
        mFlowWindow = -1;
        mFlowPackage = null;
        mPaseoDeadline = 0;
        mFlowDispatched = false;
        if (mPaseoCheck != null) mKeyHandler.removeCallbacks(mPaseoCheck);
        mPaseoCheck = null;
    }

    /** Owns all child handles; the supplied root remains owned by the caller. */
    private static final class PaseoTree implements AutoCloseable {
        final PaseoSelection.Node root;
        final java.util.ArrayList<AccessibilityNodeInfo> owned = new java.util.ArrayList<>();
        final Rect band;
        PaseoTree(AccessibilityNodeInfo node) { this(node, null); }
        // A fresh screen skeleton preserves actual ancestry (including flattened
        // siblings), but does not descend into unrelated history above the input.
        PaseoTree(AccessibilityNodeInfo node, Rect band) {
            this.band = band;
            root = visit(node, "", true);
        }
        private static PaseoSelection.Node describe(AccessibilityNodeInfo info) {
            PaseoSelection.Node n = new PaseoSelection.Node();
            Rect rect = new Rect();
            info.getBoundsInScreen(rect);
            n.left = rect.left; n.top = rect.top; n.right = rect.right; n.bottom = rect.bottom;
            n.visible = info.isVisibleToUser() && !rect.isEmpty();
            n.enabled = info.isEnabled(); n.clickable = info.isClickable(); n.editable = info.isEditable();
            CharSequence desc = info.getContentDescription();
            CharSequence hint = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? info.getHintText() : null;
            CharSequence text = info.getText();
            // Editable text is never an identity label: it may be a user's draft.
            n.label = desc != null ? desc.toString() : hint != null ? hint.toString()
                    : !n.editable && text != null ? text.toString() : null;
            n.text = PaseoSelection.draftText(n.editable,
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && info.isShowingHintText(), text);
            n.handle = info;
            return n;
        }
        private PaseoSelection.Node visit(AccessibilityNodeInfo info, String path, boolean visible) {
            PaseoSelection.Node n = describe(info);
            n.path = path;
            n.visible &= visible;
            if (band != null && (n.bottom <= band.top || n.top > band.bottom
                    || n.right < band.left || n.left > band.right)) return n;
            for (int i = 0; i < info.getChildCount(); i++) {
                AccessibilityNodeInfo child = info.getChild(i);
                if (child != null) {
                    owned.add(child);
                    n.children.add(visit(child, path + "/" + i, n.visible));
                }
            }
            return n;
        }
        @Override public void close() { for (AccessibilityNodeInfo node : owned) node.recycle(); }
    }

    private void paseoFeedback(String message) {
        clearFlowComposer();
        FloatingPillOverlay.showFeedback(this, message, false);
    }

    private void pasteIntoPaseo(AccessibilityNodeInfo initialRoot, String transcript) {
        pasteIntoPaseo(initialRoot, transcript, null);
    }

    /** Fresh focused native identity is sufficient only after unique discovery. */
    private PaseoSelection.Node currentComposer(AccessibilityNodeInfo root, AccessibilityNodeInfo saved) {
        if (root == null || saved == null || !saved.refresh()
                || saved.getWindowId() != root.getWindowId()
                || saved.getPackageName() == null || root.getPackageName() == null
                || !saved.getPackageName().equals(root.getPackageName())) return null;
        PaseoSelection.Node editor = PaseoTree.describe(saved);
        if (!editor.editable || !editor.visible || !editor.enabled
                || !PaseoSelection.COMPOSER.equals(editor.label) || !saved.isFocused()) return null;
        AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        try { return saved.equals(focused) ? editor : null; }
        finally { if (focused != null) focused.recycle(); }
    }

    private void pasteIntoPaseo(AccessibilityNodeInfo initialRoot, String transcript,
            AccessibilityNodeInfo validated) {
        final int generation = ++mPaseoGeneration;
        clearFlowComposer();
        CharSequence value = readPasteText(transcript);
        if (value == null || value.length() == 0) { paseoFeedback("Nothing to insert"); return; }
        final String expected = value.toString();
        final String pkg = initialRoot.getPackageName().toString();
        final int window = initialRoot.getWindowId();
        try {
            if (validated != null && currentComposer(initialRoot, validated) != null) {
                mFlowComposer = AccessibilityNodeInfo.obtain(validated);
            } else {
                // Clipboard or genuinely unfocused/uncertain input: discover once.
                try (PaseoTree tree = new PaseoTree(initialRoot)) {
                    PaseoSelection.Node editor = PaseoSelection.composer(tree.root);
                    if (editor == null || (validated != null && !validated.equals(editor.handle))) {
                        paseoFeedback("Copied — composer unavailable"); return;
                    }
                    mFlowComposer = AccessibilityNodeInfo.obtain((AccessibilityNodeInfo) editor.handle);
                }
            }
            mFlowGate = new PaseoSelection.Gate();
            mFlowExpected = expected;
            mFlowWindow = window;
            mFlowPackage = pkg;
            mPaseoDeadline = SystemClock.uptimeMillis() + PASEO_READY_TIMEOUT_MS;
            if (!mFlowComposer.isFocused()) {
                if (!mFlowComposer.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) {
                    paseoFeedback("Copied — composer not ready"); return;
                }
                if (currentComposer(initialRoot, mFlowComposer) != null) {
                    insertPaseo(generation, pkg, window, expected);
                } else {
                    // Only a genuinely asynchronous/uncertain focus transition
                    // needs the legacy bounded fallback, never a focused input.
                    mKeyHandler.postDelayed(() -> insertPaseo(generation, pkg, window, expected), 80);
                }
            } else {
                insertPaseo(generation, pkg, window, expected);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Paseo composer lookup failed", t);
            paseoFeedback("Copied — composer unavailable");
        }
    }

    private boolean validPaseo(int generation, String pkg, int window, AccessibilityNodeInfo root) {
        return generation == mPaseoGeneration && isDictationModeEnabled() && root != null
                && pkg != null && root.getPackageName() != null && pkg.equals(root.getPackageName().toString())
                && root.getWindowId() == window && mFlowWindow == window;
    }

    private void insertPaseo(int generation, String pkg, int window, String expected) {
        if (generation != mPaseoGeneration) return;
        AccessibilityNodeInfo root = getApplicationRoot();
        try {
            if (!validPaseo(generation, pkg, window, root)) { cancelPaseoFlow(); return; }
            PaseoSelection.Node editor = currentComposer(root, mFlowComposer);
            if (editor == null) { cancelPaseoFlow(); return; }
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, expected);
            if (!mFlowGate.write(editor.text) || !mFlowComposer.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                paseoFeedback("Copied — insertion unavailable"); return;
            }
            // Check exact accessibility echo immediately; delayed RN readiness is
            // event-driven with a single bounded fallback timer, not mandatory sleep.
            FloatingPillOverlay.showFeedback(this, isAutoSendEnabled() ? "Inserted — finding Send" : "Inserted — auto-send OFF", false);
            checkPaseo(generation, pkg, window, expected);
        } catch (Throwable t) {
            Log.w(TAG, "Paseo insertion failed", t); paseoFeedback("Copied — insertion uncertain");
        } finally { if (root != null) root.recycle(); }
    }

    private void checkPaseo(int generation, String pkg, int window, String expected) {
        if (generation != mPaseoGeneration) return;
        if (mFlowDispatched) { confirmPaseo(generation, pkg, window, expected); return; }
        AccessibilityNodeInfo root = getApplicationRoot();
        boolean retry = false;
        try {
            if (!validPaseo(generation, pkg, window, root)) { cancelPaseoFlow(); return; }
            PaseoSelection.Node fresh = currentComposer(root, mFlowComposer);
            if (fresh == null) { cancelPaseoFlow(); return; }
            mFlowGate.observe(fresh, expected);
            if (mFlowGate.isCanceled()) { cancelPaseoFlow(); return; }
            // No toolbar query at all before exact echo, or for insertion-only.
            boolean needToolbar = expected.equals(fresh.text) && isAutoSendEnabled();
            Rect band = new Rect(fresh.left, fresh.top, fresh.right, rootBoundsBottom(root));
            try (PaseoTree tree = needToolbar ? new PaseoTree(root, band) : null) {
                // getChild() may return Android's cached pre-SET_TEXT editor even
                // after saved.refresh() observes the new text. Only the refreshed,
                // focused native identity is authoritative for draft and geometry;
                // the tree is used solely to discover the local submit control.
                PaseoSelection.Node editor = fresh;
                PaseoSelection.Status state = mFlowGate.check(tree == null ? null : tree.root, editor, expected,
                        needToolbar, SystemClock.uptimeMillis() >= mPaseoDeadline);
                if (state == PaseoSelection.Status.ABORT) {
                    cancelPaseoFlow(); return;
                }
                String feedback = PaseoSelection.feedback(state);
                if (feedback != null) { paseoFeedback(feedback); return; }
                if (state == PaseoSelection.Status.INSERTED) {
                    paseoFeedback("Inserted — auto-send OFF"); return;
                }
                if (state == PaseoSelection.Status.WAIT) retry = true;
                else {
                    PaseoSelection.Node button = PaseoSelection.submit(tree.root, editor);
                    AccessibilityNodeInfo target = (AccessibilityNodeInfo) button.handle;
                    // Refresh the actual action target and editor immediately. A
                    // changed label/geometry/readiness needs a new bounded check.
                    if (!target.refresh() || !sameButton(button, PaseoTree.describe(target))) {
                        schedulePaseoCheck(generation, pkg, window, expected, 100);
                        return;
                    }
                    PaseoSelection.Node echo = currentComposer(root, mFlowComposer);
                    mFlowGate.observe(echo, expected);
                    if (echo == null || !expected.equals(echo.text) || mFlowGate.isCanceled()) {
                        cancelPaseoFlow(); return;
                    }
                    if (!sameBounds(editor, echo)) {
                        schedulePaseoCheck(generation, pkg, window, expected, 100);
                        return;
                    }
                    // Consume before dispatch; action return or later UI state must never trigger a retry.
                    if (!mFlowGate.dispatch()) { cancelPaseoFlow(); return; }
                    mFlowDispatched = true;
                    mPaseoDeadline = SystemClock.uptimeMillis() + 1500L;
                    FloatingPillOverlay.showFeedback(this, "Sending to Paseo…", false);
                    boolean accepted = tapPaseo(target, generation);
                    if (!accepted) {
                        // A rejected gesture queues no touch; only then try the exact native action once.
                        accepted = target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    }
                    if (!accepted) paseoFeedback("Inserted — send manually");
                    else schedulePaseoCheck(generation, pkg, window, expected, 100);
                    return;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Paseo readiness failed", t);
            paseoFeedback(PaseoSelection.feedback(mFlowGate != null && mFlowGate.hasEcho()
                    ? PaseoSelection.Status.MANUAL : PaseoSelection.Status.UNCONFIRMED));
            return;
        } finally { if (root != null) root.recycle(); }
        if (retry) schedulePaseoCheck(generation, pkg, window, expected, 100);
    }

    /** One touch, only at the freshly validated exact control. No blind coordinates or retries. */
    private boolean tapPaseo(AccessibilityNodeInfo target, int generation) {
        Rect bounds = new Rect(); target.getBoundsInScreen(bounds);
        Path path = new Path(); path.moveTo((bounds.left + bounds.right) / 2f, (bounds.top + bounds.bottom) / 2f);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 60)).build();
        return dispatchGesture(gesture, new GestureResultCallback() {
            @Override public void onCancelled(GestureDescription gesture) {
                if (generation == mPaseoGeneration && mFlowDispatched) paseoFeedback("Send canceled — tap Send");
            }
        }, mKeyHandler);
    }

    /** Native composer reset is evidence of local submission, never of server acceptance. */
    private void confirmPaseo(int generation, String pkg, int window, String expected) {
        AccessibilityNodeInfo root = getApplicationRoot();
        try {
            if (!validPaseo(generation, pkg, window, root)) { cancelPaseoFlow(); return; }
            PaseoSelection.Node editor = currentComposer(root, mFlowComposer);
            if (editor == null) {
                try (PaseoTree tree = new PaseoTree(root)) {
                    PaseoSelection.Node candidate = PaseoSelection.composer(tree.root);
                    AccessibilityNodeInfo handle = candidate == null ? null : (AccessibilityNodeInfo) candidate.handle;
                    editor = handle != null && handle.refresh() ? PaseoTree.describe(handle) : null;
                }
            }
            if (editor != null && editor.text.isEmpty()) { paseoFeedback("Submitted to Paseo"); return; }
            if (editor != null && !expected.equals(editor.text)) { cancelPaseoFlow(); return; }
            // A normal submit tears down the old editor before its replacement
            // is exported. Wait within the confirmation budget; never resend.

            if (SystemClock.uptimeMillis() >= mPaseoDeadline) {
                paseoFeedback("Send not confirmed — tap Send"); return;
            }
            schedulePaseoCheck(generation, pkg, window, expected, 100);
        } catch (Throwable t) {
            paseoFeedback("Send not confirmed — tap Send");
        } finally { if (root != null) root.recycle(); }
    }

    private static int rootBoundsBottom(AccessibilityNodeInfo root) {
        Rect rect = new Rect();
        root.getBoundsInScreen(rect);
        return rect.bottom;
    }

    private static boolean sameButton(PaseoSelection.Node a, PaseoSelection.Node b) {
        return b.visible && b.enabled && b.clickable && !b.editable && a.label.equals(b.label)
                && sameBounds(a, b);
    }

    private static boolean sameBounds(PaseoSelection.Node a, PaseoSelection.Node b) {
        return a.left == b.left && a.top == b.top && a.right == b.right && a.bottom == b.bottom;
    }

    private void schedulePaseoCheck(int generation, String pkg, int window, String expected, long delay) {
        if (generation != mPaseoGeneration || mFlowGate == null) return;
        long remaining = mPaseoDeadline - SystemClock.uptimeMillis();
        if (remaining <= 0) {
            paseoFeedback(mFlowDispatched ? "Send not confirmed — tap Send" : PaseoSelection.feedback(mFlowGate.hasEcho()
                    ? PaseoSelection.Status.MANUAL : PaseoSelection.Status.UNCONFIRMED));
            return;
        }
        if (mPaseoCheck != null) mKeyHandler.removeCallbacks(mPaseoCheck);
        mPaseoCheck = () -> {
            mPaseoCheck = null;
            checkPaseo(generation, pkg, window, expected);
        };
        mKeyHandler.postDelayed(mPaseoCheck, Math.min(delay, remaining));
    }

    private void cancelPaseoFlow() {
        boolean dispatched = mFlowDispatched;
        mPaseoGeneration++;
        paseoFeedback(dispatched ? "Send not confirmed — check Paseo" : "Auto-send canceled");
    }

    /** Observe fresh native identity/text, not a stale event payload or path/bounds. */
    private void observePaseoFlow() {
        AccessibilityNodeInfo root = getApplicationRoot();
        try {
            if (!validPaseo(mPaseoGeneration, mFlowPackage, mFlowWindow, root)) {
                cancelPaseoFlow(); return;
            }
            if (mFlowDispatched) { confirmPaseo(mPaseoGeneration, mFlowPackage, mFlowWindow, mFlowExpected); return; }
            mFlowGate.observe(currentComposer(root, mFlowComposer), mFlowExpected);
            if (mFlowGate.isCanceled()) { cancelPaseoFlow(); return; }
            // Coalesce bursts; observation itself never walks history or toolbar.
            // Pull the pending readiness check forward without resetting its budget.
            if (mPaseoCheck != null) schedulePaseoCheck(mPaseoGeneration, mFlowPackage,
                    mFlowWindow, mFlowExpected, 0);
        } catch (Throwable t) {
            Log.w(TAG, "Paseo observation failed; canceling", t);
            cancelPaseoFlow();
        } finally { if (root != null) root.recycle(); }
    }

    private CharSequence readPasteText(String completedTranscript) {
        CharSequence clipText = completedTranscript;
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipText == null && cm != null && cm.hasPrimaryClip()) {
                ClipData clip = cm.getPrimaryClip();
                if (clip != null && clip.getItemCount() > 0) {
                    clipText = clip.getItemAt(0).getText();
                    if (clipText == null) {
                        clipText = clip.getItemAt(0).coerceToText(this);
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Paste: failed to read clipboard", t);
        }

        if (clipText == null || clipText.length() == 0) {
            String last = VoiceVaultService.getLastTranscript();
            if (last != null && !last.trim().isEmpty()) {
                clipText = last;
            }
        }

        if (clipText == null || clipText.length() == 0) {
            try {
                List<HistoryManager.Entry> history = HistoryManager.loadLocalCache(this);
                if (history != null && !history.isEmpty()) {
                    String histText = history.get(0).transcript;
                    if (histText != null && !histText.trim().isEmpty()) {
                        clipText = histText.trim();
                    }
                }
            } catch (Throwable ignored) {}
        }


        return clipText;
    }

    private void setAutoSendEnabled(boolean enabled) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(PREF_AUTO_SEND, enabled).apply();
    }

    private boolean isAutoSendEnabled() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        return prefs.getBoolean(PREF_AUTO_SEND, true);
    }

    /**
     * Depth-first search for a focused, editable node. Used when
     * {@code findFocus(FOCUS_INPUT)} returns null in some window configs.
     */
    private AccessibilityNodeInfo findFocusedEditable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isFocused() && node.isEditable()) {
            return AccessibilityNodeInfo.obtain(node);
        }
        int children = node.getChildCount();
        for (int i = 0; i < children; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try {
                AccessibilityNodeInfo match = findFocusedEditable(child);
                if (match != null) return match;
            } finally {
                child.recycle();
            }
        }
        return null;
    }

    /**
     * Depth-first search for an editable node in the active window hierarchy.
     * Used when neither findFocus(FOCUS_INPUT) nor findFocusedEditable finds a focused field
     * (e.g. in a hybrid web chat before manual tap).
     */
    private AccessibilityNodeInfo findEditableNode(AccessibilityNodeInfo node) {
        if (node == null) return null;
        CharSequence cls = node.getClassName();
        boolean isEditClass = cls != null && cls.toString().contains("EditText");
        if (node.isEditable() || isEditClass) {
            return AccessibilityNodeInfo.obtain(node);
        }
        int children = node.getChildCount();
        for (int i = 0; i < children; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try {
                AccessibilityNodeInfo match = findEditableNode(child);
                if (match != null) return match;
            } finally {
                child.recycle();
            }
        }
        return null;
    }

    /**
     * Traverses the active window hierarchy via DFS to find and click an on-screen
     * send or submit button, traversing parents if the matched icon node is not clickable.
     */
    private boolean findAndClickSendButton(AccessibilityNodeInfo node) {
        if (node == null) return false;
        if (isSendMatch(node)) {
            if (clickNodeOrParent(node)) {
                return true;
            }
        }
        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try {
                if (findAndClickSendButton(child)) {
                    return true;
                }
            } finally {
                child.recycle();
            }
        }
        return false;
    }

    /**
     * Checks if a node matches send/submit button keywords in contentDescription,
     * text, or view ID (case-insensitive).
     */
    private boolean isSendMatch(AccessibilityNodeInfo node) {
        if (node == null || node.isEditable()) return false;

        CharSequence descCs = node.getContentDescription();
        if (descCs != null) {
            String desc = descCs.toString().trim().toLowerCase(Locale.US);
            if (desc.equals("send") || desc.equals("submit")
                    || desc.equals("send message") || desc.equals("send sms")
                    || desc.contains("send") || desc.contains("submit")) {
                return true;
            }
        }

        CharSequence textCs = node.getText();
        if (textCs != null) {
            String text = textCs.toString().trim().toLowerCase(Locale.US);
            if (text.equals("send") || text.equals("submit")
                    || text.equals("send message") || text.equals("send sms")) {
                return true;
            }
        }

        String id = node.getViewIdResourceName();
        if (id != null) {
            String lowerId = id.toLowerCase(Locale.US);
            if ((lowerId.contains("send") && !lowerId.contains("sender"))
                    || lowerId.contains("submit")) {
                return true;
            }
        }

        return false;
    }

    /**
     * Clicks the node, or if it is not clickable, traverses up to 3 parent levels
     * to find and click an enclosing clickable wrapper (e.g. icon button container).
     */
    private boolean clickNodeOrParent(AccessibilityNodeInfo node) {
        if (node == null) return false;
        if (node.isClickable() && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true;
        }
        AccessibilityNodeInfo current = node.getParent();
        for (int level = 0; level < 3 && current != null; level++) {
            try {
                if (current.isClickable() && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    current.recycle();
                    return true;
                }
                AccessibilityNodeInfo parent = current.getParent();
                current.recycle();
                current = parent;
            } catch (Throwable t) {
                break;
            }
        }
        if (current != null) {
            current.recycle();
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        int type = event.getEventType();
        String pkg = event.getPackageName() == null ? null : event.getPackageName().toString();
        if (mFlowComposer != null && !"com.android.systemui".equals(pkg)) {
            if (!mFlowDispatched && PaseoSelection.isPaseo(pkg) && type == AccessibilityEvent.TYPE_VIEW_CLICKED) {
                AccessibilityNodeInfo source = event.getSource();
                try {
                    if (source == null || !mFlowComposer.equals(source)) cancelPaseoFlow();
                } finally { if (source != null) source.recycle(); }
            } else if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    || (PaseoSelection.isPaseo(pkg) && (type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
                        || type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                        || type == AccessibilityEvent.TYPE_VIEW_SCROLLED))) {
                // Keyboard window changes are harmless if the application's exact
                // composer still exists; manual clears/remounts/navigation are not.
                observePaseoFlow();
            }
        }
        if (type != AccessibilityEvent.TYPE_VIEW_CLICKED
                && type != AccessibilityEvent.TYPE_VIEW_SCROLLED
                && type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        if (type == AccessibilityEvent.TYPE_VIEW_CLICKED && mPendingDictation.matchesWindow(pkg, event.getWindowId())) {
            AccessibilityNodeInfo source = event.getSource();
            try {
                // A positively identified same-editor focus is harmless; everything else cancels.
                if (source == null || mPendingComposer == null || !mPendingComposer.equals(source)) {
                    mPendingDictation.cancel();
                    clearPendingComposer();
                    mPendingPaseoId = -1;
                }
            } finally { if (source != null) source.recycle(); }
        }
        if ((type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || type == AccessibilityEvent.TYPE_VIEW_SCROLLED)
                && mPendingDictation.isPending() && !"com.android.systemui".equals(pkg)) {
            AccessibilityNodeInfo root = getApplicationRoot();
            try {
                String target = root == null || root.getPackageName() == null ? null : root.getPackageName().toString();
                boolean valid = root != null && mPendingDictation.matchesWindow(target, root.getWindowId());
                if (valid && PaseoSelection.isPaseo(target)) valid = currentComposer(root, mPendingComposer) != null;
                if (!valid) {
                    mPendingDictation.cancel(); clearPendingComposer(); mPendingPaseoId = -1;
                }
            } finally { if (root != null) root.recycle(); }
        }
    }

    @Override
    public void onInterrupt() { cancelPendingKeyCallbacks(); }
}
