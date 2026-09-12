package ai.hypermemetic.voicevault;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;

/**
 * Always-accessible floating button that draws over all applications.
 */
public class FloatingBubbleService extends Service {
    private WindowManager mWindowManager;
    private ImageView mBubbleView;
    private WindowManager.LayoutParams mParams;
    private BroadcastReceiver mReceiver;

    @Override
    public void onCreate() {
        super.onCreate();
        mWindowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        mBubbleView = new ImageView(this);
        int size = Math.round(52 * getResources().getDisplayMetrics().density);
        mBubbleView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        updateBubbleVisual();

        int layoutFlag = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        mParams = new WindowManager.LayoutParams(
                size, size,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        );

        mParams.gravity = Gravity.TOP | Gravity.START;
        mParams.x = 16;
        mParams.y = 300;

        mBubbleView.setOnTouchListener(new View.OnTouchListener() {
            private int initialX;
            private int initialY;
            private float initialTouchX;
            private float initialTouchY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = mParams.x;
                        initialY = mParams.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        mParams.x = initialX + (int) (event.getRawX() - initialTouchX);
                        mParams.y = initialY + (int) (event.getRawY() - initialTouchY);
                        mWindowManager.updateViewLayout(mBubbleView, mParams);
                        return true;

                    case MotionEvent.ACTION_UP:
                        float deltaX = Math.abs(event.getRawX() - initialTouchX);
                        float deltaY = Math.abs(event.getRawY() - initialTouchY);
                        if (deltaX < 12 && deltaY < 12) {
                            // Tap!
                            toggleDictation();
                        }
                        return true;
                }
                return false;
            }
        });

        mWindowManager.addView(mBubbleView, mParams);

        mReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                updateBubbleVisual();
            }
        };
        IntentFilter filter = new IntentFilter(VoiceVaultService.BROADCAST_STATE_CHANGE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(mReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(mReceiver, filter);
        }
    }

    private void updateBubbleVisual() {
        if (mBubbleView == null) return;
        boolean recording = VoiceVaultService.isRecording();
        if (recording) {
            mBubbleView.setBackgroundResource(R.drawable.bg_btn_red);
            mBubbleView.setImageResource(R.drawable.ic_btn_stop_symbol);
        } else {
            mBubbleView.setBackgroundResource(R.drawable.bg_btn_green);
            mBubbleView.setImageResource(R.drawable.ic_btn_record_symbol);
        }
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
    public void onDestroy() {
        super.onDestroy();
        if (mBubbleView != null && mWindowManager != null) {
            mWindowManager.removeView(mBubbleView);
        }
        if (mReceiver != null) {
            try { unregisterReceiver(mReceiver); } catch (Exception ignored) {}
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
