package ai.hypermemetic.voicevault;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** Executes production service methods with deterministic recorder/UI/network stubs. */
public final class RecordingServiceHarness extends RecordingServiceHarnessBase {
    private static final String TAG = "test", BROADCAST_STATE_CHANGE = "state";
    private static final int NOTIFICATION_ID = 1;
    private static final long PROCESSING_TIMEOUT_MS = 600000L;
    private static boolean sIsRecording, sIsProcessing;
    private static long sRecordingStartTime;
    private MediaRecorder mRecorder;
    private File mCurrentAudioFile;
    private PowerManager.WakeLock mWakeLock;
    private final Handler mHandler = new Handler();
    private Runnable mTimerRunnable, mRecorderStartRunnable, mProcessingTimeoutRunnable;
    private boolean mRecorderStarted;
    private DictationUpload mUpload;
    private int mGeneration, successes, stopped;
    private final Workers mExecutor = new Workers();
    private final File directory;
    RecordingServiceHarness(File directory) { this.directory = directory; }

    Object getSystemService(String name) { return new PowerManager(); }
    <T> T getSystemService(Class<T> type) { return null; }
    File getExternalFilesDir(String type) { return directory; }
    File getFilesDir() { return directory; }
    Notification buildRecordingNotification(String timer) { return new Notification(); }
    Notification buildTranscribingNotification() { return new Notification(); }
    void startForeground(int id, Notification n, int... type) {}
    void stopForeground(boolean remove) {}
    void stopSelf() { stopped++; }
    void playStrongStartFeedback() {}
    void sendExplicitBroadcast(Intent intent) {}
    void onTranscriptionSuccess(String text, long duration, long id) {
        successes++;
        VoiceVaultKeyService.onTranscriptionFinished(id, text);
        cleanup();
    }

    // PRODUCTION_METHODS

    private void recording() {
        startRecording();
        check(sIsRecording, "recording armed");
        mHandler.fire(mRecorderStartRunnable);
        check(mRecorderStarted, "microphone started");
    }
    private void processing() {
        recording();
        stopAndTranscribe();
        check(sIsProcessing && !sIsRecording, "processing");
        check(mRecorder == null && MediaRecorder.releases == 1, "recorder released before upload");
        check(mHandler.delay(mProcessingTimeoutRunnable) == 600000L, "absolute ten-minute deadline");
    }
    private void idle() {
        check(!sIsRecording && !sIsProcessing, "idle state");
        check(mRecorder == null && mWakeLock == null, "resources cleared");
        check(mHandler.delayed.isEmpty(), "timers cleared");
        check(VoiceVaultKeyService.completionText == null, "pending insertion cleared");
        check(stopped > 0, "foreground lifetime stopped");
    }
    private void scenario(String name) {
        switch (name) {
        case "prepare":
            MediaRecorder.failPrepare = true;
            startRecording(); idle();
            check(MediaRecorder.releases == 1 && mExecutor.work.isEmpty(), "prepare release/no upload");
            break;
        case "start":
            MediaRecorder.failStart = true;
            startRecording(); mHandler.fire(mRecorderStartRunnable); idle();
            check(MediaRecorder.releases == 1 && mExecutor.work.isEmpty(), "start release/no upload");
            break;
        case "early-stop":
            startRecording();
            Runnable pending = mRecorderStartRunnable;
            stopAndTranscribe(); idle(); pending.run();
            check(MediaRecorder.starts == 0 && mExecutor.work.isEmpty(), "no delayed start/upload");
            break;
        case "stop":
            recording(); MediaRecorder.failStop = true;
            stopAndTranscribe(); idle();
            check(MediaRecorder.releases == 1 && mExecutor.work.isEmpty(), "stop release/no upload");
            break;
        case "stale-start":
            startRecording(); Runnable stale = mRecorderStartRunnable;
            cancelRecording(); idle(); startRecording();
            stale.run(); check(MediaRecorder.starts == 0, "stale start ignored");
            mHandler.fire(mRecorderStartRunnable); check(MediaRecorder.starts == 1, "new start works");
            cancelRecording(); idle();
            break;
        case "timeout":
            processing(); DictationUpload oldUpload = mUpload;
            Runnable oldWorker = mExecutor.work.get(0);
            Runnable deadline = mProcessingTimeoutRunnable;
            mHandler.fire(deadline); idle();
            check(oldUpload.canceled && mCurrentAudioFile != null, "timeout cancels and retains audio");
            recording(); oldWorker.run(); mHandler.flush(); deadline.run();
            check(sIsRecording && successes == 0, "late response/deadline cannot replace retry");
            cancelRecording();
            break;
        case "cancel":
            processing(); DictationUpload canceled = mUpload;
            Runnable worker = mExecutor.work.get(0);
            cancelRecording(); idle();
            check(canceled.canceled, "upload canceled");
            JSONObject.response = "badjson";
            worker.run(); mHandler.flush();
            check(successes == 0, "late error ignored");
            mExecutor.work.get(1).run(); check(canceled.disconnected, "disconnect scheduled off UI");
            break;
        case "destroy":
            recording();
            onDestroy(); idle();
            check(MediaRecorder.releases == 1 && mExecutor.closed, "destroy releases recorder and shuts workers");
            break;
        default:
            processing(); Runnable timeout = mProcessingTimeoutRunnable;
            JSONObject.response = name;
            mExecutor.work.get(0).run(); mHandler.flush();
            if (name.equals("success")) {
                check(successes == 1 && !sIsProcessing, "success completed");
                check(mHandler.delayed.isEmpty(), "success deadline removed");
                timeout.run(); check(successes == 1 && stopped == 1, "old deadline inert");
            } else { idle(); check(successes == 0, "error not accepted"); }
        }
    }
    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        File directory = Files.createTempDirectory("vv-synthetic-recording-").toFile();
        try { new RecordingServiceHarness(directory).scenario(args[0]); }
        finally {
            for (File file : directory.listFiles()) file.delete();
            directory.delete();
        }
    }

    // These names shadow Android dependencies and the transport in extracted methods.
    static final class Context { static final String POWER_SERVICE = "power"; }
    static final class ServiceInfo { static final int FOREGROUND_SERVICE_TYPE_MICROPHONE = 1; }
    static final class Build {
        static final int SDK_INT = 34;
        static final class VERSION { static final int SDK_INT = 34; }
        static final class VERSION_CODES { static final int Q = 29; }
    }
    static final class Notification {}
    static final class NotificationManager { void notify(int id, Notification n) {} }
    static final class Intent { Intent(String action) {} }
    static final class Log { static void e(String tag, String message) {} }
    static final class Toast {
        static final int LENGTH_LONG = 1;
        static Toast makeText(Object context, String message, int duration) { return new Toast(); }
        void show() {}
    }
    static final class FloatingPillOverlay {
        static void showTranscribing() {} static void showSuccess(String text) {}
        static void updateTimer(String timer) {} static void dismiss() {}
    }
    static final class VoiceVaultKeyService {
        static String completionText;
        static void onTranscriptionFinished(long id, String text) { completionText = text; }
    }
    static final class VoiceVaultApi { static final String TRANSCRIBE_URL = "http://synthetic.invalid"; }
    static final class JSONObject {
        static String response = "success";
        final String body;
        JSONObject(String input) { body = response; if (body.equals("badjson")) throw new IllegalArgumentException(); }
        boolean optBoolean(String name, boolean fallback) { return name.equals("ok") && !body.equals("okfalse"); }
        Object opt(String name) { return body.equals("malformed") ? 42 : "synthetic transcript"; }
        String getString(String name) { return (String) opt(name); }
    }
    static final class DictationUpload {
        boolean canceled, disconnected;
        static final class Failure extends Exception { Failure(String message) { super(message); } }
        String post(String endpoint, File audio, long duration) { return "synthetic response"; }
        void cancel() { canceled = true; } void disconnect() { disconnected = true; }
        static String feedback(Exception error) { return "synthetic failure"; }
    }
    static final class PowerManager {
        static final int PARTIAL_WAKE_LOCK = 1;
        WakeLock newWakeLock(int flags, String tag) { return new WakeLock(); }
        static final class WakeLock {
            boolean held; void acquire(long duration) { held = true; }
            boolean isHeld() { return held; } void release() { held = false; }
        }
    }
    static final class MediaRecorder {
        static boolean failPrepare, failStart, failStop;
        static int starts, releases;
        static final class AudioSource { static final int VOICE_RECOGNITION = 1; }
        static final class OutputFormat { static final int MPEG_4 = 1; }
        static final class AudioEncoder { static final int AAC = 1; }
        void setAudioSource(int source) {} void setOutputFormat(int format) {}
        void setAudioEncoder(int encoder) {} void setAudioEncodingBitRate(int rate) {}
        void setAudioSamplingRate(int rate) {} void setOutputFile(String file) {}
        void prepare() { if (failPrepare) throw new IllegalStateException(); }
        void start() { if (failStart) throw new IllegalStateException(); starts++; }
        void stop() { if (failStop) throw new IllegalStateException(); }
        void release() { releases++; }
    }
    static final class Workers {
        final List<Runnable> work = new ArrayList<>();
        boolean closed;
        void execute(Runnable runnable) { work.add(runnable); }
        void shutdown() { closed = true; }
    }
    static final class Handler {
        final List<Runnable> posted = new ArrayList<>(), delayed = new ArrayList<>();
        final List<Long> delays = new ArrayList<>();
        void post(Runnable runnable) { posted.add(runnable); }
        void postDelayed(Runnable runnable, long duration) { delayed.add(runnable); delays.add(duration); }
        long delay(Runnable runnable) { return delays.get(delayed.indexOf(runnable)); }
        void removeCallbacks(Runnable runnable) {
            int index = delayed.indexOf(runnable);
            if (index >= 0) { delayed.remove(index); delays.remove(index); }
            posted.remove(runnable);
        }
        void fire(Runnable runnable) { removeCallbacks(runnable); runnable.run(); }
        void flush() { while (!posted.isEmpty()) posted.remove(0).run(); }
    }
}

class RecordingServiceHarnessBase { public void onDestroy() {} }
