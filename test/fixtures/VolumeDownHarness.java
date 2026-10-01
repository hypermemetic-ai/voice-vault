package ai.hypermemetic.voicevault;

import java.util.ArrayList;
import java.util.List;

/** Production onKeyEvent with deterministic time and no recorder, UI or network. */
public class VolumeDownHarness extends VolumeDownBase {
    static final String TAG="test";
    boolean mode=true, auto=true;
    int insertions, toggles;
    private long mLastVolDownTime=-1;
    private final VolumeUpTiming mVolDnTiming=new VolumeUpTiming();
    private Runnable mPendingVolDnRunnable;
    private boolean mConsumedUp, mConsumedDown;
    private int mKeyGeneration;
    private final VolumeUpTiming mVolUpTiming=new VolumeUpTiming();
    private Runnable mPendingVolUpRunnable;
    private final Handler mKeyHandler=new Handler();
    boolean isDictationModeEnabled() { return mode; }
    void setDictationModeEnabled(boolean value) { mode=value; }
    void finishAndInsert() { insertions++; }
    boolean isAutoSendEnabled() { return auto; }
    void setAutoSendEnabled(boolean value) { auto=value; }
    void toggleDictation() { toggles++; }
    void stopRecordingService() {}
    void notifyTileStateChanged() {}
    void provideFeedback(boolean positive, String message) {}
    void cancelPendingKeyCallbacks() { mLastVolDownTime=-1; mVolUpTiming.reset(); mVolDnTiming.reset(); mKeyGeneration++; mPendingVolDnRunnable=null; mKeyHandler.jobs.clear(); }

    // PRODUCTION_KEY_EVENT

    static void check(boolean condition, String message) {
        if(!condition) throw new AssertionError(message);
    }
    void down(long time, int action, int repeat) {
        SystemClock.now=time;
        check(onKeyEvent(new KeyEvent(KeyEvent.KEYCODE_VOLUME_DOWN,action,repeat)),"active Down consumed");
    }
    public static void main(String[] args) {
        VolumeDownHarness s=new VolumeDownHarness();
        s.down(0,KeyEvent.ACTION_DOWN,0);
        check(s.insertions==0 && s.mKeyHandler.jobs.size()==1,"key filter returns before slow accessibility work");
        s.down(30,KeyEvent.ACTION_UP,0);
        s.mKeyHandler.run();
        check(s.insertions==1,"single Down inserts after double window");
        s.down(300,KeyEvent.ACTION_DOWN,0); s.down(330,KeyEvent.ACTION_UP,0);
        s.down(400,KeyEvent.ACTION_DOWN,0); s.down(430,KeyEvent.ACTION_UP,0);
        s.mKeyHandler.run();
        check(s.insertions==1 && !s.auto,"double Down toggles off without insertion");
        s.down(800,KeyEvent.ACTION_DOWN,0); s.down(820,KeyEvent.ACTION_UP,0);
        s.down(900,KeyEvent.ACTION_DOWN,0); s.down(930,KeyEvent.ACTION_UP,0);
        s.mKeyHandler.run();
        check(s.insertions==1 && s.auto,"double Down toggles back on");
        s.down(1200,KeyEvent.ACTION_DOWN,0); s.down(1300,KeyEvent.ACTION_DOWN,5); s.down(1400,KeyEvent.ACTION_UP,0);
        s.mKeyHandler.run(); check(s.insertions==2 && s.auto,"held repeat never toggles");
        s.down(1600,KeyEvent.ACTION_DOWN,0); s.mode=false;
        s.down(1610,KeyEvent.ACTION_UP,0);
        s.cancelPendingKeyCallbacks(); s.mKeyHandler.run(); check(s.insertions==2,"mode change cancels queued insertion and consumes original release");
        check(!s.onKeyEvent(new KeyEvent(KeyEvent.KEYCODE_VOLUME_DOWN,KeyEvent.ACTION_DOWN,0)),"inactive Down passes through");

    }
    static class SystemClock { static long now; static long uptimeMillis() { return now; } }
    static class Log { static void i(String tag,String message) {} }
    static class VoiceVaultService { static boolean isRecording() { return false; } }
    static class Handler {
        final List<Runnable> jobs=new ArrayList<>();
        void post(Runnable r) { jobs.add(r); }
        void postDelayed(Runnable r,long delay) { jobs.add(r); }
        void run() { while(!jobs.isEmpty()) jobs.remove(0).run(); }
        void removeCallbacks(Runnable r) { jobs.remove(r); }
    }
    static class KeyEvent {
        static final int KEYCODE_VOLUME_UP=24,KEYCODE_VOLUME_DOWN=25,ACTION_DOWN=0,ACTION_UP=1;
        final int key,action,repeat;
        KeyEvent(int key,int action,int repeat) { this.key=key; this.action=action; this.repeat=repeat; }
        int getKeyCode() { return key; } int getAction() { return action; } int getRepeatCount() { return repeat; }
    }
}
class VolumeDownBase { protected boolean onKeyEvent(VolumeDownHarness.KeyEvent event) { return false; } }
