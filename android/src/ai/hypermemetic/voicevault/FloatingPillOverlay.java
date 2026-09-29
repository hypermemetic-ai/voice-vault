package ai.hypermemetic.voicevault;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

/** Display-only status and persistent dictation-mode badge. The recording controls live elsewhere. */
public class FloatingPillOverlay {
    private static final String TAG = "FloatingPillOverlay";
    private static final String PREFS_NAME = "voice_vault_prefs";
    private static final String PREF_DICTATION_MODE = "pref_dictation_mode_enabled";
    private static final Handler sMainHandler = new Handler(Looper.getMainLooper());
    private static final FloatingStatus sStatus = new FloatingStatus();
    private static Context sContext;
    private static WindowManager sWindowManager;
    private static View sPillView;
    private static View sDot;
    private static TextView sTvText;
    private static TextView sModeText;
    private static final Runnable sRefresh = FloatingPillOverlay::render;

    /** Read the persisted mode, not a caller's potentially stale toggle value. */
    public static void refreshMode(Context context) {
        sMainHandler.post(() -> {
            sContext = context.getApplicationContext();
            render();
        });
    }

    public static void showRecording(Context context) {
        sMainHandler.post(() -> {
            sContext = context.getApplicationContext();
            sStatus.recording();
            render();
        });
    }

    public static void updateTimer(String timerText) {
        sMainHandler.post(() -> {
            sStatus.timer(timerText);
            render();
        });
    }

    public static void showTranscribing() {
        sMainHandler.post(() -> {
            sStatus.processing();
            render();
        });
    }

    public static void showSuccess(String message) {
        sMainHandler.post(() -> {
            sStatus.success(message, SystemClock.uptimeMillis());
            render();
        });
    }

    /** Temporarily replaces the text in the existing brick, including while idle. */
    public static void showFeedback(Context context, String message, boolean enabled) {
        sMainHandler.post(() -> {
            sContext = context.getApplicationContext();
            sStatus.feedback(message, enabled, SystemClock.uptimeMillis());
            render();
        });
    }

    public static void dismiss() {
        sMainHandler.post(() -> {
            sStatus.dismissBase();
            render();
        });
    }

    /** State and window changes are serialized on the main thread. Mode never resets status expiry. */
    private static void render() {
        sMainHandler.removeCallbacks(sRefresh);
        long now = SystemClock.uptimeMillis();
        FloatingStatus.Snapshot status = sStatus.snapshot(now);
        boolean mode = sContext != null && sContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(PREF_DICTATION_MODE, false);
        if ((status.kind == FloatingStatus.Kind.IDLE && !mode) || sContext == null
                || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(sContext))) {
            removeView();
            return;
        }
        try {
            if (!ensureView()) return;
            boolean idle = status.kind == FloatingStatus.Kind.IDLE;
            boolean recording = status.kind == FloatingStatus.Kind.RECORDING;
            boolean processing = status.kind == FloatingStatus.Kind.PROCESSING;
            boolean success = status.kind == FloatingStatus.Kind.SUCCESS;
            int minHeight = Math.round((idle ? 28 : 40) * sContext.getResources().getDisplayMetrics().density);
            sPillView.setMinimumHeight(minHeight);
            sPillView.setMinimumWidth(idle ? 0 : Math.round(110 * sContext.getResources().getDisplayMetrics().density));
            sModeText.setVisibility(mode && idle ? View.VISIBLE : View.GONE);
            sTvText.setVisibility(idle ? View.GONE : View.VISIBLE);
            sTvText.setText(status.text);
            sTvText.setTextSize(recording ? 15f : 14f);
            sTvText.setTextColor(success || status.kind == FloatingStatus.Kind.ENABLED
                    ? Color.parseColor("#4ADE80") : Color.WHITE);
            sDot.setVisibility(recording || processing || success ? View.VISIBLE : View.GONE);
            if (!idle) sDot.setBackgroundResource(recording ? R.drawable.ic_recording_dot
                    : processing ? R.drawable.ic_transcribing_dot : R.drawable.ic_success_dot);
            if (status.expiresAt > now) sMainHandler.postDelayed(sRefresh, status.expiresAt - now);
        } catch (Exception e) {
            Log.e(TAG, "Error updating floating text overlay", e);
            removeView();
        }
    }

    private static boolean ensureView() {
        if (sPillView != null) return true;
        sWindowManager = (WindowManager) sContext.getSystemService(Context.WINDOW_SERVICE);
        if (sWindowManager == null) return false;
        sPillView = LayoutInflater.from(sContext).inflate(R.layout.overlay_floating_pill, null);
        sDot = sPillView.findViewById(R.id.pill_dot);
        sTvText = sPillView.findViewById(R.id.pill_text);
        sModeText = sPillView.findViewById(R.id.pill_mode);

        int layoutFlag = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        float density = sContext.getResources().getDisplayMetrics().density;
        int resourceId = sContext.getResources().getIdentifier("status_bar_height", "dimen", "android");
        int statusBarHeight = resourceId > 0
                ? sContext.getResources().getDimensionPixelSize(resourceId) : Math.round(48 * density);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        // Android 12+ rejects touches through an untrusted overlay above 0.8 obscuring opacity.
        // Keep the solid backdrop and text together below that threshold.
        params.alpha = 0.79f;
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        params.y = statusBarHeight + Math.round(18 * density);
        sWindowManager.addView(sPillView, params);
        return true;
    }

    private static void removeView() {
        if (sPillView != null && sWindowManager != null) {
            try {
                sWindowManager.removeView(sPillView);
            } catch (Exception e) {
                try { sWindowManager.removeViewImmediate(sPillView); } catch (Exception ignored) {}
            }
        }
        sPillView = null;
        sDot = null;
        sTvText = null;
        sModeText = null;
    }
}
