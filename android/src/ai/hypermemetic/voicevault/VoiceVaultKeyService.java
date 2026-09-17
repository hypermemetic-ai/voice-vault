package ai.hypermemetic.voicevault;

import android.accessibilityservice.AccessibilityService;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.List;
import java.util.Locale;

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
 *           optionally auto-sending with IME Enter and clicking the Send button.</li>
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
    private static final long CODEX_EXEC_DELAY_MS = 240;

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
     * window. Auto-focuses an editable field if none currently has focus.
     * Uses 0ms ACTION_SET_TEXT with ACTION_PASTE fallback.
     * Auto-sends via ACTION_IME_ENTER and findAndClickSendButton when enabled.
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
                return;
            }
            Log.i(TAG, "Paste: found target node: " + target.getClassName() + " / id=" + target.getViewIdResourceName() + " / editable=" + target.isEditable() + " / focused=" + target.isFocused());

            CharSequence clipText = null;
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null && cm.hasPrimaryClip()) {
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

                // Stage 2: Follow-up auto-send for Codex in Orca and terminal buffers.
                // In Codex in Orca, the first send transfers composer text into the terminal buffer;
                // a follow-up send is required to execute the prompt in the terminal.
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
                }, CODEX_EXEC_DELAY_MS);
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
     * (e.g. in Orca composer or hybrid web chat before manual tap).
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
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {}
}
