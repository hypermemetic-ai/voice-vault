package ai.hypermemetic.voicevault;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {
    private static final int PERMISSION_REQ_CODE = 100;

    private ImageButton mBtnRecord;
    private TextView mTvTimer;
    private ProgressBar mPbGpuSpinner;
    private LinearLayout mLayoutTranscript;
    private EditText mEtTranscript;
    private Button mBtnCopy;
    private Button mBtnToggleOverlay;

    // Slide-Out Drawer Views
    private View mBtnOpenHistory;
    private View mBtnCloseDrawer;
    private View mDrawerScrim;
    private LinearLayout mDrawerPanel;
    private LinearLayout mLayoutHistoryContainer;
    private boolean mDrawerOpen = false;

    private Handler mHandler;
    private Runnable mTimerUpdater;
    private BroadcastReceiver mReceiver;
    private boolean mFloatingActive = false;

    private boolean handleQuickToggleIfNeeded(Intent intent) {
        if (intent == null) return false;

        android.net.Uri referrer = getReferrer();
        String ref = (referrer != null && referrer.getHost() != null) ? referrer.getHost().toLowerCase() : "";
        int flags = intent.getFlags();
        boolean fromNotification = intent.getBooleanExtra("from_notification", false);
        boolean explicitQuickToggle = intent.getBooleanExtra("quick_toggle", false);
        boolean fromSystemUi = ref.contains("systemui");
        boolean hasSourceBounds = intent.getSourceBounds() != null;
        boolean fromLauncher = (flags & Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED) != 0
                || ref.contains("launcher")
                || hasSourceBounds;

        boolean isQuickToggle = explicitQuickToggle || fromSystemUi || (!fromLauncher && !fromNotification);

        if (isQuickToggle) {
            if (Build.VERSION.SDK_INT >= 34) {
                overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0);
                overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0);
            } else {
                overridePendingTransition(0, 0);
            }

            Intent serviceIntent = new Intent(this, VoiceVaultService.class);
            if (!VoiceVaultService.isRecording()) {
                serviceIntent.setAction(VoiceVaultService.ACTION_START);
            } else {
                serviceIntent.setAction(VoiceVaultService.ACTION_STOP);
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }

            moveTaskToBack(true);
            finish();
            if (Build.VERSION.SDK_INT >= 34) {
                overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0);
            } else {
                overridePendingTransition(0, 0);
            }
            return true;
        }
        return false;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (handleQuickToggleIfNeeded(getIntent())) {
            return;
        }

        setContentView(R.layout.activity_main);

        mHandler = new Handler(Looper.getMainLooper());

        mBtnRecord = findViewById(R.id.btn_record);
        mTvTimer = findViewById(R.id.tv_timer);
        mPbGpuSpinner = findViewById(R.id.pb_gpu_spinner);
        mLayoutTranscript = findViewById(R.id.layout_transcript);
        mEtTranscript = findViewById(R.id.et_transcript);
        mBtnCopy = findViewById(R.id.btn_copy);
        mBtnToggleOverlay = findViewById(R.id.btn_toggle_overlay);

        // Drawer views
        mBtnOpenHistory = findViewById(R.id.btn_open_history);
        mBtnCloseDrawer = findViewById(R.id.btn_close_drawer);
        mDrawerScrim = findViewById(R.id.drawer_scrim);
        mDrawerPanel = findViewById(R.id.drawer_panel);
        mLayoutHistoryContainer = findViewById(R.id.layout_history_container);

        mBtnOpenHistory.setOnClickListener(v -> openDrawer());
        mBtnCloseDrawer.setOnClickListener(v -> closeDrawer());
        mDrawerScrim.setOnClickListener(v -> closeDrawer());

        mBtnRecord.setOnClickListener(v -> onButtonClicked());

        mBtnCopy.setOnClickListener(v -> {
            String text = mEtTranscript.getText().toString().trim();
            if (!text.isEmpty()) {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    ClipData clip = ClipData.newPlainText("Voice Vault", text);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        android.os.PersistableBundle extras = new android.os.PersistableBundle();
                        extras.putBoolean("com.android.systemui.SUPPRESS_CLIPBOARD_OVERLAY", true);
                        clip.getDescription().setExtras(extras);
                    }
                    cm.setPrimaryClip(clip);
                    VoiceVaultKeyService.autoDismissClipboardOverlay();
                    mBtnCopy.setText("COPIED");
                    mHandler.postDelayed(() -> mBtnCopy.setText("COPY"), 2000);
                }
            }
        });

        mBtnToggleOverlay.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } else {
                if (mFloatingActive) {
                    stopService(new Intent(this, FloatingBubbleService.class));
                    mFloatingActive = false;
                    mBtnToggleOverlay.setTextColor(0xFF555555);
                } else {
                    startService(new Intent(this, FloatingBubbleService.class));
                    mFloatingActive = true;
                    mBtnToggleOverlay.setTextColor(0xFF22C55E);
                }
            }
        });

        checkAndRequestPermissions();
        setupReceiver();
        syncWithServiceState();
    }

    private void openDrawer() {
        if (mDrawerOpen) return;
        mDrawerOpen = true;

        loadHistoryIntoDrawer();

        mDrawerScrim.setVisibility(View.VISIBLE);
        mDrawerScrim.setAlpha(0f);
        mDrawerScrim.animate().alpha(1f).setDuration(220).start();

        int drawerWidth = mDrawerPanel.getWidth();
        if (drawerWidth == 0) {
            drawerWidth = Math.round(320 * getResources().getDisplayMetrics().density);
        }
        mDrawerPanel.setVisibility(View.VISIBLE);
        mDrawerPanel.setTranslationX(-drawerWidth);
        mDrawerPanel.animate().translationX(0f).setDuration(220).start();
    }

    private void closeDrawer() {
        if (!mDrawerOpen) return;
        mDrawerOpen = false;

        int drawerWidth = mDrawerPanel.getWidth();
        if (drawerWidth == 0) {
            drawerWidth = Math.round(320 * getResources().getDisplayMetrics().density);
        }

        mDrawerScrim.animate().alpha(0f).setDuration(180)
                .withEndAction(() -> mDrawerScrim.setVisibility(View.GONE)).start();
        mDrawerPanel.animate().translationX(-drawerWidth).setDuration(180)
                .withEndAction(() -> mDrawerPanel.setVisibility(View.GONE)).start();
    }

    @Override
    public void onBackPressed() {
        if (mDrawerOpen) {
            closeDrawer();
            return;
        }
        super.onBackPressed();
    }

    private void loadHistoryIntoDrawer() {
        HistoryManager.fetchHistory(this, grouped -> {
            mHandler.post(() -> renderHistoryItems(grouped));
        });
    }

    private void renderHistoryItems(Map<String, List<HistoryManager.Entry>> grouped) {
        if (mLayoutHistoryContainer == null) return;
        mLayoutHistoryContainer.removeAllViews();

        if (grouped == null || grouped.isEmpty()) {
            TextView emptyTv = new TextView(this);
            emptyTv.setText("No transcripts recorded yet");
            emptyTv.setTextColor(Color.parseColor("#555555"));
            emptyTv.setTextSize(13f);
            try {
                emptyTv.setTypeface(getResources().getFont(R.font.urw_gothic_book));
            } catch (Exception ignored) {}
            emptyTv.setPadding(0, 40, 0, 0);
            mLayoutHistoryContainer.addView(emptyTv);
            return;
        }

        SimpleDateFormat timeFmt = new SimpleDateFormat("h:mm a", Locale.US);
        float density = getResources().getDisplayMetrics().density;

        for (Map.Entry<String, List<HistoryManager.Entry>> group : grouped.entrySet()) {
            String headerText = group.getKey();
            List<HistoryManager.Entry> items = group.getValue();
            if (items == null || items.isEmpty()) continue;

            // Date Section Header
            TextView tvHeader = new TextView(this);
            tvHeader.setText(headerText);
            tvHeader.setTextColor(Color.parseColor("#777777"));
            tvHeader.setTextSize(11f);
            tvHeader.setLetterSpacing(0.08f);
            try {
                tvHeader.setTypeface(getResources().getFont(R.font.urw_gothic_demi));
            } catch (Exception ignored) {}
            int padTop = Math.round(14 * density);
            int padBottom = Math.round(6 * density);
            tvHeader.setPadding(0, padTop, 0, padBottom);
            mLayoutHistoryContainer.addView(tvHeader);

            // Item Cards
            for (HistoryManager.Entry entry : items) {
                View card = createTranscriptCard(entry, timeFmt, density);
                mLayoutHistoryContainer.addView(card);
            }
        }
    }

    private View createTranscriptCard(HistoryManager.Entry entry, SimpleDateFormat timeFmt, float density) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_history_card);
        int pad = Math.round(12 * density);
        card.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        cardLp.bottomMargin = Math.round(8 * density);
        card.setLayoutParams(cardLp);
        card.setClickable(true);

        // Top Row: Time + Duration + Action
        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        String timeStr = timeFmt.format(new Date(entry.timestamp));
        TextView tvTime = new TextView(this);
        tvTime.setText(timeStr);
        tvTime.setTextColor(Color.parseColor("#999999"));
        tvTime.setTextSize(11f);
        try {
            tvTime.setTypeface(getResources().getFont(R.font.urw_gothic_demi));
        } catch (Exception ignored) {}
        topRow.addView(tvTime);

        if (entry.durationMs > 0) {
            long sec = Math.round(entry.durationMs / 1000.0);
            TextView tvDur = new TextView(this);
            tvDur.setText(" · " + sec + "s");
            tvDur.setTextColor(Color.parseColor("#22C55E"));
            tvDur.setTextSize(11f);
            try {
                tvDur.setTypeface(getResources().getFont(R.font.urw_gothic_demi));
            } catch (Exception ignored) {}
            topRow.addView(tvDur);
        }

        View spacer = new View(this);
        LinearLayout.LayoutParams spacerLp = new LinearLayout.LayoutParams(0, 1, 1f);
        topRow.addView(spacer, spacerLp);

        TextView tvCopyBadge = new TextView(this);
        tvCopyBadge.setText("COPY");
        tvCopyBadge.setTextColor(Color.parseColor("#555555"));
        tvCopyBadge.setTextSize(10f);
        try {
            tvCopyBadge.setTypeface(getResources().getFont(R.font.urw_gothic_demi));
        } catch (Exception ignored) {}
        topRow.addView(tvCopyBadge);

        card.addView(topRow);

        // Transcript Text
        TextView tvTranscript = new TextView(this);
        tvTranscript.setText(entry.transcript);
        tvTranscript.setTextColor(Color.parseColor("#EEEEEE"));
        tvTranscript.setTextSize(13.5f);
        tvTranscript.setLineSpacing(0, 1.25f);
        try {
            tvTranscript.setTypeface(getResources().getFont(R.font.urw_gothic_book));
        } catch (Exception ignored) {}
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        textLp.topMargin = Math.round(6 * density);
        card.addView(tvTranscript, textLp);

        // 1-Tap Copy Action
        card.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                ClipData clip = ClipData.newPlainText("Voice Vault", entry.transcript);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    android.os.PersistableBundle extras = new android.os.PersistableBundle();
                    extras.putBoolean("com.android.systemui.SUPPRESS_CLIPBOARD_OVERLAY", true);
                    clip.getDescription().setExtras(extras);
                }
                cm.setPrimaryClip(clip);
                VoiceVaultKeyService.autoDismissClipboardOverlay();
            }
            Vibrator vib = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (vib != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    vib.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK));
                } else {
                    vib.vibrate(50);
                }
            }
            tvCopyBadge.setText("✓ COPIED");
            tvCopyBadge.setTextColor(Color.parseColor("#22C55E"));
            mHandler.postDelayed(() -> {
                tvCopyBadge.setText("COPY");
                tvCopyBadge.setTextColor(Color.parseColor("#555555"));
            }, 1500);

            // Also populate the main screen transcript editor
            if (mEtTranscript != null && mLayoutTranscript != null) {
                mLayoutTranscript.setVisibility(View.VISIBLE);
                mEtTranscript.setText(entry.transcript);
            }
        });

        return card;
    }

    private void checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            boolean needAudio = checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED;
            boolean needNotif = false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                needNotif = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED;
            }

            if (needAudio || needNotif) {
                if (needNotif) {
                    requestPermissions(new String[]{
                            Manifest.permission.RECORD_AUDIO,
                            Manifest.permission.POST_NOTIFICATIONS
                    }, PERMISSION_REQ_CODE);
                } else {
                    requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSION_REQ_CODE);
                }
            }
        }
    }

    private void setupReceiver() {
        mReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                syncWithServiceState();
                String action = intent != null ? intent.getAction() : null;
                if (VoiceVaultService.BROADCAST_TRANSCRIPT.equals(action)) {
                    String text = intent.getStringExtra("text");
                    if (text != null && !text.isEmpty()) {
                        mLayoutTranscript.setVisibility(View.VISIBLE);
                        mEtTranscript.setText(text);
                        if (mDrawerOpen) {
                            loadHistoryIntoDrawer();
                        }
                    }
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(VoiceVaultService.BROADCAST_STATE_CHANGE);
        filter.addAction(VoiceVaultService.BROADCAST_TRANSCRIPT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(mReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(mReceiver, filter);
        }
    }

    private void onButtonClicked() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                checkAndRequestPermissions();
                return;
            }
        }

        boolean recording = VoiceVaultService.isRecording();
        Intent intent = new Intent(this, VoiceVaultService.class);
        if (!recording) {
            renderRecordingState();
            intent.setAction(VoiceVaultService.ACTION_START);
        } else {
            renderIdleState();
            mPbGpuSpinner.setVisibility(View.VISIBLE);
            intent.setAction(VoiceVaultService.ACTION_STOP);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void syncWithServiceState() {
        boolean recording = VoiceVaultService.isRecording();
        boolean processing = VoiceVaultService.isProcessing();

        if (processing) {
            mPbGpuSpinner.setVisibility(View.VISIBLE);
        } else {
            mPbGpuSpinner.setVisibility(View.GONE);
        }

        if (recording) {
            renderRecordingState();
        } else {
            renderIdleState();
        }
    }

    private void renderRecordingState() {
        mBtnRecord.setBackgroundResource(R.drawable.bg_btn_red);
        mBtnRecord.setImageResource(R.drawable.ic_btn_stop_symbol);

        startTimerUpdater();
    }

    private void renderIdleState() {
        mBtnRecord.setBackgroundResource(R.drawable.bg_btn_green);
        mBtnRecord.setImageResource(R.drawable.ic_btn_record_symbol);

        stopTimerUpdater();
        mTvTimer.setText("00:00");
    }

    private void startTimerUpdater() {
        stopTimerUpdater();
        mTimerUpdater = new Runnable() {
            @Override
            public void run() {
                if (VoiceVaultService.isRecording()) {
                    long elapsed = System.currentTimeMillis() - VoiceVaultService.getRecordingStartTime();
                    long sec = (elapsed / 1000) % 60;
                    long min = (elapsed / 1000) / 60;
                    mTvTimer.setText(String.format("%02d:%02d", min, sec));
                    mHandler.postDelayed(this, 500);
                } else {
                    mTvTimer.setText("00:00");
                }
            }
        };
        mHandler.post(mTimerUpdater);
    }

    private void stopTimerUpdater() {
        if (mTimerUpdater != null) {
            mHandler.removeCallbacks(mTimerUpdater);
            mTimerUpdater = null;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        syncWithServiceState();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (handleQuickToggleIfNeeded(intent)) {
            return;
        }
        syncWithServiceState();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopTimerUpdater();
        if (mReceiver != null) {
            try { unregisterReceiver(mReceiver); } catch (Exception ignored) {}
        }
    }
}
