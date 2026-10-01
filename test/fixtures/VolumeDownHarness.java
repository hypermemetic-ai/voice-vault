package ai.hypermemetic.voicevault;

import java.util.ArrayList;
import java.util.List;

/** Production onKeyEvent with deterministic time and no recorder, UI or network. */
public class VolumeDownHarness extends VolumeDownBase {
    static final String TAG="test";
    boolean mode=true, auto=true;
    int insertions, toggles;
    private long mLastVolDownTime=-1;
    private final VolumeUpTiming mVolUpTiming=new VolumeUpTiming();
    private Runnable mPendingVolUpRunnable;
    private final Handler mKeyHandler=new Handler();
    boolean isDictationModeEnabled() { return mode; }
    void setDictationModeEnabled(boolean value) { mode=value; }
    void finishAndInsert() { insertions++; }
    void toggleDictation() { toggles++; }
    void stopRecordingService() {}
    void notifyTileStateChanged() {}
    void provideFeedback(boolean positive, String message) {}
    void cancelPendingKeyCallbacks() { mLastVolDownTime=-1; mVolUpTiming.reset(); mKeyHandler.jobs.clear(); }

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
        check(s.insertions==1 && s.mKeyHandler.jobs.isEmpty(),"single Down inserts immediately");
        s.down(30,KeyEvent.ACTION_UP,0);
        s.down(100,KeyEvent.ACTION_DOWN,0);
        s.down(180,KeyEvent.ACTION_DOWN,5);
        check(s.insertions==1 && s.auto,"double and repeat do not disable auto-send or insert twice");
        s.down(220,KeyEvent.ACTION_DOWN,0);
        check(s.insertions==2 && s.auto,"new intentional Down works after debounce");
        s.auto=false; s.down(450,KeyEvent.ACTION_DOWN,0);
        check(s.insertions==3 && !s.auto,"explicit insertion-only setting preserved");
        s.mode=false;
        SystemClock.now=700;
        check(!s.onKeyEvent(new KeyEvent(KeyEvent.KEYCODE_VOLUME_DOWN,KeyEvent.ACTION_DOWN,0)),"inactive Down passes through");
        check(s.insertions==3,"inactive mode does not insert");
        s.cancelPendingKeyCallbacks(); s.mode=true; s.down(701,KeyEvent.ACTION_DOWN,0);
        check(s.insertions==4,"mode change resets debounce");
    }
    static class SystemClock { static long now; static long uptimeMillis() { return now; } }
    static class Log { static void i(String tag,String message) {} }
    static class VoiceVaultService { static boolean isRecording() { return false; } }
    static class Handler {
        final List<Runnable> jobs=new ArrayList<>();
        void postDelayed(Runnable r,long delay) { jobs.add(r); }
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
