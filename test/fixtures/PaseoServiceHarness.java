package ai.hypermemetic.voicevault;

import java.util.*;

/** JVM-only controlled IPC-cost model. Production methods are inserted by the runner.
 * No Android runtime, RN bridge, visible-frame timing, or network is simulated. */
public class PaseoServiceHarness {
    static long now, writeAt, clickAt, timerIdle;
    static int children, writes, clicks, refreshes, focuses;
    static final long IPC = 200; // deterministic microseconds per query/action, NOT a phone estimate
    static void check(boolean value, String what) { if (!value) throw new AssertionError(what); }
    static class Rect {
        int left, top, right, bottom;
        Rect() {}
        Rect(int l, int t, int r, int b) { left=l; top=t; right=r; bottom=b; }
        boolean isEmpty() { return left >= right || top >= bottom; }
    }
    static class Bundle {
        CharSequence value;
        void putCharSequence(String key, CharSequence v) { value=v; }
    }
    static class Build { static class VERSION { static int SDK_INT=34; } static class VERSION_CODES { static int O=26; } }
    static class SystemClock { static long uptimeMillis() { return now/1000; } }
    static class Log { static void w(String tag, String msg, Throwable t) { throw new AssertionError(msg, t); } }
    static class FloatingPillOverlay {
        static void showFeedback(PaseoServiceHarness s, String msg, boolean b) { s.feedback=msg; }
    }
    static class Handler {
        static class Job { Runnable r; long at; Job(Runnable r, long at) { this.r=r; this.at=at; } }
        List<Job> jobs = new ArrayList<>();
        void post(Runnable r) { postDelayed(r, 0); }
        void postDelayed(Runnable r, long ms) { jobs.add(new Job(r, now+ms*1000)); }
        void removeCallbacks(Runnable r) { jobs.removeIf(j -> j.r==r); }
        void run() {
            int limit=100;
            while (!jobs.isEmpty()) {
                check(--limit>0, "bounded scheduler");
                jobs.sort(Comparator.comparingLong(j -> j.at));
                Job j=jobs.remove(0); timerIdle+=Math.max(0,j.at-now); now=Math.max(now,j.at); j.r.run();
            }
        }
    }
    static class AccessibilityNodeInfo {
        static final int FOCUS_INPUT=1, ACTION_FOCUS=2, ACTION_SET_TEXT=3, ACTION_CLICK=4;
        static final String ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE="text";
        String label, text="", pkg="sh.paseo.debug";
        Rect bounds; boolean editable, focused, visible=true, enabled=true, clickable=true, hint, alive=true;
        int window=1; List<AccessibilityNodeInfo> kids=new ArrayList<>();
        PaseoServiceHarness owner; Runnable onRefresh;
        AccessibilityNodeInfo(String label, int l, int t, int r, int b) { this.label=label; bounds=new Rect(l,t,r,b); }
        static AccessibilityNodeInfo obtain(AccessibilityNodeInfo n) { return n; }
        void recycle() {}
        void getBoundsInScreen(Rect r) { r.left=bounds.left; r.top=bounds.top; r.right=bounds.right; r.bottom=bounds.bottom; }
        boolean isVisibleToUser() { return visible; } boolean isEnabled() { return enabled; }
        boolean isClickable() { return clickable; } boolean isEditable() { return editable; }
        CharSequence getContentDescription() { return label; } CharSequence getHintText() { return null; }
        CharSequence getText() { return text; } boolean isShowingHintText() { return hint; }
        int getChildCount() { return kids.size(); }
        AccessibilityNodeInfo getChild(int i) { children++; now+=IPC; return kids.get(i); }
        String getPackageName() { return pkg; } int getWindowId() { return window; }
        boolean isFocused() { return focused; }
        boolean refresh() { refreshes++; now+=IPC; if (onRefresh!=null) { Runnable r=onRefresh; onRefresh=null; r.run(); } return alive; }
        AccessibilityNodeInfo findFocus(int kind) { focuses++; now+=IPC; return focusedNode(this); }
        static AccessibilityNodeInfo focusedNode(AccessibilityNodeInfo n) {
            if (n.focused && n.alive) return n;
            for (AccessibilityNodeInfo c:n.kids) { AccessibilityNodeInfo f=focusedNode(c); if(f!=null)return f; }
            return null;
        }
        boolean performAction(int action) { return performAction(action, null); }
        boolean performAction(int action, Bundle args) {
            now+=IPC;
            if (action==ACTION_FOCUS) {
                if(owner.focusDelay>0) owner.mKeyHandler.postDelayed(()->focused=true,owner.focusDelay);
                else focused=true;
                return true;
            }
            if (action==ACTION_SET_TEXT) {
                writes++; writeAt=now; owner.childrenAtWrite=children; owner.idleAtWrite=timerIdle;
                String value=args.value.toString();
                if(owner.echoDelay==0) { text=value; hint=false; }
                else if(owner.echoDelay>0) owner.mKeyHandler.postDelayed(() -> { text=value; hint=false; owner.observePaseoFlow(); },owner.echoDelay);
                return true;
            }
            if (action==ACTION_CLICK) { clicks++; clickAt=now; owner.childrenAtClick=children; owner.idleAtClick=timerIdle; return !owner.reject; }
            return false;
        }
    }
    static class AccessibilityEvent {
        static final int TYPE_VIEW_CLICKED=1, TYPE_VIEW_SCROLLED=2, TYPE_WINDOW_STATE_CHANGED=3,
                TYPE_VIEW_TEXT_CHANGED=4, TYPE_WINDOW_CONTENT_CHANGED=5;
        int type; AccessibilityNodeInfo source;
        AccessibilityEvent(int type, AccessibilityNodeInfo source) { this.type=type; this.source=source; }
        int getEventType() { return type; } CharSequence getPackageName() { return source.pkg; }
        int getWindowId() { return source.window; } AccessibilityNodeInfo getSource() { return source; }
    }
    final Handler mKeyHandler=new Handler();
    final PendingDictation mPendingDictation=new PendingDictation();
    AccessibilityNodeInfo root, editor, send, mPendingComposer, mFlowComposer;
    long mPendingPaseoId=-1; int mPaseoGeneration, mFlowWindow=-1, mPaseoAttempt;
    long mPaseoDeadline; static final long PASEO_READY_TIMEOUT_MS=2000L;
    PaseoSelection.Gate mFlowGate; String mFlowExpected, mFlowPackage;
    Runnable mPaseoCheck; boolean mode=true, auto=true, reject;
    int echoDelay, focusDelay, childrenAtWrite, childrenAtClick; long idleAtWrite, idleAtClick; String feedback="sentinel";
    static final String TAG="test";
    boolean isDictationModeEnabled() { return mode; } boolean isAutoSendEnabled() { return auto; }
    AccessibilityNodeInfo getApplicationRoot() { now+=IPC; return root; }
    CharSequence readPasteText(String s) { return s==null?"replacement":s; }
    void pasteIntoFocusedField(String text) { pasteIntoPaseo(getApplicationRoot(), text); }
    // PRODUCTION_METHODS

    static PaseoServiceHarness fixture(boolean flat, int historyRows) {
        return fixture(flat, historyRows, false);
    }
    static PaseoServiceHarness fixture(boolean flat, int historyRows, boolean flatHistory) {
        now=timerIdle=0; children=writes=clicks=refreshes=focuses=0; writeAt=clickAt=-1;
        PaseoServiceHarness s=new PaseoServiceHarness();
        s.root=new AccessibilityNodeInfo("screen",0,0,400,900);
        AccessibilityNodeInfo history=new AccessibilityNodeInfo("chat history",0,0,400,420);
        if(!flatHistory) s.root.kids.add(history);
        for(int i=0;i<historyRows;i++) {
            AccessibilityNodeInfo row=new AccessibilityNodeInfo("row",0,0,400,400);
            (flatHistory?s.root:history).kids.add(row);
            for(int j=0;j<4;j++) row.kids.add(new AccessibilityNodeInfo("history text",0,0,400,400));
        }
        AccessibilityNodeInfo column=new AccessibilityNodeInfo("column",12,420,388,492);
        s.editor=new AccessibilityNodeInfo(PaseoSelection.COMPOSER,24,428,376,452);
        s.editor.editable=true; s.editor.focused=true; s.editor.text="existing draft"; s.editor.owner=s;
        AccessibilityNodeInfo toolbar=new AccessibilityNodeInfo("toolbar",18,464,382,492);
        s.send=new AccessibilityNodeInfo("Send message",354,464,382,492); s.send.owner=s;
        toolbar.kids.add(new AccessibilityNodeInfo("Context window 50% used",18,464,90,492));
        toolbar.kids.add(new AccessibilityNodeInfo("Voice",320,464,348,492)); toolbar.kids.add(s.send);
        if(flat) { s.root.kids.add(s.editor); s.root.kids.addAll(toolbar.kids); }
        else { s.root.kids.add(column); column.kids.add(s.editor); column.kids.add(toolbar); }
        s.mPendingComposer=s.editor; s.mPendingDictation.begin(42,"sh.paseo.debug",1); s.mPendingPaseoId=42;
        return s;
    }
    void complete() { insertCompletedRecording(42,"replacement"); }
    static void benchmark(String revision) {
        for(int layout=0;layout<3;layout++) {
            boolean flat=layout>0, flatHistory=layout==2;
            String name=layout==0?"COLUMN":layout==1?"flattened":"flat-history-rows";
            PaseoServiceHarness s=fixture(flat,1000,flatHistory); s.complete(); s.mKeyHandler.run();
            check(writes==1 && clicks==1,"benchmark one write/click");
            System.out.printf("%s recording %s rows=1000 nodes=%d ready->SET_TEXT_us=%d children_before_write=%d idle_before_write_us=%d ready->CLICK_us=%d children_total=%d idle_total_us=%d refresh=%d focus=%d%n",
                    revision,name,flatHistory?5005:flat?5006:5008,writeAt,s.childrenAtWrite,s.idleAtWrite,clickAt,s.childrenAtClick,s.idleAtClick,refreshes,focuses);
            s=fixture(flat,1000,flatHistory); s.pasteIntoPaseo(s.root,"replacement"); s.mKeyHandler.run();
            check(writes==1 && clicks==1,"clipboard one write/click");
            System.out.printf("%s clipboard %s ready->SET_TEXT_us=%d children_before_write=%d idle_before_write_us=%d ready->CLICK_us=%d children_total=%d idle_total_us=%d%n",
                    revision,name,writeAt,s.childrenAtWrite,s.idleAtWrite,clickAt,s.childrenAtClick,s.idleAtClick);
        }
    }
    static void regressions() {
        int cases=0;
        for(boolean flat:new boolean[]{false,true}) for(String label:new String[]{"Send message","Queue message","Send and interrupt","Send and steer"}) {
            PaseoServiceHarness s=fixture(flat,1000); s.send.label=label; s.complete(); s.mKeyHandler.run();
            check(writes==1 && clicks==1,"exact label "+label); check(s.childrenAtWrite==0,"focused no traversal before write"); cases++;
        }
        PaseoServiceHarness s=fixture(false,1000); s.editor.focused=false; s.complete(); s.mKeyHandler.run();
        check(writes==1 && clicks==1,"unfocused fallback"); check(s.childrenAtWrite>5000,"fallback discovery"); cases++;
        s=fixture(false,1000); s.editor.focused=false; s.focusDelay=25; s.complete(); check(writes==0,"wait only for asynchronous focus"); s.mKeyHandler.run();
        check(writes==1 && clicks==1,"asynchronous focus fallback"); cases++;
        s=fixture(false,1000); s.auto=false; s.complete(); s.mKeyHandler.run(); check(writes==1 && clicks==0 && children==0,"insert-only no traversal"); cases++;
        s=fixture(false,1000); s.echoDelay=25; s.complete(); check(writes==1 && clicks==0 && children==0,"no query before echo"); s.mKeyHandler.run();
        check(clicks==1 && clickAt<100000,"event wakes send before fallback timer"); cases++;
        for(String label:new String[]{"Context window 50% used","Voice","Send"}) {
            s=fixture(false,1000); s.send.label=label; s.complete(); s.mKeyHandler.run(); check(clicks==0 && writes==1,"not submit "+label); cases++;
        }
        s=fixture(true,1000); s.root.kids.add(new AccessibilityNodeInfo("Queue message",354,464,382,492)); s.complete(); s.mKeyHandler.run(); check(clicks==0,"ambiguous"); cases++;
        s=fixture(false,1000); s.reject=true; s.complete(); s.mKeyHandler.run(); check(clicks==1 && writes==1,"rejected no retry"); cases++;
        s=fixture(false,1000); s.send.onRefresh=()->{}; final PaseoServiceHarness stale=s;
        s.send.onRefresh=()->stale.send.label="Voice"; s.complete(); s.mKeyHandler.run(); check(clicks==0,"changed action target refresh"); cases++;
        for(int kind=0;kind<6;kind++) {
            s=fixture(false,1000); s.send.enabled=false; s.complete(); check(writes==1 && clicks==0,"pending readiness");
            if(kind==0)s.editor.text="user edit";
            if(kind==1)s.editor.text="";
            if(kind==2){s.editor.alive=false; s.editor.focused=false;}
            if(kind==3)s.root.pkg="other.app";
            if(kind==4)s.mode=false;
            if(kind==5)s.root.window=2;
            s.observePaseoFlow(); s.send.enabled=true; s.mKeyHandler.run(); check(clicks==0,"canceled destination/edit "+kind); cases++;
        }
        s=fixture(false,1000); s.send.enabled=false; s.complete(); int before=children;
        for(int i=0;i<100;i++) s.observePaseoFlow(); check(children==before,"event observations never traverse");
        s.onAccessibilityEvent(new AccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED,s.send)); s.send.enabled=true; s.mKeyHandler.run(); check(clicks==0,"manual submit cancels"); cases++;
        s=fixture(false,1000); s.echoDelay=-1; s.complete(); s.mKeyHandler.run(); check(writes==1 && clicks==0 && children==0 && s.feedback.contains("unconfirmed"),"unconfirmed timeout"); cases++;
        s=fixture(false,1000); s.editor.text=PaseoSelection.COMPOSER; s.editor.hint=true; s.auto=false; s.complete(); s.mKeyHandler.run(); check(writes==1 && clicks==0,"hint handled"); cases++;
        s=fixture(false,1000); s.mode=false; s.complete(); s.mKeyHandler.run(); check(writes==0,"mode off"); cases++;
        s=fixture(false,1000); s.insertCompletedRecording(41,"wrong recording"); check(writes==0 && children==0 && s.mPendingDictation.isPending(),"wrong recording preserves request"); s.complete(); check(writes==1 && clicks==1,"correct recording still completes"); cases++;
        for(int kind=0;kind<3;kind++) {
            s=fixture(false,1000);
            if(kind==0)s.root.pkg="other.app";
            if(kind==1)s.root.window=2;
            if(kind==2){s.editor.label="different input";}
            s.complete(); s.mKeyHandler.run(); check(writes==0 && clicks==0,"destination changed before write "+kind); cases++;
        }
        s=fixture(false,1000); s.send.enabled=false; s.complete(); final PaseoServiceHarness ready=s;
        s.mKeyHandler.postDelayed(()->{ready.send.enabled=true; ready.observePaseoFlow();},25); s.mKeyHandler.run();
        check(clicks==1 && clickAt<100000,"event wakes readiness before timer"); cases++;
        s=fixture(false,1000); final PaseoServiceHarness moved=s;
        s.send.onRefresh=()->moved.editor.bounds.bottom+=50; s.complete(); s.mKeyHandler.run(); check(clicks==0,"editor geometry changes before dispatch"); cases++;
        s=fixture(true,1000); s.root.kids.removeIf(n->n.label.equals("Voice")); s.complete(); s.mKeyHandler.run(); check(clicks==1,"exact local send survives optional peer absence"); cases++;
        s=fixture(false,1000); AccessibilityNodeInfo duplicate=new AccessibilityNodeInfo(PaseoSelection.COMPOSER,24,428,376,452); duplicate.editable=true;
        s.root.kids.add(duplicate); s.pasteIntoPaseo(s.root,"replacement"); s.mKeyHandler.run(); check(writes==0,"clipboard ambiguous composer"); cases++;
        System.out.println("production-method regressions passed="+cases);
    }
    static void autosendRegressions() {
        PaseoServiceHarness s=fixture(false,0);
        final PaseoServiceHarness resizing=s;
        s.send.onRefresh=()-> {
            resizing.editor.bounds.bottom+=50;
            resizing.mKeyHandler.postDelayed(()-> {
                AccessibilityNodeInfo toolbar=resizing.root.kids.get(1).kids.get(1);
                toolbar.bounds.top+=50; toolbar.bounds.bottom+=50;
                for(AccessibilityNodeInfo child:toolbar.kids) { child.bounds.top+=50; child.bounds.bottom+=50; }
                resizing.root.kids.get(1).bounds.bottom+=50;
            },50);
        };
        s.complete(); s.mKeyHandler.run();
        check(writes==1 && clicks==1,"normal multiline layout must revalidate and send once");

        s=fixture(false,0); s.send.enabled=false;
        final PaseoServiceHarness rendering=s;
        s.complete();
        for(int i=1;i<=20;i++) {
            s.mKeyHandler.postDelayed(()-> { if(rendering.mFlowComposer!=null) rendering.observePaseoFlow(); },i*10);
        }
        s.mKeyHandler.postDelayed(()->{rendering.send.enabled=true; if(rendering.mFlowComposer!=null) rendering.observePaseoFlow();},600);
        s.mKeyHandler.run();
        check(writes==1 && clicks==1,"content events must not exhaust readiness before React enables Send");

        s=fixture(true,0);
        final PaseoServiceHarness minimal=s;
        s.root.kids.removeIf(n->n!=minimal.editor && n!=minimal.send && !n.label.equals("chat history"));
        s.complete(); s.mKeyHandler.run();
        check(writes==1 && clicks==1,"exact local send must not depend on optional toolbar peers");

        s=fixture(false,0); s.send.enabled=false;
        s.complete(); s.mKeyHandler.run();
        check(clicks==0 && s.feedback.equals("Inserted — send manually"),"unavailable Send terminates without dispatch");
        check(now>=2000000 && now<2300000,"readiness respects the real two-second budget");
    }
    public static void main(String[] args) {
        if(args[0].equals("autosend")) { autosendRegressions(); return; }
        benchmark(args[0]); if(args[0].equals("after"))regressions();
    }
}
