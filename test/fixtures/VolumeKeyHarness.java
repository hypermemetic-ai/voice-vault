package ai.hypermemetic.voicevault;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Production onKeyEvent/tap/hold methods with deterministic uptime and no recorder, UI or network. */
public class VolumeKeyHarness extends VolumeKeyBase {
    static final String TAG="test";
    static final long HOLD_MS=500;
    boolean mode=true, auto=true, recording;
    int insertions, toggles, raises, passed, stops;
    String feedback;
    private boolean mOwnUp, mOwnDown, mHeldUp, mHeldDown, mModeAtPressUp;
    private int mPressGenUp, mPressGenDown;
    private Runnable mHoldUp, mHoldDown;
    private int mKeyGeneration;
    private final Handler mKeyHandler=new Handler();
    boolean isDictationModeEnabled() { return mode; }
    // Like the preference listener: a mode change cancels pending key work.
    void setDictationModeEnabled(boolean value) { mode=value; cancelPendingKeyCallbacks(); }
    void finishAndInsert() { insertions++; }
    boolean isAutoSendEnabled() { return auto; }
    void setAutoSendEnabled(boolean value) { auto=value; }
    void toggleDictation() { toggles++; recording=!recording; }
    void stopRecordingService() { stops++; recording=false; }
    void raiseVolume() { raises++; }
    void notifyTileStateChanged() {}
    void provideFeedback(boolean positive, String message) { feedback=message; }
    void cancelPendingKeyCallbacks() {
        mKeyGeneration++;
        if (mHoldUp != null) mKeyHandler.removeCallbacks(mHoldUp);
        if (mHoldDown != null) mKeyHandler.removeCallbacks(mHoldDown);
        mHoldUp = null; mHoldDown = null;
    }
    @Override protected boolean superKey(KeyEvent event) { passed++; return false; }

    // PRODUCTION_KEY_EVENT

    static void check(boolean condition, String message) { if(!condition) throw new AssertionError(message); }
    boolean key(int code, int action, int repeat, long at) {
        mKeyHandler.runUntil(at);
        return onKeyEvent(new KeyEvent(code, action, repeat));
    }
    /** A press from {@code at} lasting {@code ms}, with key repeats while held. */
    void press(int code, long at, long ms) {
        boolean consumed = key(code, KeyEvent.ACTION_DOWN, 0, at);
        for (long t = at + 400, n = 1; t < at + ms; t += 50, n++) key(code, KeyEvent.ACTION_DOWN, (int) n, t);
        key(code, KeyEvent.ACTION_UP, 0, at + ms);
        mKeyHandler.runUntil(at + ms + 1);
    }
    static final int UP=KeyEvent.KEYCODE_VOLUME_UP, DOWN=KeyEvent.KEYCODE_VOLUME_DOWN;
    public static void main(String[] args) {
        // Dictation Mode ON: taps.
        VolumeKeyHarness s=new VolumeKeyHarness();
        check(s.key(DOWN,KeyEvent.ACTION_DOWN,0,0),"active Down consumed");
        check(s.insertions==0,"key filter returns before slow accessibility work");
        check(s.key(DOWN,KeyEvent.ACTION_UP,0,80),"release consumed");
        s.mKeyHandler.runUntil(81);
        check(s.insertions==1 && s.auto,"Down tap inserts on release with no double-press wait");
        s.press(UP,1000,90); check(s.toggles==1 && s.recording,"Up tap starts recording");
        s.press(UP,1200,90); check(s.toggles==2 && !s.recording,"Up tap stops recording");
        s.press(UP,1300,90); s.press(UP,1450,90); check(s.toggles==4 && s.mode,"two quick taps are two taps, never a mode change");
        check(s.passed==0 && s.raises==0,"active keys never reach the system");

        // Holds.
        s.press(DOWN,2000,700); check(!s.auto && s.insertions==1 && "Auto-send: OFF".equals(s.feedback),"Down hold toggles auto-send off without insertion");
        s.press(DOWN,3000,499); check(!s.auto && s.insertions==2,"just under the threshold is a tap");
        s.press(DOWN,4000,500); check(s.auto && s.insertions==2,"the threshold is a hold, toggling auto-send back on");
        s.press(DOWN,5000,2000); check(s.auto==false && s.insertions==2,"long hold with repeats toggles exactly once");
        s.press(DOWN,8000,600); check(s.auto,"restore auto-send");
        s.press(UP,9000,90); check(s.recording,"recording before Up hold");
        s.press(UP,10000,600); check(!s.mode && s.stops==1 && !s.recording && s.toggles==5,"Up hold stops recording and disables Dictation Mode, no tap");

        // Dictation Mode OFF.
        int passedBefore=s.passed;
        check(!s.key(DOWN,KeyEvent.ACTION_DOWN,0,11000) && !s.key(DOWN,KeyEvent.ACTION_UP,0,11050),"inactive Down passes through");
        check(s.passed==passedBefore+2,"inactive Down down/up both go to the system");
        s.press(UP,12000,90); check(s.raises==1 && !s.mode && s.toggles==5,"inactive Up tap raises volume once");
        s.press(UP,13000,600); check(s.mode && s.raises==1 && s.toggles==5 && "Dictation Mode: ON".equals(s.feedback),"inactive Up hold enables Dictation Mode, never records");
        s.press(UP,14000,90); check(s.toggles==6,"next tap records");

        // Mode changed elsewhere (tile/dashboard) mid-press: release is consumed, no stale action.
        s=new VolumeKeyHarness();
        s.key(DOWN,KeyEvent.ACTION_DOWN,0,0); s.mode=false; s.cancelPendingKeyCallbacks();
        check(s.key(DOWN,KeyEvent.ACTION_UP,0,90),"owned release consumed after mode change");
        s.mKeyHandler.runUntil(2000); check(s.insertions==0 && s.passed==0,"mode change voids the pending tap");
        s=new VolumeKeyHarness();
        s.key(UP,KeyEvent.ACTION_DOWN,0,0); s.cancelPendingKeyCallbacks(); s.mKeyHandler.runUntil(1000);
        s.key(UP,KeyEvent.ACTION_UP,0,1000); s.mKeyHandler.runUntil(1001);
        check(s.mode && s.toggles==0,"canceled press neither holds nor taps");
        // Keys are independent.
        s=new VolumeKeyHarness();
        s.key(UP,KeyEvent.ACTION_DOWN,0,0); s.key(DOWN,KeyEvent.ACTION_DOWN,0,100);
        s.key(DOWN,KeyEvent.ACTION_UP,0,150); s.key(UP,KeyEvent.ACTION_UP,0,200); s.mKeyHandler.runUntil(201);
        check(s.toggles==1 && s.insertions==1,"overlapping Up and Down taps each act once");
        System.out.println("volume key regressions passed");
    }
    static class Log { static void i(String tag,String message) {} }
    static class VoiceVaultService { static VolumeKeyHarness owner; static boolean isRecording() { return current.recording; } }
    static VolumeKeyHarness current;
    VolumeKeyHarness() { current=this; SystemClock.now=0; }
    static class SystemClock { static long now; static long uptimeMillis() { return now; } }
    static class Handler {
        static class Job { final Runnable r; final long at; Job(Runnable r,long at){this.r=r;this.at=at;} }
        final List<Job> jobs=new ArrayList<>();
        void post(Runnable r) { postDelayed(r,0); }
        void postDelayed(Runnable r,long delay) { jobs.add(new Job(r,SystemClock.now+delay)); }
        void removeCallbacks(Runnable r) { jobs.removeIf(j->j.r==r); }
        void runUntil(long t) {
            while(true) {
                jobs.sort(Comparator.comparingLong(j->j.at));
                if(jobs.isEmpty() || jobs.get(0).at>t) break;
                Job j=jobs.remove(0); SystemClock.now=Math.max(SystemClock.now,j.at); j.r.run();
            }
            SystemClock.now=Math.max(SystemClock.now,t);
        }
    }
    static class KeyEvent {
        static final int KEYCODE_VOLUME_UP=24,KEYCODE_VOLUME_DOWN=25,ACTION_DOWN=0,ACTION_UP=1;
        final int key,action,repeat;
        KeyEvent(int key,int action,int repeat) { this.key=key; this.action=action; this.repeat=repeat; }
        int getKeyCode() { return key; } int getAction() { return action; } int getRepeatCount() { return repeat; }
    }
}
class VolumeKeyBase {
    protected boolean superKey(VolumeKeyHarness.KeyEvent event) { return false; }
    protected boolean onKeyEvent(VolumeKeyHarness.KeyEvent event) { return superKey(event); }
}
