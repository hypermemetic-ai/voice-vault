package ai.hypermemetic.voicevault;

import android.accessibilityservice.AccessibilityService;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
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
 *       <li>Double-press Volume Down: toggles auto-send on paste (pref_auto_send_on_paste).</li>
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
    // Same deadline logic, independent first-press state for Down.
    private final VolumeUpTiming mVolDnTiming = new VolumeUpTiming();
    private Runnable mPendingVolUpRunnable = null;
    private Runnable mPendingVolDnRunnable = null;
    private final PendingDictation mPendingDictation = new PendingDictation();
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
        mPendingDictation.cancel();
        mVolUpTiming.reset();
        mVolDnTiming.reset();
        if (mPendingVolUpRunnable != null) {
            mKeyHandler.removeCallbacks(mPendingVolUpRunnable);
            mPendingVolUpRunnable = null;
        }
        if (mPendingVolDnRunnable != null) {
            mKeyHandler.removeCallbacks(mPendingVolDnRunnable);
            mPendingVolDnRunnable = null;
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
                        return true; // Consume second press
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
                    // Double-press Volume Down while active -> TOGGLE auto-send
                    mKeyHandler.removeCallbacks(mPendingVolDnRunnable);
                    mPendingVolDnRunnable = null;
                    boolean newAutoSend = !isAutoSendEnabled();
                    setAutoSendEnabled(newAutoSend);
                    Log.i(TAG, "Dictation Mode ON: Vol Dn double-press -> auto-send " + (newAutoSend ? "ENABLED" : "DISABLED"));
                    provideFeedback(newAutoSend, "Auto-send: " + (newAutoSend ? "ON" : "OFF"));
                    return true;
                }
                if (press == VolumeUpTiming.ActivePress.EXPIRED_FIRST) {
                    // Handler may be late: finish the first single before starting a new window.
                    mKeyHandler.removeCallbacks(mPendingVolDnRunnable);
                    mPendingVolDnRunnable = null;
                    Log.i(TAG, "Dictation Mode: expired Vol Dn single-press -> finish and insert");
                    finishAndInsert();
                }
                Runnable single = new Runnable() {
                    @Override public void run() {
                        if (mPendingVolDnRunnable != this) return;
                        mPendingVolDnRunnable = null;
                        mVolDnTiming.activeSingleFinished();
                        Log.i(TAG, "Dictation Mode: Vol Dn single-press -> finish and insert");
                        finishAndInsert();
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
            Log.w(TAG, "Failed to stop recording service", t);
        }
    }

    private void setDictationModeEnabled(boolean enabled) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefs.edit().putBoolean(PREF_DICTATION_MODE, enabled).apply();
    }

    private void setAutoSendEnabled(boolean enabled) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefs.edit().putBoolean(PREF_AUTO_SEND, enabled).apply();
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
        AccessibilityNodeInfo root = getApplicationRoot();
        try {
            mPendingDictation.cancel();
            if (root != null && root.getPackageName() != null) {
                mPendingDictation.begin(VoiceVaultService.getRecordingStartTime(),
                        root.getPackageName().toString(), root.getWindowId());
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
        AccessibilityNodeInfo root = getApplicationRoot();
        try {
            String packageName = root == null || root.getPackageName() == null
                    ? null : root.getPackageName().toString();
            int windowId = root == null ? -1 : root.getWindowId();
            String result = mPendingDictation.complete(recordingId, text, packageName, windowId);
            if (result != null) pasteIntoFocusedField(result);
        } finally {
            if (root != null) root.recycle();
        }
    }

    /** An IME can hold the active window while the focused application owns the editor. */
    private AccessibilityNodeInfo getApplicationRoot() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        List<AccessibilityWindowInfo> windows = getWindows();
        if (windows == null) return root;
        try {
            boolean imeActive = false;
            for (AccessibilityWindowInfo window : windows) {
                if (root != null && window.getId() == root.getWindowId()
                        && window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                    imeActive = true;
                }
            }
            if (root == null || imeActive) {
                for (AccessibilityWindowInfo window : windows) {
                    if (window.getType() == AccessibilityWindowInfo.TYPE_APPLICATION && window.isFocused()) {
                        AccessibilityNodeInfo candidate = window.getRoot();
                        if (candidate != null) {
                            if (root != null) root.recycle();
                            return candidate;
                        }
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
        if (event.getEventType() != AccessibilityEvent.TYPE_VIEW_CLICKED) return;
        String packageName = event.getPackageName() == null ? null : event.getPackageName().toString();
        // SystemUI clipboard dismissal is unrelated to the destination conversation.
        if (mPendingDictation.matchesWindow(packageName, event.getWindowId())) {
            mPendingDictation.cancel();
        }
    }

    @Override
    public void onInterrupt() { cancelPendingKeyCallbacks(); }
}
