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
    private static String sCaptureId="",sRecoveryId="";
    private static long sRecordingStartTime;
    private MediaRecorder mRecorder;
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
    private RecordingIndex.Entry stored() {
        try {File file=new File(directory,"dictation_1700000000000.m4a");Files.writeString(file.toPath(),"synthetic audio");
            RecordingIndex.Entry e=RecordingStore.current.capture(file,123);RecordingStore.current.finalizeCapture(e.id,1000);
            RecordingIndex.Entry a=RecordingStore.current.index.begin(e.id);RecordingStore.current.index.finish(e.id,a.attempt,"failed","server_error","","");return RecordingStore.current.index.get(e.id);
        }catch(Exception error){throw new RuntimeException(error);}
    }
    private void scenario(String name) {
        switch (name) {
        case "storage-intent":
            RecordingStore.current.failCapture=true;startRecording();idle();check(MediaRecorder.starts==0 && mExecutor.work.isEmpty(),"no recorder/upload without intent");break;
        case "storage-finalize":
            recording();RecordingStore.current.failFinalize=true;stopAndTranscribe();idle();check(mCurrentAudioFile.exists() && mExecutor.work.isEmpty(),"original retained without upload");break;
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
    static final class VoiceVaultApi { static final String BASE_URL="http://synthetic.invalid",TRANSCRIBE_URL = BASE_URL+"/api/transcribe"; }
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
        boolean canceled, disconnected;
        static final class Failure extends Exception { String recordingId="";boolean audioAvailable;Failure(String message) { super(message); } }
        String post(String endpoint, File audio, long duration,String id) { return "synthetic response"; }
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
        static boolean failPrepare, failStart, failStop;
        static int starts, releases;
        static final class AudioSource { static final int VOICE_RECOGNITION = 1; }
        static final class OutputFormat { static final int MPEG_4 = 1; }
        static final class AudioEncoder { static final int AAC = 1; }
        void setAudioSource(int source) {} void setOutputFormat(int format) {}
        void setAudioEncoder(int encoder) {} void setAudioEncodingBitRate(int rate) {}
        String output;void setAudioSamplingRate(int rate) {} void setOutputFile(String file) {output=file;}
        void prepare() { if (failPrepare) throw new IllegalStateException(); }
        void start() { if (failStart) throw new IllegalStateException(); starts++; }
        void stop() { if (failStop) throw new IllegalStateException();try{Files.writeString(new File(output).toPath(),"synthetic audio");}catch(Exception e){throw new IllegalStateException();} }
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
