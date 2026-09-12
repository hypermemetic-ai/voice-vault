package ai.hypermemetic.voicevault;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class VoiceVaultService extends Service {
    private static final String TAG = "VoiceVaultService";
    private static final String CHANNEL_ID = "voice_vault_recording";
    private static final int NOTIFICATION_ID = 4242;

    public static final String ACTION_START = "ai.hypermemetic.voicevault.START";
    public static final String ACTION_STOP = "ai.hypermemetic.voicevault.STOP";
    public static final String ACTION_CANCEL = "ai.hypermemetic.voicevault.CANCEL";

    public static final String BROADCAST_STATE_CHANGE = "ai.hypermemetic.voicevault.STATE_CHANGED";
    public static final String BROADCAST_TRANSCRIPT = "ai.hypermemetic.voicevault.TRANSCRIPT";

    private static volatile boolean sIsRecording = false;
    private static volatile boolean sIsProcessing = false;
    private static volatile long sRecordingStartTime = 0;
    private static volatile String sLastTranscript = "";

    private MediaRecorder mRecorder = null;
    private File mCurrentAudioFile = null;
    private PowerManager.WakeLock mWakeLock = null;
    private Handler mHandler = null;
    private Runnable mTimerRunnable = null;
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();

    public static boolean isRecording() {
        return sIsRecording;
    }

    public static boolean isProcessing() {
        return sIsProcessing;
    }

    public static long getRecordingStartTime() {
        return sRecordingStartTime;
    }

    public static String getLastTranscript() {
        return sLastTranscript;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mHandler = new Handler(Looper.getMainLooper());
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;

        if (ACTION_STOP.equals(action)) {
            stopAndTranscribe();
        } else if (ACTION_CANCEL.equals(action)) {
            cancelRecording();
        } else if (ACTION_START.equals(action)) {
            if (!sIsRecording && !sIsProcessing) {
                startRecording();
            }
        }
        return START_NOT_STICKY;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.channel_name),
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription(getString(R.string.channel_desc));
            channel.enableVibration(false);
            channel.enableLights(true);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildRecordingNotification(String timerText) {
        Intent stopIntent = new Intent(this, VoiceVaultService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent piStop = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent cancelIntent = new Intent(this, VoiceVaultService.class);
        cancelIntent.setAction(ACTION_CANCEL);
        PendingIntent piCancel = PendingIntent.getService(this, 2, cancelIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent activityIntent = new Intent(this, MainActivity.class);
        activityIntent.putExtra("from_notification", true);
        activityIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent piActivity = PendingIntent.getActivity(this, 0, activityIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        builder.setContentTitle("🔴 Voice Vault · Recording…")
                .setContentText(timerText + " — Tap Stop when finished")
                .setSmallIcon(R.drawable.ic_mic)
                .setContentIntent(piActivity)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        R.drawable.ic_stop,
                        getString(R.string.action_stop),
                        piStop
                ).build())
                .addAction(new Notification.Action.Builder(
                        R.drawable.ic_stop,
                        getString(R.string.action_cancel),
                        piCancel
                ).build());

        return builder.build();
    }

    private Notification buildTranscribingNotification() {
        Notification.Builder builder = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        builder.setContentTitle("Voice Vault · Transcribing…")
                .setContentText("Processing on RTX A2000 Whisper Turbo")
                .setSmallIcon(R.drawable.ic_mic)
                .setProgress(0, 0, true)
                .setOngoing(true);

        return builder.build();
    }

    private void postCompletedNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;

        Intent activityIntent = new Intent(this, MainActivity.class);
        activityIntent.putExtra("from_notification", true);
        activityIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent piActivity = PendingIntent.getActivity(this, 0, activityIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        String preview = text.length() > 60 ? text.substring(0, 57) + "…" : text;
        builder.setContentTitle("🟢 Copied to Clipboard!")
                .setContentText(preview)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setSmallIcon(R.drawable.ic_mic)
                .setContentIntent(piActivity)
                .setAutoCancel(true);

        nm.notify(NOTIFICATION_ID + 1, builder.build());
    }

    private void playStrongStartFeedback() {
        // 1. Tactile haptic punch
        Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (v != null && v.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK));
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(100, 255));
            } else {
                v.vibrate(100);
            }
        }

        // 2. Instant low-latency 35ms liquid pop (<3ms audio latency)
        SoundEffects.playStartPop();

        // 3. Dynamic top pill overlay with live ticking timer
        FloatingPillOverlay.showRecording(this);
    }

    private void playStrongSuccessFeedback() {
        // 1. Authoritative double haptic pulse at maximum intensity
        Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (v != null && v.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                long[] timings = new long[]{0, 100, 60, 130};
                int[] amplitudes = new int[]{0, 255, 0, 255};
                v.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1));
            } else {
                v.vibrate(new long[]{0, 100, 60, 130}, -1);
            }
        }

        // 2. Crisp 65ms dual-harmonic glass bell chime
        SoundEffects.playSuccessChime();

        // 3. Top pill displays green confirmation checkmark and auto-dismisses
        FloatingPillOverlay.showSuccess("✓ Copied");
    }

    private void startRecording() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VoiceVault::RecordingWakeLock");
                mWakeLock.acquire(3 * 60 * 60 * 1000L); // 3-hour safeguard for arbitrarily long recordings
            }

            File dir = getExternalFilesDir("recordings");
            if (dir == null) dir = getFilesDir();
            dir.mkdirs();

            mCurrentAudioFile = new File(dir, "dictation_" + System.currentTimeMillis() + ".m4a");

            mRecorder = new MediaRecorder();
            // VOICE_RECOGNITION activates the Pixel's multi-microphone
            // beamforming / target-voice capture path, which steers a beam at
            // the talker in front of the device instead of picking up the whole
            // room the way AudioSource.MIC does. This is the client-side half of
            // the two-part speaker-rejection pipeline (server voiceprint gate is
            // the other half).
            mRecorder.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION);
            mRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mRecorder.setAudioEncodingBitRate(96000);
            mRecorder.setAudioSamplingRate(16000);
            mRecorder.setOutputFile(mCurrentAudioFile.getAbsolutePath());
            mRecorder.prepare();

            sIsRecording = true;
            sIsProcessing = false;
            sRecordingStartTime = System.currentTimeMillis();

            Notification notification = buildRecordingNotification("00:00");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }

            // Fire authoritative multi-modal feedback (Haptic + Sound + Visual Toast)
            playStrongStartFeedback();

            // Delay microphone start 90ms so start chirp is never recorded into user audio
            mHandler.postDelayed(() -> {
                try {
                    if (sIsRecording && mRecorder != null) {
                        mRecorder.start();
                    }
                } catch (Exception ex) {
                    Log.e(TAG, "Error starting recorder delayed", ex);
                }
            }, 90);

            mTimerRunnable = new Runnable() {
                @Override
                public void run() {
                    if (sIsRecording) {
                        long elapsed = System.currentTimeMillis() - sRecordingStartTime;
                        long sec = (elapsed / 1000) % 60;
                        long min = (elapsed / 1000) / 60;
                        String formatted = String.format("%02d:%02d", min, sec);
                        NotificationManager nm = getSystemService(NotificationManager.class);
                        if (nm != null) {
                            nm.notify(NOTIFICATION_ID, buildRecordingNotification(formatted));
                        }
                        FloatingPillOverlay.updateTimer(formatted);
                        sendExplicitBroadcast(new Intent(BROADCAST_STATE_CHANGE));
                        mHandler.postDelayed(this, 1000);
                    }
                }
            };
            mHandler.postDelayed(mTimerRunnable, 1000);
            sendExplicitBroadcast(new Intent(BROADCAST_STATE_CHANGE));

        } catch (Exception e) {
            Log.e(TAG, "Failed to start recording", e);
            sIsRecording = false;
            stopForeground(true);
            FloatingPillOverlay.dismiss();
        }
    }

    private void stopAndTranscribe() {
        if (!sIsRecording) return;
        sIsRecording = false;
        sIsProcessing = true;

        if (mTimerRunnable != null) {
            mHandler.removeCallbacks(mTimerRunnable);
        }

        final long durationMs = System.currentTimeMillis() - sRecordingStartTime;

        try {
            if (mRecorder != null) {
                mRecorder.stop();
                mRecorder.release();
                mRecorder = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error stopping recorder", e);
        }

        // Update floating pill to show sleek transcribing status
        FloatingPillOverlay.showTranscribing();

        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildTranscribingNotification());
        }
        sendExplicitBroadcast(new Intent(BROADCAST_STATE_CHANGE));

        final File audioFile = mCurrentAudioFile;
        mExecutor.execute(() -> {
            try {
                if (audioFile == null || !audioFile.exists() || audioFile.length() == 0) {
                    throw new Exception("Recorded audio file is empty");
                }

                String endpoint = "https://qq-box.tail580136.ts.net:3443/api/transcribe";
                URL url = new URL(endpoint);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setConnectTimeout(30000);
                conn.setReadTimeout(600000); // 10 minutes for long recordings
                conn.setRequestProperty("Content-Type", "audio/mp4");
                conn.setRequestProperty("X-Duration-Ms", String.valueOf(durationMs));
                conn.setRequestProperty("X-Device", "Pixel 10 Native");
                conn.setFixedLengthStreamingMode((int) audioFile.length());

                try (OutputStream os = conn.getOutputStream();
                     FileInputStream fis = new FileInputStream(audioFile)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = fis.read(buffer)) != -1) {
                        os.write(buffer, 0, read);
                    }
                    os.flush();
                }

                int code = conn.getResponseCode();
                if (code == 200) {
                    InputStream is = conn.getInputStream();
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int len;
                    while ((len = is.read(buf)) != -1) {
                        baos.write(buf, 0, len);
                    }
                    String responseBody = baos.toString("UTF-8");
                    JSONObject json = new JSONObject(responseBody);
                    String text = json.optString("text", "");

                    mHandler.post(() -> onTranscriptionSuccess(text, durationMs));
                } else {
                    throw new Exception("HTTP error " + code);
                }

            } catch (Exception e) {
                Log.e(TAG, "Transcription failed", e);
                mHandler.post(() -> {
                    sIsProcessing = false;
                    FloatingPillOverlay.showSuccess("Error");
                    cleanup();
                });
            }
        });
    }

    private void onTranscriptionSuccess(String text, long durationMs) {
        sIsProcessing = false;
        if (text != null && !text.trim().isEmpty()) {
            sLastTranscript = text.trim();

            // Auto copy to Android system clipboard
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                ClipData clip = ClipData.newPlainText("Voice Vault", sLastTranscript);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    android.os.PersistableBundle extras = new android.os.PersistableBundle();
                    extras.putBoolean("com.android.systemui.SUPPRESS_CLIPBOARD_OVERLAY", true);
                    clip.getDescription().setExtras(extras);
                }
                cm.setPrimaryClip(clip);

                // Auto-dismiss the SystemUI bottom-left overlay immediately if accessibility is active
                VoiceVaultKeyService.autoDismissClipboardOverlay();
            }

            // Combined authoritative completion feedback: Double Buzz + Instant Chime + Pill Checkmark
            playStrongSuccessFeedback();

            // Save transcript in local history cache
            HistoryManager.saveLocalTranscript(this, sLastTranscript, durationMs);
            HistoryManager.pruneLocalRecordings(this);

            // Explicit broadcast to MainActivity
            Intent intent = new Intent(BROADCAST_TRANSCRIPT);
            intent.putExtra("text", sLastTranscript);
            intent.putExtra("durationMs", durationMs);
            sendExplicitBroadcast(intent);
        } else {
            FloatingPillOverlay.showSuccess("No speech");
        }
        cleanup();
    }

    private void cancelRecording() {
        sIsRecording = false;
        sIsProcessing = false;
        if (mTimerRunnable != null) {
            mHandler.removeCallbacks(mTimerRunnable);
        }
        try {
            if (mRecorder != null) {
                mRecorder.stop();
                mRecorder.release();
                mRecorder = null;
            }
            if (mCurrentAudioFile != null && mCurrentAudioFile.exists()) {
                mCurrentAudioFile.delete();
            }
        } catch (Exception ignored) {}
        FloatingPillOverlay.dismiss();
        cleanup();
        sendExplicitBroadcast(new Intent(BROADCAST_STATE_CHANGE));
    }

    private void cleanup() {
        sIsProcessing = false;
        if (mWakeLock != null && mWakeLock.isHeld()) {
            mWakeLock.release();
            mWakeLock = null;
        }
        stopForeground(true);
        stopSelf();
        sendExplicitBroadcast(new Intent(BROADCAST_STATE_CHANGE));
    }

    private void sendExplicitBroadcast(Intent intent) {
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        cleanup();
        mExecutor.shutdown();
        super.onDestroy();
    }
}
