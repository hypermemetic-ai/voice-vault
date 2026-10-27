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
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class VoiceVaultService extends Service {
    private static final String TAG = "VoiceVaultService";
    private static final String CHANNEL_ID = "voice_vault_recording";
    private static final int NOTIFICATION_ID = 4242;

    public static final String ACTION_START = "ai.hypermemetic.voicevault.START";
    public static final String ACTION_STOP = "ai.hypermemetic.voicevault.STOP";
    public static final String ACTION_RECOVER = "ai.hypermemetic.voicevault.RECOVER";
    public static final String EXTRA_RECORDING_ID = "recording_uuid";
    public static final String ACTION_CANCEL = "ai.hypermemetic.voicevault.CANCEL";

    public static final String BROADCAST_STATE_CHANGE = "ai.hypermemetic.voicevault.STATE_CHANGED";
    public static final String BROADCAST_TRANSCRIPT = "ai.hypermemetic.voicevault.TRANSCRIPT";

    private static volatile boolean sIsRecording = false;
    private static volatile boolean sIsProcessing = false;
    private static volatile long sRecordingStartTime = 0;
    private static volatile String sLastTranscript = "";
    private static volatile String sRecoveryId = "", sCaptureId = "";
    public static String getActiveRecordingId() { return sCaptureId; }
    public static String getRecoveryId() { return sRecoveryId; }

    private static final int SAMPLE_RATE = 16000;
    private static final int CAPTURE_BUFFER_BYTES = 3200; // 100 ms of 16 kHz mono PCM16
    private static final long STREAM_FLUSH_TIMEOUT_MS = 10000L;

    private AudioRecord mRecorder = null;
    private Thread mCaptureThread = null;
    private FileOutputStream mCaptureOut = null;
    private volatile boolean mCaptureRunning = false;
    private volatile long mPcmBytes = 0;
    private volatile Exception mCaptureError = null;
    private boolean mWavFinalized = false;
    private RecordingStreamer mStreamer = null;
    private File mCurrentAudioFile = null;
    private PowerManager.WakeLock mWakeLock = null;
    private Handler mHandler = null;
    private Runnable mTimerRunnable = null;
    private Runnable mRecorderStartRunnable = null;
    private Runnable mProcessingTimeoutRunnable = null;
    private boolean mRecorderStarted = false;
    private DictationUpload mUpload = null;
    private static final long PROCESSING_TIMEOUT_MS = 600000L;
    private int mGeneration = 0;
    private RecordingStore mRecordingStore;
    private RecordingIndex.Entry mRecordingEntry;
    private String mRecoveryId = "";
    private boolean mRecoveryOperation;
    private final ExecutorService mExecutor = Executors.newCachedThreadPool();

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

        if (ACTION_RECOVER.equals(action)) {
            recoverRecording(intent.getStringExtra(EXTRA_RECORDING_ID));
        } else if (ACTION_STOP.equals(action)) {
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
                    NotificationManager.IMPORTANCE_MIN
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
                .setOnlyAlertOnce(true)
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

        Intent cancel=new Intent(this,VoiceVaultService.class).setAction(ACTION_CANCEL);
        builder.addAction(new Notification.Action.Builder(R.drawable.ic_stop,"Cancel transcription",
            PendingIntent.getService(this,2,cancel,PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)).build());
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

        // 2. Dynamic top pill overlay with live ticking timer
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

        // 2. Top pill displays green confirmation checkmark and auto-dismisses
        FloatingPillOverlay.showSuccess("✓ Copied");
    }

    private void startRecording() {
        mRecoveryOperation=false;
        final int generation = ++mGeneration;
        sRecordingStartTime = System.currentTimeMillis();
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VoiceVault::RecordingWakeLock");
                mWakeLock.acquire(3 * 60 * 60 * 1000L); // 3-hour safeguard for arbitrarily long recordings
            }

            File dir = getExternalFilesDir("recordings");
            if (dir == null) dir = getFilesDir();
            dir.mkdirs();

            mCurrentAudioFile = new File(dir, "dictation_" + sRecordingStartTime + ".wav");
            try {
                mRecordingStore=RecordingStore.get(this);
                synchronized(mRecordingStore) {
                    sIsRecording=true;mRecordingEntry=mRecordingStore.capture(mCurrentAudioFile,sRecordingStartTime);sCaptureId=mRecordingEntry.id;
                }
            } catch(Exception storage) {
                failDictation(sRecordingStartTime,"Recording storage failed — audio is not safely saved","storage_failure");return;
            }

            // VOICE_RECOGNITION activates the Pixel's multi-microphone
            // beamforming / target-voice capture path, which steers a beam at
            // the talker in front of the device instead of picking up the whole
            // room the way AudioSource.MIC does. This is the client-side half of
            // the two-part speaker-rejection pipeline (server voiceprint gate is
            // the other half).
            int minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minBuffer <= 0) throw new IllegalStateException();
            mRecorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(minBuffer * 2, CAPTURE_BUFFER_BYTES * 2));
            if (mRecorder.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException();
            mPcmBytes = 0;
            mCaptureError = null;
            mWavFinalized = false;
            mCaptureOut = new FileOutputStream(mCurrentAudioFile, false);
            mCaptureOut.write(WavHeader.bytes(0));

            sIsRecording = true;
            sIsProcessing = false;
            Notification notification = buildRecordingNotification("00:00");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }

            // Fire haptic + visual feedback (no sounds)
            playStrongStartFeedback();

            // Delay microphone start 90ms so the start haptic is never recorded into user audio
            mRecorderStartRunnable = () -> {
                if (generation != mGeneration || !sIsRecording || mRecorder == null) return;
                mRecorderStartRunnable = null;
                try {
                    mRecorder.startRecording();
                    mRecorderStarted = true;
                    mCaptureRunning = true;
                    AudioRecord recorder = mRecorder;
                    FileOutputStream out = mCaptureOut;
                    mCaptureThread = new Thread(() -> captureLoop(recorder, out, generation), "VoiceVaultCapture");
                    mCaptureThread.start();
                    mStreamer = new RecordingStreamer(VoiceVaultApi.streamUrl(sCaptureId), mCurrentAudioFile);
                    mStreamer.start();
                } catch (Exception ex) {
                    failDictation(sRecordingStartTime, "Microphone failed — try again");
                }
            };
            mHandler.postDelayed(mRecorderStartRunnable, 90);

            mTimerRunnable = new Runnable() {
                @Override
                public void run() {
                    if (sIsRecording) {
                        long elapsed = System.currentTimeMillis() - sRecordingStartTime;
                        long sec = (elapsed / 1000) % 60;
                        long min = (elapsed / 1000) / 60;
                        String formatted = String.format("%02d:%02d", min, sec);
                        // Silent mode: the foreground notification is posted
                        // once via startForeground() and never rebuilt, so the
                        // OS requirement is met with zero user interruption.
                        FloatingPillOverlay.updateTimer(formatted);
                        sendExplicitBroadcast(new Intent(BROADCAST_STATE_CHANGE));
                        mHandler.postDelayed(this, 1000);
                    }
                }
            };
            mHandler.postDelayed(mTimerRunnable, 1000);
            sendExplicitBroadcast(new Intent(BROADCAST_STATE_CHANGE));

        } catch (Exception e) {
            failDictation(sRecordingStartTime, "Microphone unavailable — check permission");
        }
    }

    /** Capture thread: append PCM to the durable WAV and publish the byte count. */
    private void captureLoop(AudioRecord recorder, FileOutputStream out, int generation) {
        byte[] buffer = new byte[CAPTURE_BUFFER_BYTES];
        while (mCaptureRunning && generation == mGeneration) {
            int read;
            try {
                read = recorder.read(buffer, 0, buffer.length);
            } catch (Exception error) {
                mCaptureError = error;
                break;
            }
            if (read < 0) {
                mCaptureError = new IOException("Microphone read failed");
                break;
            }
            if (read == 0) {
                Thread.yield();
                continue;
            }
            int count = read & ~1; // PCM16 frames stay whole and stream offsets stay even
            try {
                out.write(buffer, 0, count);
                mPcmBytes += count;
                RecordingStreamer streamer = mStreamer;
                if (streamer != null) streamer.written(mPcmBytes);
            } catch (Exception error) {
                mCaptureError = error;
                break;
            }
        }
        if (mCaptureError != null) {
            mHandler.post(() -> {
                if (generation == mGeneration && sIsRecording) {
                    failDictation(sRecordingStartTime, "Recording storage failed — audio is not safely saved", "storage_failure");
                }
            });
        }
    }

    private void stopAndTranscribe() {
        if (!sIsRecording) return;
        sIsRecording = false;
        cancelTimers();

        final long durationMs = System.currentTimeMillis() - sRecordingStartTime;
        final long recordingId = sRecordingStartTime;
        final int generation = mGeneration;

        final long pcmBytes;
        try {
            if (mRecorder == null || !mRecorderStarted) throw new IllegalStateException();
            stopCapture();
            if (mCaptureError != null || mPcmBytes <= 0) throw new IllegalStateException();
            pcmBytes = mPcmBytes;
            WavHeader.finish(mCurrentAudioFile, pcmBytes);
            mWavFinalized = true;
        } catch (Exception e) {
            failDictation(recordingId, "Recording too short or failed — try again");
            return;
        } finally {
            releaseRecorder();
        }

        try {
            mRecordingStore.finalizeCapture(mRecordingEntry.id,durationMs);
            mRecordingEntry=mRecordingStore.index.begin(mRecordingEntry.id);
            if(mRecordingEntry==null)throw new java.io.IOException();
        } catch(Exception storage) {
            failDictation(recordingId,"Recording storage failed — audio is not safely saved","storage_failure");return;
        }
        sIsProcessing = true;

        // Update floating pill to show sleek transcribing status
        FloatingPillOverlay.showTranscribing();

        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildTranscribingNotification());
        }
        sendExplicitBroadcast(new Intent(BROADCAST_STATE_CHANGE));

        final File audioFile = mCurrentAudioFile;
        final RecordingIndex.Entry ownedEntry=mRecordingEntry;
        final RecordingStreamer streamer = mStreamer;
        if (streamer != null) streamer.stop(pcmBytes);
        final DictationUpload upload = new DictationUpload();
        mUpload = upload;
        mProcessingTimeoutRunnable = () -> {
            if (generation == mGeneration && sIsProcessing) {
                failDictation(recordingId, "Transcription timed out — try again");
            }
        };
        mHandler.postDelayed(mProcessingTimeoutRunnable, PROCESSING_TIMEOUT_MS);
        mExecutor.execute(() -> {
            try {
                JSONObject json = null;
                boolean streamed = streamer != null && streamer.flush(STREAM_FLUSH_TIMEOUT_MS);
                if (streamer != null && !streamed) {
                    streamer.cancel();
                    mExecutor.execute(streamer::disconnect);
                }
                if (streamed) {
                    try {
                        String sha256 = DictationUpload.sha256(audioFile);
                        DictationUpload.Response finished = upload.finish(VoiceVaultApi.finishUrl(ownedEntry.id), pcmBytes, sha256, durationMs);
                        if (finished.status == 200) json = new JSONObject(finished.body);
                    } catch (Exception finishFailure) {
                        // Finishing is best effort; the whole-WAV upload below is authoritative.
                    }
                }
                if (json == null) {
                    json = new JSONObject(upload.post(VoiceVaultApi.TRANSCRIBE_URL, audioFile, durationMs, ownedEntry.id));
                }
                acceptTranscription(json, generation, recordingId, durationMs);
            } catch (Exception e) {
                final String feedback = DictationUpload.feedback(e);
                mHandler.post(() -> {
                    if (generation == mGeneration && sIsProcessing) {
                        rememberServerCopy(e);failDictation(recordingId, feedback, DictationUpload.category(e));
                    }
                });
            }
        });
    }

    private void acceptTranscription(JSONObject json, int generation, long recordingId, long durationMs) throws Exception {
        if (!json.optBoolean("ok", false)) throw new DictationUpload.Failure("Server rejected transcription");
        if (!(json.opt("text") instanceof String)) throw new DictationUpload.Failure("Invalid server response");
        String text = json.getString("text");
        mHandler.post(() -> {
            if (generation != mGeneration || !sIsProcessing) return;
            try {
                String state=json.optBoolean("rejected",false) ? "speaker_rejected" : text.trim().isEmpty() ? "no_speech" : "transcribed";
                if(!mRecordingStore.index.finish(mRecordingEntry.id,mRecordingEntry.attempt,state,"",text,json.optString("id",mRecordingEntry.id)))return;
            }catch(Exception storage){failDictation(recordingId,"Recording storage failed — original retained","storage_failure");return;}
            mUpload = null;
            onTranscriptionSuccess(text, durationMs, recordingId);
            if (json.optBoolean("rejected", false)) FloatingPillOverlay.showSuccess("Other speaker rejected");
        });
    }

    private void onTranscriptionSuccess(String text, long durationMs, long recordingId) {
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
            HistoryManager.pruneLocalRecordings(this);

            // Explicit broadcast to MainActivity
            Intent intent = new Intent(BROADCAST_TRANSCRIPT);
            intent.putExtra("text", sLastTranscript);
            intent.putExtra("durationMs", durationMs);
            sendExplicitBroadcast(intent);
        } else {
            FloatingPillOverlay.showSuccess("No speech");
        }
        VoiceVaultKeyService.onTranscriptionFinished(recordingId, text);
        cleanup();
    }

    private void rememberServerCopy(Exception error) {
        if(error instanceof DictationUpload.Failure) {
            DictationUpload.Failure failure=(DictationUpload.Failure)error;
            try {mRecordingStore.index.serverCopy(mRecordingEntry.id,mRecordingEntry.attempt,failure.recordingId,failure.audioAvailable);}catch(Exception ignored){}
        }
    }
    private void recoverRecording(String id) {
        if(sIsRecording || sIsProcessing || !mRecoveryId.isEmpty()) {
            Toast.makeText(this,"Voice Vault is busy",Toast.LENGTH_SHORT).show();return;
        }
        try {
            mRecordingStore=RecordingStore.get(this);
            synchronized(mRecordingStore) {
                mRecordingStore.reconcile(null);
                RecordingIndex.Entry entry=mRecordingStore.index.get(id);
                if(entry==null || entry.deleted || (!mRecordingStore.usable(entry) && !entry.serverAudio)) {
                    Toast.makeText(this,"Audio unavailable",Toast.LENGTH_LONG).show();stopSelf();return;
                }
                sIsProcessing=true;mRecordingEntry=mRecordingStore.index.begin(id);
                if(mRecordingEntry==null){sIsProcessing=false;Toast.makeText(this,"Recording is busy or already complete",Toast.LENGTH_SHORT).show();stopSelf();return;}
            }
        }catch(Exception storage){sIsProcessing=false;Toast.makeText(this,"Recording storage failed",Toast.LENGTH_LONG).show();stopSelf();return;}
        mRecoveryOperation=true;mRecoveryId=id;sRecoveryId=id;final int generation=++mGeneration;final RecordingIndex.Entry entry=mRecordingEntry;
        final DictationUpload upload=new DictationUpload();mUpload=upload;sIsProcessing=true;
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.Q)startForeground(NOTIFICATION_ID,buildTranscribingNotification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(NOTIFICATION_ID,buildTranscribingNotification());
        sendExplicitBroadcast(new Intent(BROADCAST_STATE_CHANGE));
        mProcessingTimeoutRunnable=()->{if(generation==mGeneration && id.equals(mRecoveryId))finishRecovery(null,"timeout");};
        mHandler.postDelayed(mProcessingTimeoutRunnable,PROCESSING_TIMEOUT_MS);
        mExecutor.execute(()->{
            try {
                RecoveryTranscription.Result result=RecoveryTranscription.run(upload,VoiceVaultApi.BASE_URL,entry,mRecordingStore.usable(entry) ? mRecordingStore.audio(entry) : null);
                mHandler.post(()->{if(generation==mGeneration && id.equals(mRecoveryId))finishRecovery(result,"");});
            }catch(Exception error){String category=DictationUpload.category(error);
                mHandler.post(()->{if(generation==mGeneration && id.equals(mRecoveryId)){rememberServerCopy(error);finishRecovery(null,category);}});}
        });
    }
    private void finishRecovery(RecoveryTranscription.Result result,String failure) {
        RecordingIndex.Entry entry=mRecordingEntry;
        try {
            boolean saved=result==null ? mRecordingStore.index.finish(entry.id,entry.attempt,"failed",failure,"",entry.serverId)
                : mRecordingStore.index.finish(entry.id,entry.attempt,result.state,"",result.text,result.serverId);
            if(!saved)failure="interrupted";
        }catch(Exception storage){failure="storage_failure";}
        ++mGeneration;mRecoveryId="";sRecoveryId="";cancelUpload();
        // Recovery completion deliberately stays outside the dictation/clipboard/insertion path.
        Toast.makeText(this,result!=null && failure.isEmpty() ? "Transcription saved in History" : "Recording retained · "+RecordingIndex.safeCategory(failure).replace('_',' '),Toast.LENGTH_LONG).show();
        cleanup();
    }

    private void cancelTimers() {
        if (mRecorderStartRunnable != null) mHandler.removeCallbacks(mRecorderStartRunnable);
        if (mProcessingTimeoutRunnable != null) mHandler.removeCallbacks(mProcessingTimeoutRunnable);
        if (mTimerRunnable != null) mHandler.removeCallbacks(mTimerRunnable);
        mRecorderStartRunnable = null;
        mProcessingTimeoutRunnable = null;
        mTimerRunnable = null;
    }

    /** Stop capture first so the header patch and streaming see a complete file. */
    private void stopCapture() {
        mCaptureRunning = false;
        AudioRecord recorder = mRecorder;
        if (recorder != null) {
            try { recorder.stop(); } catch (Exception ignored) {}
        }
        Thread thread = mCaptureThread;
        mCaptureThread = null;
        if (thread != null && thread != Thread.currentThread()) {
            try { thread.join(2000); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
        mRecorderStarted = false;
        FileOutputStream out = mCaptureOut;
        mCaptureOut = null;
        if (out != null) {
            try { out.close(); } catch (Exception ignored) {}
        }
    }

    private void releaseRecorder() {
        AudioRecord recorder = mRecorder;
        mRecorder = null;
        mCaptureRunning = false;
        Thread thread = mCaptureThread;
        mCaptureThread = null;
        if (thread != null && thread != Thread.currentThread()) {
            try { thread.join(2000); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
        FileOutputStream out = mCaptureOut;
        mCaptureOut = null;
        if (out != null) {
            try { out.close(); } catch (Exception ignored) {}
        }
        mRecorderStarted = false;
        if (!mWavFinalized && mCurrentAudioFile != null && mPcmBytes > 0) {
            // Interruption or failure still leaves a well-formed local WAV.
            try { WavHeader.finish(mCurrentAudioFile, mPcmBytes); mWavFinalized = true; } catch (Exception ignored) {}
        }
        if (recorder == null) return;
        try { recorder.stop(); } catch (Exception ignored) {}
        try { recorder.release(); } catch (Exception ignored) {}
    }

    private void cancelUpload() {
        DictationUpload upload = mUpload;
        mUpload = null;
        if (upload != null) {
            upload.cancel();
            mExecutor.execute(upload::disconnect);
        }
    }

    private void cancelStreamer() {
        RecordingStreamer streamer = mStreamer;
        mStreamer = null;
        if (streamer != null) {
            streamer.cancel();
            mExecutor.execute(streamer::disconnect);
        }
    }

    private void failDictation(long recordingId,String feedback) {
        failDictation(recordingId,feedback,feedback.contains("timed out") ? "timeout" : "invalid_audio");
    }
    private void failDictation(long recordingId, String feedback,String category) {
        try {
            if(mRecordingEntry!=null && mRecordingStore!=null) {
                if(mRecordingEntry.state.equals("processing"))mRecordingStore.index.finish(mRecordingEntry.id,mRecordingEntry.attempt,"failed",category,"",mRecordingEntry.serverId);
                else mRecordingStore.index.interrupt(mRecordingEntry.id,category);
            }
        }catch(Exception storage){feedback="Recording storage failed — audio is not safely saved";}
        ++mGeneration;
        sIsRecording = false;
        sIsProcessing = false;
        VoiceVaultKeyService.onTranscriptionFinished(recordingId, null);
        cancelUpload();
        cancelStreamer();
        // Only fixed feedback categories are logged, never paths or response bodies.
        Log.e(TAG, feedback);
        FloatingPillOverlay.showSuccess(feedback);
        Toast.makeText(this, feedback, Toast.LENGTH_LONG).show();
        cleanup();
    }

    private void cancelRecording() {
        if(!mRecoveryId.isEmpty()) {finishRecovery(null,"canceled");return;}
        boolean capture=sIsRecording;
        mGeneration++;
        VoiceVaultKeyService.onTranscriptionFinished(sRecordingStartTime,null);
        sIsRecording=false;sIsProcessing=false;cancelTimers();cancelStreamer();releaseRecorder();cancelUpload();
        try {
            if(mRecordingEntry!=null && mRecordingStore!=null) {
                mRecordingStore.index.interrupt(mRecordingEntry.id,"canceled");
                if(capture)mRecordingStore.deleteLocal(mRecordingEntry.id);
            }
        }catch(Exception storage){Toast.makeText(this,"Recording storage failed",Toast.LENGTH_LONG).show();}
        // Canceling capture discards only its active partial file; finalized processing keeps the original.
        if(capture && mCurrentAudioFile!=null && mCurrentAudioFile.exists())mCurrentAudioFile.delete();
        if(capture)mCurrentAudioFile=null;
        FloatingPillOverlay.dismiss();cleanup();
    }

    private void cleanup() {
        sCaptureId="";
        sIsRecording = false;
        sIsProcessing = false;
        cancelTimers();
        cancelStreamer();
        releaseRecorder();
        if (mWakeLock != null) {
            if (mWakeLock.isHeld()) mWakeLock.release();
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
        try {if(mRecordingEntry!=null && mRecordingStore!=null)mRecordingStore.index.interrupt(mRecordingEntry.id,"interrupted");}catch(Exception ignored){}
        boolean recovering=mRecoveryOperation;mRecoveryId="";sRecoveryId="";
        mGeneration++;
        if(!recovering)VoiceVaultKeyService.onTranscriptionFinished(sRecordingStartTime, null);
        sIsRecording = false;
        sIsProcessing = false;
        cancelUpload();
        cleanup();
        mExecutor.shutdown();
        super.onDestroy();
    }
}
