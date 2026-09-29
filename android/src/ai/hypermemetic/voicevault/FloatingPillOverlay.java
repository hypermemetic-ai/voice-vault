package ai.hypermemetic.voicevault;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

public class FloatingPillOverlay {
    private static final String TAG = "FloatingPillOverlay";
    private static final String PREFS_NAME = "voice_vault_prefs";
    private static final String PREF_DICTATION_MODE = "pref_dictation_mode_enabled";
    private static final Handler sMainHandler = new Handler(Looper.getMainLooper());
    private static final FloatingPillState sState = new FloatingPillState();

    private static Context sContext;
    private static WindowManager sWindowManager;
    private static View sPillView;
    private static View sDot;
    private static TextView sTvText;
    private static TextView sModeText;
    private static Runnable sDismissRunnable;

    /** Read the persisted preference, never a caller's possibly stale toggle value. */
    public static void refreshMode(Context context) {
        Context appContext = context.getApplicationContext();
        sMainHandler.post(() -> {
            sContext = appContext;
            render();
        });
    }

    public static void showRecording(Context context) {
        Context appContext = context.getApplicationContext();
        sMainHandler.post(() -> {
            sContext = appContext;
            cancelSuccessExpiry();
            sState.recording();
            render();
        });
    }

    public static void updateTimer(String timerText) {
        sMainHandler.post(() -> {
            sState.timer(timerText);
            render();
        });
    }

    public static void showTranscribing() {
        sMainHandler.post(() -> {
            cancelSuccessExpiry();
            sState.processing();
            render();
        });
    }

    public static void showSuccess(String message) {
        sMainHandler.post(() -> {
            cancelSuccessExpiry();
            sState.success(message);
            render();
            sDismissRunnable = () -> {
                sDismissRunnable = null;
                sState.idle();
                render();
            };
            sMainHandler.postDelayed(sDismissRunnable, 1400);
        });
    }

    public static void dismiss() {
        sMainHandler.post(() -> {
            cancelSuccessExpiry();
            sState.idle();
            render();
        });
    }

    private static void cancelSuccessExpiry() {
        if (sDismissRunnable != null) {
            sMainHandler.removeCallbacks(sDismissRunnable);
            sDismissRunnable = null;
        }
    }

    /** All state and view changes are serialized on the main thread. */
    private static void render() {
        if (sContext == null) return;
        sState.setDictationMode(sContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(PREF_DICTATION_MODE, false));
        if (!sState.isVisible() || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && !Settings.canDrawOverlays(sContext))) {
            removeWindow();
            return;
        }

        try {
            if (sPillView == null) {
                sWindowManager = (WindowManager) sContext.getSystemService(Context.WINDOW_SERVICE);
                if (sWindowManager == null) return;
                sPillView = LayoutInflater.from(sContext).inflate(R.layout.overlay_floating_pill, null);
                sDot = sPillView.findViewById(R.id.pill_dot);
                sTvText = sPillView.findViewById(R.id.pill_text);
                sModeText = sPillView.findViewById(R.id.pill_mode);

                int layoutFlag = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE;
                int resourceId = sContext.getResources().getIdentifier("status_bar_height", "dimen", "android");
                int statusBarHeight = resourceId > 0
                        ? sContext.getResources().getDimensionPixelSize(resourceId)
                        : dp(48);
                WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        layoutFlag,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.TRANSLUCENT);
                // Android 12+ rejects pass-through touches through untrusted overlays
                // above the maximum obscuring opacity (0.8 for one overlay).
                params.alpha = 0.7f;
                params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
                params.y = statusBarHeight + dp(18);
                updateViews();
                sWindowManager.addView(sPillView, params);
            } else {
                updateViews();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error showing floating pill overlay", e);
            removeWindow();
        }
    }

    private static int dp(int value) {
        return Math.round(value * sContext.getResources().getDisplayMetrics().density);
    }

    private static void updateViews() {
        boolean idle = sState.phase() == FloatingPillState.Phase.IDLE;
        sPillView.setMinimumWidth(idle ? 0 : dp(110));
        sPillView.setMinimumHeight(dp(idle ? 28 : 40));
        sDot.setVisibility(idle ? View.GONE : View.VISIBLE);
        sTvText.setVisibility(idle ? View.GONE : View.VISIBLE);
        sModeText.setVisibility(sState.isDictationMode() ? View.VISIBLE : View.GONE);
        if (idle) return;

        sTvText.setText(sState.text());
        switch (sState.phase()) {
            case RECORDING:
                sDot.setBackgroundResource(R.drawable.ic_recording_dot);
                sTvText.setTextSize(15f);
                sTvText.setTextColor(Color.WHITE);
                break;
            case PROCESSING:
                sDot.setBackgroundResource(R.drawable.ic_transcribing_dot);
                sTvText.setTextSize(13f);
                sTvText.setTextColor(Color.parseColor("#E0E0E0"));
                break;
            case SUCCESS:
                sDot.setBackgroundResource(R.drawable.ic_success_dot);
                sTvText.setTextSize(14f);
                sTvText.setTextColor(Color.parseColor("#4ADE80"));
                break;
            default:
                break;
        }
    }

    private static void removeWindow() {
        if (sPillView != null && sWindowManager != null) {
            try {
                sWindowManager.removeView(sPillView);
            } catch (Exception e) {
                try {
                    sWindowManager.removeViewImmediate(sPillView);
                } catch (Exception ignored) {}
            }
        }
        sPillView = null;
        sDot = null;
        sTvText = null;
        sModeText = null;
    }
}
