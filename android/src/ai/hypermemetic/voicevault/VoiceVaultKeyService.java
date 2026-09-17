package ai.hypermemetic.voicevault;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.List;

/**
 * Hardware volume-key controller for Dictation Mode (toggled via the Quick
 * Settings tile, stored in {@code pref_dictation_mode_enabled}).
 *
 * <ul>
 *   <li>Dictation Mode OFF: volume keys pass through to the system normally.</li>
 *   <li>Dictation Mode ON:
 *     <ul>
 *       <li>Volume Up: toggles recording (start/stop + Whisper transcription).</li>
 *       <li>Volume Down: pastes the clipboard into the focused editable field,
 *           optionally auto-sending with IME Enter.</li>
 *     </ul>
 *     Both keys are consumed so the system volume never changes.
 *   </li>
 * </ul>
 *
 * Also provides accessibility capabilities to auto-dismiss the SystemUI
 * clipboard overlay so it doesn't block bottom-left UI buttons (e.g. in Orca).
 */
public class VoiceVaultKeyService extends AccessibilityService {
    private static final String TAG = "VoiceVaultKey";
    private static final String PREFS_NAME = "voice_vault_prefs";
    private static final String PREF_DICTATION_MODE = "pref_dictation_mode_enabled";
    private static final String PREF_AUTO_SEND = "pref_auto_send_on_paste";
    private static final long IME_ENTER_DELAY_MS = 60;

    private static volatile VoiceVaultKeyService sInstance = null;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        sInstance = this;
        Log.i(TAG, "Accessibility service connected");
    }

    @Override
    public boolean onUnbind(Intent intent) {
        if (sInstance == this) {
            sInstance = null;
        }
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        if (sInstance == this) {
            sInstance = null;
        }
        super.onDestroy();
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
        if (!isVolumeKey || !isDictationModeEnabled()) {
            return super.onKeyEvent(event);
        }

        // Dictation Mode is ON: consume volume keys so the system volume
        // never changes, and act on the press (ACTION_DOWN only).
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                Log.i(TAG, "Dictation Mode: Vol Up -> toggle recording");
                toggleDictation();
            } else {
                Log.i(TAG, "Dictation Mode: Vol Dn -> paste transcript");
                pasteIntoFocusedField();
            }
        }
        return true;
    }

    private boolean isDictationModeEnabled() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        return prefs.getBoolean(PREF_DICTATION_MODE, false);
    }

    private void toggleDictation() {
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
     * window. Falls back to a root traversal when {@code findFocus} returns
     * null; never crashes when no editable field exists.
     */
    private void pasteIntoFocusedField() {
        AccessibilityNodeInfo target = null;
        AccessibilityNodeInfo root = null;
        try {
            root = getRootInActiveWindow();
            if (root == null) {
                Log.i(TAG, "Paste: no active window root");
                return;
            }

            target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (target == null) {
                target = findFocusedEditable(root);
            }
            if (target == null) {
                Log.i(TAG, "Paste: no focused editable field");
                return;
            }

            boolean pasted = target.performAction(AccessibilityNodeInfo.ACTION_PASTE);
            Log.i(TAG, "Paste: ACTION_PASTE dispatched, accepted=" + pasted);

            if (pasted && isAutoSendEnabled()
                    && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                final AccessibilityNodeInfo sendTarget = AccessibilityNodeInfo.obtain(target);
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    try {
                        int imeEnter = AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId();
                        boolean sent = sendTarget.performAction(imeEnter);
                        Log.i(TAG, "Paste: ACTION_IME_ENTER dispatched, accepted=" + sent);
                    } catch (Throwable t) {
                        Log.w(TAG, "Paste: auto-send failed", t);
                    } finally {
                        sendTarget.recycle();
                    }
                }, IME_ENTER_DELAY_MS);
            }

            autoDismissClipboardOverlay();
        } catch (Throwable t) {
            Log.w(TAG, "Paste: failed", t);
        } finally {
            if (target != null) target.recycle();
            if (root != null) root.recycle();
        }
    }

    private boolean isAutoSendEnabled() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        return prefs.getBoolean(PREF_AUTO_SEND, false);
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

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {}
}
