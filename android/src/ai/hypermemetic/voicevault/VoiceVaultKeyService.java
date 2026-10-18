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
import android.media.AudioManager;
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

/**
 * Hardware volume-key controller for Dictation Mode. Taps act on release; holds
 * ({@link #HOLD_MS}) act while pressed.
 *
 * <ul>
 *   <li>Dictation Mode OFF: hold Volume Up enables Dictation Mode. A Volume Up tap
 *       raises the volume (with the slider); Volume Down is untouched.</li>
 *   <li>Dictation Mode ON (both keys consumed, system volume never changes):
 *     <ul>
 *       <li>Tap Volume Up: start/stop recording. Hold: disable Dictation Mode.</li>
 *       <li>Tap Volume Down: finish, insert into the focused composer (added to any
 *           draft) and send when Auto-send is on. Hold: toggle Auto-send.</li>
 *     </ul>
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

    private static volatile VoiceVaultKeyService sInstance = null;

    private final Handler mKeyHandler = new Handler(Looper.getMainLooper());
    static final long HOLD_MS = 500;
    // Per-key press state: owned until release; a hold acts once while pressed.
    private boolean mOwnUp, mOwnDown, mHeldUp, mHeldDown, mModeAtPressUp;
    private int mPressGenUp, mPressGenDown;
    private Runnable mHoldUp, mHoldDown;
    private int mKeyGeneration;
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
    private static final long PASEO_SEND_READY_TIMEOUT_MS = 5000L;
    // Web composers (T3 Code in Chrome) clear after a round trip through React.
    private static final long PASEO_CONFIRM_TIMEOUT_MS = 3000L;
    private String mFlowSentLabel = "Sent";
    private SharedPreferences mModePrefs;
    private final SharedPreferences.OnSharedPreferenceChangeListener mModeListener = (prefs, key) -> {
        if (PREF_DICTATION_MODE.equals(key)) {
            cancelPendingKeyCallbacks();
            FloatingPillOverlay.refreshMode(this);
        } else if (PREF_AUTO_SEND.equals(key)) {
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
        // Keys stay owned until release so the system never sees half a press.
        mKeyGeneration++;
        if (mHoldUp != null) mKeyHandler.removeCallbacks(mHoldUp);
        if (mHoldDown != null) mKeyHandler.removeCallbacks(mHoldDown);
        mHoldUp = null;
        mHoldDown = null;
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

    /**
     * Each volume key is either a tap (acts on release) or a hold (acts at
     * {@link #HOLD_MS} while still pressed, with haptic confirmation). Key
     * filtering returns at once; actions run on the handler.
     */
    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        boolean up = keyCode == KeyEvent.KEYCODE_VOLUME_UP;
        if (!up && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return super.onKeyEvent(event);

        if (event.getAction() == KeyEvent.ACTION_UP) {
            if (!(up ? mOwnUp : mOwnDown)) return super.onKeyEvent(event);
            boolean held = up ? mHeldUp : mHeldDown;
            int generation = up ? mPressGenUp : mPressGenDown;
            boolean modeAtPress = up ? mModeAtPressUp : true;
            if (up) { mOwnUp = false; mHeldUp = false; } else { mOwnDown = false; mHeldDown = false; }
            Runnable hold = up ? mHoldUp : mHoldDown;
            if (hold != null) mKeyHandler.removeCallbacks(hold);
            if (up) mHoldUp = null; else mHoldDown = null;
            // A hold already acted; a mode change mid-press voids the tap.
            if (!held && generation == mKeyGeneration) {
                mKeyHandler.post(() -> {
                    if (generation == mKeyGeneration) tapVolumeKey(up, modeAtPress);
                });
            }
            return true;
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN) return super.onKeyEvent(event);
        if (event.getRepeatCount() > 0) return (up ? mOwnUp : mOwnDown) || super.onKeyEvent(event);

        boolean mode = isDictationModeEnabled();
        // Dictation Mode OFF: Volume Down is the system's.
        if (!mode && !up) return super.onKeyEvent(event);
        final int generation = mKeyGeneration;
        Runnable hold = new Runnable() {
            @Override public void run() {
                if ((up ? mHoldUp : mHoldDown) != this) return;
                if (up) { mHoldUp = null; mHeldUp = true; } else { mHoldDown = null; mHeldDown = true; }
                if (generation == mKeyGeneration) holdVolumeKey(up, mode);
            }
        };
        if (up) {
            if (mHoldUp != null) mKeyHandler.removeCallbacks(mHoldUp);
            mOwnUp = true; mHeldUp = false; mPressGenUp = generation; mModeAtPressUp = mode; mHoldUp = hold;
        } else {
            if (mHoldDown != null) mKeyHandler.removeCallbacks(mHoldDown);
            mOwnDown = true; mHeldDown = false; mPressGenDown = generation; mHoldDown = hold;
        }
        mKeyHandler.postDelayed(hold, HOLD_MS);
        return true;
    }

    private void tapVolumeKey(boolean up, boolean modeAtPress) {
        if (up && !modeAtPress) {
            // The press was held back to tell tap from hold; give it to the system now.
            raiseVolume();
            return;
        }
        if (!isDictationModeEnabled()) return;
        if (up) {
            Log.i(TAG, "Dictation Mode: Vol Up tap -> toggle recording");
            toggleDictation();
        } else {
            Log.i(TAG, "Dictation Mode: Vol Down tap -> finish and insert");
            finishAndInsert();
        }
    }

    private void holdVolumeKey(boolean up, boolean modeAtPress) {
        if (up && !modeAtPress) {
            Log.i(TAG, "Dictation Mode OFF: Vol Up hold -> ENABLE dictation mode");
            setDictationModeEnabled(true);
            notifyTileStateChanged();
            provideFeedback(true, "Dictation Mode: ON");
        } else if (up) {
            Log.i(TAG, "Dictation Mode ON: Vol Up hold -> DISABLE dictation mode");
            if (VoiceVaultService.isRecording()) stopRecordingService();
            setDictationModeEnabled(false);
            cancelPendingKeyCallbacks();
            notifyTileStateChanged();
            provideFeedback(false, "Dictation Mode: OFF");
        } else if (isDictationModeEnabled()) {
            boolean enabled = !isAutoSendEnabled();
            setAutoSendEnabled(enabled);
            provideFeedback(enabled, "Auto-send: " + (enabled ? "ON" : "OFF"));
        }
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

    private void raiseVolume() {
        try {
            AudioManager audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (audio != null) audio.adjustSuggestedStreamVolume(AudioManager.ADJUST_RAISE,
                    AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI);
        } catch (Throwable t) {
            Log.w(TAG, "Failed to raise volume", t);
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

    /** Visual + haptic only; Voice Vault plays no sounds. */
    private void provideFeedback(boolean positive, String message) {
        FloatingPillOverlay.showFeedback(this, message, positive);

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
                mPendingPaseoId = VoiceVaultService.getRecordingStartTime();
                mPendingComposer = findComposer(root);
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
            // The input chosen when Vol Down was pressed must still be the live
            // composer. Refresh its native identity, not the entire chat history.
            // No input was found then: discover one now, as for a clipboard paste.
            boolean sameComposer = mPendingComposer == null || currentComposer(root, mPendingComposer) != null;
            String result = mPendingDictation.complete(recordingId, text, packageName, windowId);
            if (recordingId == mPendingPaseoId) mPendingPaseoId = -1;
            // A manual reset/navigation can remount the input while transcription
            // is pending. Keep the copied result, but don't insert or report a failure
            // after the user's action. Identity is never transferred to the new input.
            if (result != null && sameComposer && packageName != null) {
                pasteIntoPaseo(root, result, mPendingComposer);
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

    /** Inserts the clipboard (or latest transcript) into the focused app's composer. */
    private void pasteIntoFocusedField() {
        pasteIntoFocusedField(null);
    }

    private void pasteIntoFocusedField(String completedTranscript) {
        AccessibilityNodeInfo appRoot = getApplicationRoot();
        if (appRoot == null || appRoot.getPackageName() == null) {
            if (appRoot != null) appRoot.recycle();
            FloatingPillOverlay.showFeedback(this, "Copied — open composer, then Vol Down", false);
            return;
        }
        try {
            pasteIntoPaseo(appRoot, completedTranscript);
        } finally { appRoot.recycle(); }
        autoDismissClipboardOverlay();
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
        mFlowSentLabel = "Sent";
        if (mPaseoCheck != null) mKeyHandler.removeCallbacks(mPaseoCheck);
        mPaseoCheck = null;
    }

    /** Owns all child handles; the supplied root remains owned by the caller. */
    private static final class PaseoTree implements AutoCloseable {
        final PaseoSelection.Node root;
        final java.util.ArrayList<AccessibilityNodeInfo> owned = new java.util.ArrayList<>();
        PaseoTree(AccessibilityNodeInfo node) { root = visit(node, "", true); }

        // The Android screen is the contract. Query the nearest native input
        // group with controls, including disabled controls that may become ready.
        // A platform text query avoids walking every message in a flattened chat;
        // a small bounded walk covers providers whose text query finds nothing.
        PaseoTree(AccessibilityNodeInfo composer, AccessibilityNodeInfo application) {
            PaseoSelection.Node found = null, nearest = null;
            int widened = 0;
            AccessibilityNodeInfo parent = composer.getParent();
            for (int depth = 0; parent != null && depth < 32; depth++) {
                owned.add(parent);
                if (!parent.refresh() || parent.getWindowId() != application.getWindowId()
                        || parent.getPackageName() == null || application.getPackageName() == null
                        || !application.getPackageName().toString().equals(parent.getPackageName().toString())) break;
                PaseoSelection.Node scope = describe(parent);
                scope.scoped = true;
                scope.children.add(describe(composer));
                for (String query : new String[] {"Send", "Queue", "Submit", "Steer"}) {
                    java.util.List<AccessibilityNodeInfo> results = parent.findAccessibilityNodeInfosByText(query);
                    if (results == null) continue;
                    for (AccessibilityNodeInfo result : results) {
                        owned.add(result);
                        if (!result.refresh() || result.getPackageName() == null
                                || !application.getPackageName().toString().equals(result.getPackageName().toString())
                                || result.getWindowId() != application.getWindowId()) continue;
                        if (belongsTo(result, parent)) addSubmit(scope, result);
                    }
                }
                if (scope.children.size() > 1) { found = scope; break; }
                boolean form = hasControls(parent, composer, new int[] {128});
                if (form || parent.equals(application)) {
                    findSubmit(scope, parent, composer, new int[] {160});
                    if (scope.children.size() > 1) { found = scope; break; }
                    // Keep the nearest form even when its Send isn't mounted yet.
                    // Widen only slightly: a web composer nests its toolbar a
                    // level or two above the editor, never across the window.
                    if (nearest == null) nearest = scope;
                    if (parent.equals(application) || ++widened > 2) break;
                }
                AccessibilityNodeInfo next = parent.getParent();
                if (depth == 31 && next != null) owned.add(next);
                parent = next;
            }
            root = found != null ? found : nearest;
        }

        private static void addSubmit(PaseoSelection.Node scope, AccessibilityNodeInfo result) {
            PaseoSelection.Node button = describe(result);
            if (button.editable || !button.visible || !button.clickable
                    || !PaseoSelection.primary(button.label)) return;
            for (PaseoSelection.Node child : scope.children)
                if (result.equals(child.handle)) return;
            scope.children.add(button);
        }

        private void findSubmit(PaseoSelection.Node scope, AccessibilityNodeInfo node,
                AccessibilityNodeInfo editor, int[] budget) {
            for (int i = 0; i < node.getChildCount() && --budget[0] >= 0; i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child == null) continue;
                owned.add(child);
                if (editor.equals(child)) continue;
                if (PaseoSelection.primary(describe(child).label) && child.refresh()) addSubmit(scope, child);
                findSubmit(scope, child, editor, budget);
            }
        }

        private static boolean hasControls(AccessibilityNodeInfo node, AccessibilityNodeInfo editor, int[] budget) {
            if (--budget[0] < 0) return true; // uncertain boundary must not widen the search
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child == null) continue;
                try {
                    // The draft's own contents (chips, links) are not composer controls.
                    if (editor.equals(child) || !child.refresh()) continue;
                    if (child.isVisibleToUser() && !child.isEditable() && child.isClickable()) return true;
                    if (hasControls(child, editor, budget)) return true;
                } finally { child.recycle(); }
            }
            return false;
        }

        private static PaseoSelection.Node describe(AccessibilityNodeInfo info) {
            PaseoSelection.Node n = new PaseoSelection.Node();
            Rect rect = new Rect();
            info.getBoundsInScreen(rect);
            n.left = rect.left; n.top = rect.top; n.right = rect.right; n.bottom = rect.bottom;
            n.visible = info.isVisibleToUser() && !rect.isEmpty();
            n.enabled = info.isEnabled(); n.clickable = info.isClickable(); n.editable = info.isEditable();
            n.focused = info.isFocused();
            CharSequence desc = info.getContentDescription();
            CharSequence hint = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? info.getHintText() : null;
            CharSequence text = info.getText();
            // Editable text is never an identity label: it may be a user's draft.
            n.label = desc != null ? desc.toString() : hint != null ? hint.toString()
                    : !n.editable && text != null ? text.toString() : null;
            // Chrome exposes a web button's aria-label as text and may put a
            // tooltip/description in the other fields; any exact submit name counts.
            if (!n.editable && !PaseoSelection.primary(n.label)) {
                if (text != null && PaseoSelection.primary(text.toString())) n.label = text.toString();
                else if (hint != null && PaseoSelection.primary(hint.toString())) n.label = hint.toString();
            }
            n.text = PaseoSelection.draftText(n.editable,
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && info.isShowingHintText(), text, hint);
            n.handle = info;
            return n;
        }
        private PaseoSelection.Node visit(AccessibilityNodeInfo info, String path, boolean visible) {
            PaseoSelection.Node n = describe(info);
            n.path = path;
            n.visible &= visible;
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

    /**
     * The saved input must still be live, editable and visible in the same window,
     * and no other editable input may have taken the OS input focus. A closed
     * keyboard or focus on a button does not make it a different composer.
     */
    private PaseoSelection.Node currentComposer(AccessibilityNodeInfo root, AccessibilityNodeInfo saved) {
        if (root == null || saved == null || !saved.refresh()
                || saved.getWindowId() != root.getWindowId()
                || saved.getPackageName() == null || root.getPackageName() == null
                || !saved.getPackageName().toString().equals(root.getPackageName().toString())) return null;
        PaseoSelection.Node editor = PaseoTree.describe(saved);
        if (!editor.editable || !editor.visible || !editor.enabled) return null;
        AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        try { return focused == null || saved.equals(focused) || !focused.isEditable() ? editor : null; }
        finally { if (focused != null) focused.recycle(); }
    }

    /** The OS input focus first (no traversal); otherwise the window's only visible input. */
    private AccessibilityNodeInfo findComposer(AccessibilityNodeInfo root) {
        if (root == null) return null;
        AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        try {
            if (focused != null && currentComposer(root, focused) != null) return AccessibilityNodeInfo.obtain(focused);
        } finally { if (focused != null) focused.recycle(); }
        try (PaseoTree tree = new PaseoTree(root)) {
            PaseoSelection.Node editor = PaseoSelection.composer(tree.root);
            return editor == null ? null : AccessibilityNodeInfo.obtain((AccessibilityNodeInfo) editor.handle);
        }
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
            if (validated != null) {
                if (currentComposer(initialRoot, validated) == null) {
                    paseoFeedback("Copied — composer unavailable"); return;
                }
                mFlowComposer = AccessibilityNodeInfo.obtain(validated);
            } else {
                mFlowComposer = findComposer(initialRoot);
                if (mFlowComposer == null) { paseoFeedback("Copied — open composer, then Vol Down"); return; }
            }
            mFlowGate = new PaseoSelection.Gate();
            mFlowExpected = expected;
            mFlowWindow = window;
            mFlowPackage = pkg;
            mPaseoDeadline = SystemClock.uptimeMillis() + PASEO_READY_TIMEOUT_MS;
            // Focus is a courtesy for the app; text insertion doesn't wait for it.
            if (!mFlowComposer.isFocused()) mFlowComposer.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            insertPaseo(generation, pkg, window, expected);
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

    private void insertPaseo(int generation, String pkg, int window, String transcript) {
        if (generation != mPaseoGeneration) return;
        AccessibilityNodeInfo root = getApplicationRoot();
        try {
            if (!validPaseo(generation, pkg, window, root)) { cancelPaseoFlow(); return; }
            PaseoSelection.Node editor = currentComposer(root, mFlowComposer);
            if (editor == null) { cancelPaseoFlow(); return; }
            // Add to whatever the user already typed; the combined draft is what gets sent.
            final String expected = PaseoSelection.append(editor.text, transcript);
            mFlowExpected = expected;
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, expected);
            if (!mFlowGate.write(editor.text) || !mFlowComposer.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                paseoFeedback("Copied — insertion unavailable"); return;
            }
            // Insertion confirmation has its own budget after the write returns.
            mPaseoDeadline = SystemClock.uptimeMillis() + PASEO_READY_TIMEOUT_MS;
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
            observePaseoDraft(fresh, expected);
            if (mFlowGate.isCanceled()) { cancelPaseoFlow(); return; }
            // No toolbar query at all before exact echo, or for insertion-only.
            boolean needToolbar = PaseoSelection.sameDraft(expected, fresh.text) && isAutoSendEnabled();
            try (PaseoTree tree = needToolbar ? new PaseoTree(mFlowComposer, root) : null) {
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
                String feedback = state == PaseoSelection.Status.MANUAL
                        ? mFlowGate.unavailableFeedback() : PaseoSelection.feedback(state);
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
                    if (!target.refresh() || !sameButton(button, PaseoTree.describe(target))
                            || !belongsTo(target, (AccessibilityNodeInfo) tree.root.handle)
                            || !belongsTo(mFlowComposer, (AccessibilityNodeInfo) tree.root.handle)) {
                        schedulePaseoCheck(generation, pkg, window, expected, 100);
                        return;
                    }
                    PaseoSelection.Node echo = currentComposer(root, mFlowComposer);
                    observePaseoDraft(echo, expected);
                    if (echo == null || !PaseoSelection.sameDraft(expected, echo.text) || mFlowGate.isCanceled()) {
                        cancelPaseoFlow(); return;
                    }
                    // Consume before dispatch; action return or later UI state must never trigger a retry.
                    if (!mFlowGate.dispatch()) { cancelPaseoFlow(); return; }
                    mFlowDispatched = true;
                    mFlowSentLabel = button.label.contains("Queue") ? "Queued"
                            : button.label.contains("Steer") ? "Steered" : "Sent";
                    mPaseoDeadline = SystemClock.uptimeMillis() + PASEO_CONFIRM_TIMEOUT_MS;
                    FloatingPillOverlay.showFeedback(this, "Sending…", false);
                    boolean accepted = target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    if (!accepted) {
                        // Android rejected the semantic action; no accepted activation
                        // is retried. Use one touch at the live control only in this case.
                        accepted = tapPaseo(target, generation);
                    }
                    if (!accepted) paseoFeedback("Send action rejected — tap Send");
                    else schedulePaseoCheck(generation, pkg, window, expected, 100);
                    return;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Paseo readiness failed", t);
            paseoFeedback(mFlowGate != null && mFlowGate.hasEcho()
                    ? "Inserted — Send check failed" : PaseoSelection.feedback(PaseoSelection.Status.UNCONFIRMED));
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
            if (editor != null && PaseoSelection.blank(editor.text)) { sentFeedback(mFlowSentLabel); return; }
            if (editor != null && !PaseoSelection.sameDraft(expected, editor.text)) { cancelPaseoFlow(); return; }
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

    private static boolean sameButton(PaseoSelection.Node a, PaseoSelection.Node b) {
        return b.visible && b.enabled && b.clickable && !b.editable && a.label.equals(b.label)
                && a.handle.equals(b.handle);
    }

    private static boolean belongsTo(AccessibilityNodeInfo target, AccessibilityNodeInfo owner) {
        AccessibilityNodeInfo parent = target.getParent();
        for (int depth = 0; parent != null && depth < 32; depth++) {
            AccessibilityNodeInfo next = null;
            try {
                if (!parent.refresh()) return false;
                if (owner.equals(parent)) return true;
                next = parent.getParent();
            } finally { parent.recycle(); }
            parent = next;
        }
        if (parent != null) parent.recycle();
        return false;
    }

    private void schedulePaseoCheck(int generation, String pkg, int window, String expected, long delay) {
        if (generation != mPaseoGeneration || mFlowGate == null) return;
        long remaining = mPaseoDeadline - SystemClock.uptimeMillis();
        if (remaining <= 0) {
            paseoFeedback(mFlowDispatched ? "Send not confirmed — tap Send" : mFlowGate.hasEcho()
                    ? mFlowGate.unavailableFeedback() : PaseoSelection.feedback(PaseoSelection.Status.UNCONFIRMED));
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
        paseoFeedback(dispatched ? "Send not confirmed — check app" : "Auto-send canceled");
    }

    private void sentFeedback(String label) {
        clearFlowComposer();
        FloatingPillOverlay.showFeedback(this, label + " ✓", true);
    }

    /** Give React its readiness budget once, after exact native insertion echo. */
    private void observePaseoDraft(PaseoSelection.Node editor, String expected) {
        boolean hadEcho = mFlowGate.hasEcho();
        mFlowGate.observe(editor, expected);
        if (!hadEcho && mFlowGate.hasEcho()) {
            mPaseoDeadline = SystemClock.uptimeMillis() + PASEO_SEND_READY_TIMEOUT_MS;
        }
    }

    /** Observe fresh native identity/text, not a stale event payload or path/bounds. */
    private void observePaseoFlow() {
        AccessibilityNodeInfo root = getApplicationRoot();
        try {
            if (!validPaseo(mPaseoGeneration, mFlowPackage, mFlowWindow, root)) {
                cancelPaseoFlow(); return;
            }
            if (mFlowDispatched) { confirmPaseo(mPaseoGeneration, mFlowPackage, mFlowWindow, mFlowExpected); return; }
            observePaseoDraft(currentComposer(root, mFlowComposer), mFlowExpected);
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

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        int type = event.getEventType();
        String pkg = event.getPackageName() == null ? null : event.getPackageName().toString();
        boolean flowApp = pkg != null && pkg.equals(mFlowPackage);
        if (mFlowComposer != null && !"com.android.systemui".equals(pkg)) {
            if (!mFlowDispatched && flowApp && type == AccessibilityEvent.TYPE_VIEW_CLICKED) {
                AccessibilityNodeInfo source = event.getSource();
                try {
                    if (source == null || !mFlowComposer.equals(source)) cancelPaseoFlow();
                } finally { if (source != null) source.recycle(); }
            } else if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    || (flowApp && (type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
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
                if (valid && mPendingComposer != null) valid = currentComposer(root, mPendingComposer) != null;
                if (!valid) {
                    mPendingDictation.cancel(); clearPendingComposer(); mPendingPaseoId = -1;
                }
            } finally { if (root != null) root.recycle(); }
        }
    }

    @Override
    public void onInterrupt() { cancelPendingKeyCallbacks(); }
}
