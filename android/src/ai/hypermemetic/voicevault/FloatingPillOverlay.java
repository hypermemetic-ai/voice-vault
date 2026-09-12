package ai.hypermemetic.voicevault;

import android.content.Context;
import android.content.Intent;
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

    private static WindowManager sWindowManager = null;
    private static View sPillView = null;
    private static View sDot = null;
    private static TextView sTvText = null;
    private static Handler sMainHandler = new Handler(Looper.getMainLooper());
    private static Runnable sDismissRunnable = null;

    public static void showRecording(Context context) {
        sMainHandler.post(() -> {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (!Settings.canDrawOverlays(context)) {
                        Log.d(TAG, "Cannot draw overlays: permission not granted");
                        return;
                    }
                }

                dismissInternal();

                sWindowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
                if (sWindowManager == null) return;

                LayoutInflater inflater = LayoutInflater.from(context);
                sPillView = inflater.inflate(R.layout.overlay_floating_pill, null);
                sDot = sPillView.findViewById(R.id.pill_dot);
                sTvText = sPillView.findViewById(R.id.pill_text);

                if (sDot != null) {
                    sDot.setBackgroundResource(R.drawable.ic_recording_dot);
                    sDot.setVisibility(View.VISIBLE);
                }
                sTvText.setText("00:00");
                sTvText.setTextSize(15f);
                sTvText.setTextColor(Color.WHITE);

                int layoutFlag = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE;

                // Position safely below Pixel camera punch-hole and status bar
                int resourceId = context.getResources().getIdentifier("status_bar_height", "dimen", "android");
                int statusBarHeight = (resourceId > 0)
                        ? context.getResources().getDimensionPixelSize(resourceId)
                        : Math.round(48 * context.getResources().getDisplayMetrics().density);
                int yOffset = statusBarHeight + Math.round(18 * context.getResources().getDisplayMetrics().density);

                int heightPx = Math.round(40 * context.getResources().getDisplayMetrics().density);
                WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        heightPx,
                        layoutFlag,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.TRANSLUCENT
                );

                params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
                params.y = yOffset;

                // Tap on the floating box stops recording immediately
                sPillView.setOnClickListener(v -> {
                    Intent stopIntent = new Intent(context, VoiceVaultService.class);
                    stopIntent.setAction(VoiceVaultService.ACTION_STOP);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(stopIntent);
                    } else {
                        context.startService(stopIntent);
                    }
                });

                sWindowManager.addView(sPillView, params);

            } catch (Exception e) {
                Log.e(TAG, "Error showing floating text overlay", e);
            }
        });
    }

    public static void updateTimer(String timerText) {
        sMainHandler.post(() -> {
            try {
                if (sTvText != null && sPillView != null) {
                    sTvText.setText(timerText);
                }
            } catch (Exception ignored) {}
        });
    }

    public static void showTranscribing() {
        sMainHandler.post(() -> {
            try {
                if (sPillView != null) {
                    if (sDot != null) {
                        sDot.setBackgroundResource(R.drawable.ic_transcribing_dot);
                        sDot.setVisibility(View.VISIBLE);
                    }
                    if (sTvText != null) {
                        sTvText.setText("Processing");
                        sTvText.setTextSize(13f);
                        sTvText.setTextColor(Color.parseColor("#E0E0E0"));
                    }
                }
            } catch (Exception ignored) {}
        });
    }

    public static void showSuccess(String message) {
        sMainHandler.post(() -> {
            try {
                if (sPillView != null) {
                    if (sDot != null) {
                        sDot.setBackgroundResource(R.drawable.ic_success_dot);
                        sDot.setVisibility(View.VISIBLE);
                    }
                    if (sTvText != null) {
                        sTvText.setText(message != null ? message : "Copied");
                        sTvText.setTextSize(14f);
                        sTvText.setTextColor(Color.parseColor("#4ADE80")); // Clean crisp green
                    }

                    if (sDismissRunnable != null) {
                        sMainHandler.removeCallbacks(sDismissRunnable);
                    }
                    sDismissRunnable = FloatingPillOverlay::dismissInternal;
                    sMainHandler.postDelayed(sDismissRunnable, 1400);
                } else {
                    dismissInternal();
                }
            } catch (Exception ignored) {}
        });
    }

    public static void dismiss() {
        sMainHandler.post(FloatingPillOverlay::dismissInternal);
    }

    private static void dismissInternal() {
        if (sDismissRunnable != null) {
            sMainHandler.removeCallbacks(sDismissRunnable);
            sDismissRunnable = null;
        }
        if (sPillView != null && sWindowManager != null) {
            try {
                sWindowManager.removeView(sPillView);
            } catch (Exception e) {
                try {
                    sWindowManager.removeViewImmediate(sPillView);
                } catch (Exception ignored) {}
            }
            sPillView = null;
            sDot = null;
            sTvText = null;
        }
    }
}
