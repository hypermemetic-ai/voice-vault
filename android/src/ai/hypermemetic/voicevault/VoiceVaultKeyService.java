package ai.hypermemetic.voicevault;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Build;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Hardware side-button listener:
 * Double-pressing the physical Volume Down key on the phone side toggles dictation!
 */
public class VoiceVaultKeyService extends AccessibilityService {
    private static final long DOUBLE_PRESS_WINDOW_MS = 450;
    private long mLastVolumeDownTime = 0;

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        int action = event.getAction();

        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && action == KeyEvent.ACTION_DOWN) {
            long now = System.currentTimeMillis();
            if (now - mLastVolumeDownTime < DOUBLE_PRESS_WINDOW_MS) {
                // Hardware double-tap on side button detected!
                mLastVolumeDownTime = 0;
                toggleDictation();
                return true; // Consume event to prevent volume change
            }
            mLastVolumeDownTime = now;
        }

        return super.onKeyEvent(event);
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

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {}
}
