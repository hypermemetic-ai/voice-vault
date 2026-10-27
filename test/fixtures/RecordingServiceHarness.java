package ai.hypermemetic.voicevault;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** Executes production service methods with deterministic recorder/UI/network stubs. */
public final class RecordingServiceHarness extends RecordingServiceHarnessBase {
    private static final String TAG = "test", BROADCAST_STATE_CHANGE = "state";
    private static final int NOTIFICATION_ID = 1;
    private static final long PROCESSING_TIMEOUT_MS = 600000L;
    private static final int SAMPLE_RATE = 16000;
    private static final int CAPTURE_BUFFER_BYTES = 3200;
    private static final long STREAM_FLUSH_TIMEOUT_MS = 10000L;
    private static boolean sIsRecording, sIsProcessing;
    private static String sCaptureId="",sRecoveryId="";
    private static long sRecordingStartTime;
    private AudioRecord mRecorder;
    private Thread mCaptureThread;
    private FileOutputStream mCaptureOut;
    private volatile boolean mCaptureRunning;
    private volatile long mPcmBytes;
    private volatile Exception mCaptureError;
    private boolean mWavFinalized;
    private RecordingStreamer mStreamer;
    private File mCurrentAudioFile;
    private PowerManager.WakeLock mWakeLock;
    private final Handler mHandler = new Handler();
    private Runnable mTimerRunnable, mRecorderStartRunnable, mProcessingTimeoutRunnable;
    private boolean mRecorderStarted;
    private DictationUpload mUpload;
    private int mGeneration, successes, stopped;
    private RecordingStore mRecordingStore;
    private RecordingIndex.Entry mRecordingEntry;
    private String mRecoveryId="";
    private boolean mRecoveryOperation;
    private final Workers mExecutor = new Workers();
    private final File directory;
    RecordingServiceHarness(File directory) throws Exception { this.directory = directory; RecordingStore.current=new RecordingStore(directory); }

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
        AudioRecord.remaining = 1;
        startRecording();
        check(sIsRecording, "recording armed");
        mHandler.fire(mRecorderStartRunnable);
        check(mRecorderStarted, "microphone started");
        long deadline = System.currentTimeMillis() + 2000;
        while (mPcmBytes == 0 && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(5); } catch (InterruptedException ignored) {}
        }
        check(mPcmBytes > 0, "synthetic audio captured");
    }
    private void processing() {
        recording();
        stopAndTranscribe();
        check(sIsProcessing && !sIsRecording, "processing");
        check(mRecorder == null && AudioRecord.releases == 1, "recorder released before upload");
        check(mHandler.delay(mProcessingTimeoutRunnable) == 600000L, "absolute ten-minute deadline");
    }
    private void idle() {
        check(!sIsRecording && !sIsProcessing, "idle state");
        check(mRecorder == null && mWakeLock == null, "resources cleared");
        check(mHandler.delayed.isEmpty(), "timers cleared");
        check(VoiceVaultKeyService.completionText == null, "pending insertion cleared");
        check(stopped > 0, "foreground lifetime stopped");
    }
    private RecordingIndex.Entry stored() {
        try {File file=new File(directory,"dictation_1700000000000.m4a");Files.writeString(file.toPath(),"synthetic audio");
            RecordingIndex.Entry e=RecordingStore.current.capture(file,123);RecordingStore.current.finalizeCapture(e.id,1000);
            RecordingIndex.Entry a=RecordingStore.current.index.begin(e.id);RecordingStore.current.index.finish(e.id,a.attempt,"failed","server_error","","");return RecordingStore.current.index.get(e.id);
        }catch(Exception error){throw new RuntimeException(error);}
    }
    private void scenario(String name) {
        switch (name) {
        case "storage-intent":
            RecordingStore.current.failCapture=true;startRecording();idle();check(AudioRecord.starts==0 && mExecutor.work.isEmpty(),"no recorder/upload without intent");break;
        case "storage-finalize":
            recording();RecordingStore.current.failFinalize=true;stopAndTranscribe();idle();check(mCurrentAudioFile.exists() && DictationUpload.uploads==0,"original retained without upload");break;
        case "recover-busy":
            recording();recoverRecording("missing");check(sIsRecording && mExecutor.work.isEmpty(),"active dictation owns workflow");cancelRecording();break;
        case "recover-repeat":
        case "recover-cancel":
        case "recover-failed":
        case "recover-destroy": {
            RecordingIndex.Entry recovery=stored();int completions=VoiceVaultKeyService.completions;
            recoverRecording(recovery.id);Runnable worker=mExecutor.work.get(0);recoverRecording(recovery.id);
            check(sIsProcessing && mExecutor.work.size()==1,"no repeated attempt on tap/recreation");
            if(name.equals("recover-destroy")) {onDestroy();worker.run();mHandler.flush();check(mRecordingStore.index.get(recovery.id).state.equals("interrupted"),"destroy preserves retry and invalidates late result");}
            else if(name.equals("recover-cancel")) {cancelRecording();worker.run();mHandler.flush();check(mRecordingStore.index.get(recovery.id).failure.equals("canceled"),"late output cannot replace cancellation");}
            else {RecoveryTranscription.fail=name.equals("recover-failed");worker.run();mHandler.flush();
                check(mRecordingStore.index.get(recovery.id).state.equals(name.equals("recover-failed") ? "failed" : "transcribed"),"recovery result stored");}
            onDestroy();check(VoiceVaultKeyService.completions==completions && successes==0,"recovery never completes dictation/insertion");
            check(mCurrentAudioFile==null && new File(directory,recovery.filename).exists(),"recovery original retained");
            check(mRecordingStore.index.get(recovery.id).capturedAt==123,"capture time preserved");break;
        }
        case "prepare":
            AudioRecord.failPrepare = true;
            startRecording(); idle();
            check(AudioRecord.releases == 1 && mExecutor.work.isEmpty(), "prepare release/no upload");
            break;
        case "start":
            AudioRecord.failStart = true;
            startRecording(); mHandler.fire(mRecorderStartRunnable); idle();
            check(AudioRecord.releases == 1 && mExecutor.work.isEmpty(), "start release/no upload");
            break;
        case "early-stop":
            startRecording();
            Runnable pending = mRecorderStartRunnable;
            stopAndTranscribe(); idle(); pending.run();
            check(AudioRecord.starts == 0 && mExecutor.work.isEmpty(), "no delayed start/upload");
            break;
        case "capture-failure":
            AudioRecord.failRead = true;
            startRecording(); mHandler.fire(mRecorderStartRunnable);
            long captureDeadline = System.currentTimeMillis() + 2000;
            while (mCaptureError == null && System.currentTimeMillis() < captureDeadline) {
                try { Thread.sleep(5); } catch (InterruptedException ignored) {}
            }
            check(mCaptureError != null, "capture failure observed");
            stopAndTranscribe(); idle(); mHandler.flush();
            check(AudioRecord.releases == 1 && DictationUpload.uploads == 0, "capture failure release/no upload");
            break;
        case "stale-start":
            startRecording(); Runnable stale = mRecorderStartRunnable;
            cancelRecording(); idle();
            startRecording();
            stale.run(); check(AudioRecord.starts == 0, "stale start ignored");
            mHandler.fire(mRecorderStartRunnable); check(AudioRecord.starts == 1, "new start works");
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
            for (int index = 1; index < mExecutor.work.size(); index++) mExecutor.work.get(index).run();
            check(canceled.disconnected, "disconnect scheduled off UI");
            break;
        case "destroy":
            recording();
            onDestroy(); idle();
            check(AudioRecord.releases == 1 && mExecutor.closed, "destroy releases recorder and shuts workers");
            break;
        case "fallback":
            processing(); RecordingStreamer.complete = false;
            mExecutor.work.get(0).run(); mHandler.flush();
            check(successes == 1 && DictationUpload.uploads == 1 && DictationUpload.finishes == 0, "incomplete stream falls back to the WAV upload");
            break;
        case "finish-rejected":
            processing(); DictationUpload.finishStatus = 409;
            mExecutor.work.get(0).run(); mHandler.flush();
            check(successes == 1 && DictationUpload.uploads == 1 && DictationUpload.finishes == 1, "non-200 finish falls back to the WAV upload");
            break;
        default:
            processing(); Runnable timeout = mProcessingTimeoutRunnable;
            JSONObject.response = name;
            mExecutor.work.get(0).run(); mHandler.flush();
            if (name.equals("success")) {
                check(successes == 1 && !sIsProcessing, "success completed");
                check(DictationUpload.finishes == 1 && DictationUpload.uploads == 0, "verified finish accepted without upload");
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
    static final class ServiceInfo { static final int FOREGROUND_SERVICE_TYPE_MICROPHONE = 1, FOREGROUND_SERVICE_TYPE_DATA_SYNC=2; }
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
        static final int LENGTH_LONG = 1,LENGTH_SHORT=0;
        static Toast makeText(Object context, String message, int duration) { return new Toast(); }
        void show() {}
    }
    static final class FloatingPillOverlay {
        static void showTranscribing() {} static void showSuccess(String text) {}
        static void updateTimer(String timer) {} static void dismiss() {}
    }
    static final class VoiceVaultKeyService {
        static String completionText;static int completions;
        static void onTranscriptionFinished(long id, String text) { completionText = text;completions++; }
    }
    static final class VoiceVaultApi {
        static final String BASE_URL="http://synthetic.invalid",TRANSCRIBE_URL = BASE_URL+"/api/transcribe";
        static String streamUrl(String id){return BASE_URL+"/api/recording/"+id+"/stream";}
        static String finishUrl(String id){return BASE_URL+"/api/recording/"+id+"/stream/finish";}
    }
    static final class JSONObject {
        static String response = "success";
        final String body;
        JSONObject(String input) { body = response; if (body.equals("badjson")) throw new IllegalArgumentException(); }
        boolean optBoolean(String name, boolean fallback) { return name.equals("ok") && !body.equals("okfalse"); }
        Object opt(String name) { return body.equals("malformed") ? 42 : "synthetic transcript"; }
        String optString(String name,String fallback){return fallback;}
        String getString(String name) { return (String) opt(name); }
    }
    static final class DictationUpload {
        static int uploads, finishes, finishStatus = 200;
        boolean canceled, disconnected;
        static final class Failure extends Exception { String recordingId="";boolean audioAvailable;Failure(String message) { super(message); } }
        static final class Response {
            final int status; final String body;
            Response(int status,String body){this.status=status;this.body=body;}
        }
        String post(String endpoint, File audio, long duration,String id) { uploads++; return "synthetic response"; }
        Response finish(String endpoint, long bytes, String sha256, long duration) { finishes++; return new Response(finishStatus, JSONObject.response); }
        static String sha256(File file) { return "synthetic-sha256"; }
        void cancel() { canceled = true; } void disconnect() { disconnected = true; }
        static String category(Exception error){return "server_error";}
        static String feedback(Exception error) { return "synthetic failure"; }
    }
    static final class RecordingStore {
        static RecordingStore current;final RecordingIndex index;final File directory;boolean failCapture,failFinalize;
        RecordingStore(File directory)throws Exception {this.directory=directory;index=new RecordingIndex(new RecordingIndex.Disk(){byte[] bytes;
            public byte[] read(){return bytes;}public void write(byte[] next){bytes=next;}});}
        static RecordingStore get(Object context){return current;}
        void reconcile(String active){}
        RecordingIndex.Entry capture(File file,long time)throws java.io.IOException {if(failCapture)throw new java.io.IOException();return index.capture(time,"private",file.getName());}
        void finalizeCapture(String id,long duration)throws java.io.IOException {if(failFinalize)throw new java.io.IOException();index.finalizeCapture(id,duration);}
        File audio(RecordingIndex.Entry e){return new File(directory,e.filename);}
        boolean usable(RecordingIndex.Entry e){return e.finalized && audio(e).exists();}
        RecordingIndex.Entry deleteLocal(String id)throws java.io.IOException {RecordingIndex.Entry e=index.tombstone(id);if(e!=null)audio(e).delete();return e;}
    }
    static final class RecoveryTranscription {
        static boolean fail;
        static final class Result {String text="recovered",state="transcribed",serverId="server-synthetic";}
        static Result run(DictationUpload upload,String base,RecordingIndex.Entry e,File file)throws Exception {if(fail)throw new java.io.IOException();return new Result();}
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
        static final class AudioSource { static final int VOICE_RECOGNITION = 1; }
    }
    static final class AudioFormat { static final int CHANNEL_IN_MONO = 16, ENCODING_PCM_16BIT = 2; }
    static final class AudioRecord {
        static boolean failPrepare, failStart, failRead;
        static int starts, releases, stops;
        static volatile int remaining = 1;
        static final int STATE_INITIALIZED = 1;
        AudioRecord(int source, int rate, int channels, int encoding, int buffer) {}
        static int getMinBufferSize(int rate, int channels, int encoding) { return 3200; }
        int getState() { return failPrepare ? 0 : STATE_INITIALIZED; }
        void startRecording() { if (failStart) throw new IllegalStateException(); starts++; }
        int read(byte[] buffer, int offset, int length) {
            if (failRead) return -1;
            if (remaining <= 0) return 0;
            int count = Math.min(3200, length);
            for (int i = 0; i < count; i++) buffer[offset + i] = (byte) (i & 0x7f);
            remaining--;
            return count;
        }
        void stop() { stops++; }
        void release() { releases++; }
    }
    static final class WavHeader {
        static final int SIZE = 44;
        static byte[] bytes(long pcmBytes) { return new byte[SIZE]; }
        static void finish(File file, long pcmBytes) throws java.io.IOException {
            try (RandomAccessFile out = new RandomAccessFile(file, "rw")) {
                out.seek(0); out.write(bytes(pcmBytes)); out.setLength(SIZE + pcmBytes);
            }
        }
    }
    static final class RecordingStreamer {
        static boolean complete = true;
        boolean canceled, disconnected, stopped, started;
        RecordingStreamer(String endpoint, File file) {}
        void start() { started = true; }
        void written(long pcmBytes) {}
        void stop(long pcmBytes) { stopped = true; }
        boolean flush(long timeoutMs) { return complete; }
        void cancel() { canceled = true; }
        void disconnect() { disconnected = true; }
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
        synchronized void post(Runnable runnable) { posted.add(runnable); }
        synchronized void postDelayed(Runnable runnable, long duration) { delayed.add(runnable); delays.add(duration); }
        synchronized long delay(Runnable runnable) { return delays.get(delayed.indexOf(runnable)); }
        synchronized void removeCallbacks(Runnable runnable) {
            int index = delayed.indexOf(runnable);
            if (index >= 0) { delayed.remove(index); delays.remove(index); }
            posted.remove(runnable);
        }
        void fire(Runnable runnable) { removeCallbacks(runnable); runnable.run(); }
        void flush() {
            while (true) {
                Runnable next;
                synchronized (this) { next = posted.isEmpty() ? null : posted.remove(0); }
                if (next == null) return;
                next.run();
            }
        }
    }
}

class RecordingServiceHarnessBase { public void onDestroy() {} }
