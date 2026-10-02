package ai.hypermemetic.voicevault;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Calls the real bound production service, using real remote accessibility handles. */
public class PaseoFixtureTest extends Instrumentation {
    VoiceVaultKeyService service;
    PaseoFixtureActivity activity;
    int cases;
    void report(String message) { Bundle b = new Bundle(); b.putString("stream", message + "\n"); sendStatus(0, b); }
    void check(boolean condition, String detail) { if (!condition) throw new AssertionError(detail); }
    static Object field(Class<?> cls, String name, Object obj) throws Exception {
        Field f = cls.getDeclaredField(name); f.setAccessible(true); return f.get(obj);
    }
    Object invoke(String name, Class<?>[] types, Object... args) throws Exception {
        Method m = VoiceVaultKeyService.class.getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(service, args);
    }
    void main(Runnable r) { runOnMainSync(r); }
    void pause(long ms) { SystemClock.sleep(ms); }
    String feedback() throws Exception {
        return ((FloatingStatus) field(FloatingPillOverlay.class, "sStatus", null)).snapshot(SystemClock.uptimeMillis()).text;
    }
    void begin(String text) throws Exception {
        main(() -> FloatingPillOverlay.showFeedback(service, "Sentinel", false)); pause(150);
        main(() -> {
            try { invoke("pasteIntoFocusedField", new Class<?>[] {String.class}, text); }
            catch (Exception e) { throw new RuntimeException(e); }
        });
    }
    void setup(String label, boolean multiline, boolean enabled, boolean duplicate, int echoDelay, boolean keyboard) {
        main(() -> activity.configure(label, multiline, enabled, duplicate, echoDelay)); pause(400);
        main(() -> activity.keyboard(keyboard)); pause(500);
    }
    void dump(String name) throws Exception {
        AccessibilityNodeInfo root = (AccessibilityNodeInfo) invoke("getApplicationRoot", new Class<?>[0]);
        check(root != null, "native root missing");
        Class<?> cls = Class.forName("ai.hypermemetic.voicevault.VoiceVaultKeyService$PaseoTree");
        Constructor<?> constructor = cls.getDeclaredConstructor(AccessibilityNodeInfo.class); constructor.setAccessible(true);
        AutoCloseable adapter = (AutoCloseable) constructor.newInstance(root);
        try {
            PaseoSelection.Node tree = (PaseoSelection.Node) field(cls, "root", adapter);
            PaseoSelection.Node editor = PaseoSelection.composer(tree);
            PaseoSelection.Node button = PaseoSelection.submit(tree, editor);
            report("HIERARCHY " + name + " selected=" + (button == null ? "none" : button.label));
            dumpNodes(tree, "");
        } finally { adapter.close(); root.recycle(); }
    }
    void dumpNodes(PaseoSelection.Node node, String indent) {
        AccessibilityNodeInfo handle = (AccessibilityNodeInfo) node.handle;
        report(indent + node.path + " class=" + handle.getClassName() + " label=" + node.label
            + " bounds=[" + node.left + "," + node.top + "][" + node.right + "," + node.bottom + "]"
            + " visible=" + node.visible + " enabled=" + node.enabled + " clickable=" + node.clickable
            + " hint=" + handle.isShowingHintText() + " actions=" + handle.getActionList());
        for (PaseoSelection.Node child : node.children) dumpNodes(child, indent + "  ");
    }
    void manualTap() {
        int[] location = new int[2]; main(() -> activity.send.getLocationOnScreen(location));
        float x = location[0] + activity.send.getWidth() / 2f;
        float y = location[1] + activity.send.getHeight() / 2f;
        long now = SystemClock.uptimeMillis();
        for (int action : new int[] {android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP}) {
            android.view.MotionEvent event = android.view.MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, x, y, 0);
            event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
            check(getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).injectInputEvent(event, true), "manual tap injection failed");
            event.recycle();
        }
    }
    void result(String name, int clicks, String feedback) throws Exception {
        check(activity.clicks == clicks, name + " clicks=" + activity.clicks + " writes=" + activity.writes + " touch=" + activity.touchDowns + " clickActions=" + activity.clickActions + " feedback=" + feedback());
        check(activity.writes == 1, name + " writes=" + activity.writes);
        check(activity.touchDowns + activity.clickActions <= (activity.rejectClick ? 2 : 1), name + " repeated native dispatch=" + activity.clickActions);
        check(feedback.equals(feedback()), name + " feedback=" + feedback());
        check(field(VoiceVaultKeyService.class, "mFlowComposer", service) == null, name + " flow still pending");
        cases++; report("PASS " + name + " writes=" + activity.writes + " nativeClickActions=" + activity.clickActions
                + " touchDowns=" + activity.touchDowns + " mockClicks=" + activity.clicks + " feedback=" + feedback());
    }
    static void setStatic(Class<?> cls,String name,Object value) throws Exception {
        Field f=cls.getDeclaredField(name); f.setAccessible(true); f.set(null,value);
    }
    void key(int code) { hardwareKeys(code, 1); }
    void hardwareKeys(int code, int count) {
        // UiAutomation injection bypasses Android's hardware accessibility filter.
        // The host runner sends actual emulator EV_KEY events in response.
        report("HARDWARE_KEY " + code + " " + count);
        pause(300);
    }
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle finalResult = new Bundle();
        try {
            UiAutomation automation = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            // am instrument force-stops its target; enable AFTER that stop so the
            // real service binds inside this instrumented process, not a dead one.
            automation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS");
            android.provider.Settings.Secure.putString(getTargetContext().getContentResolver(),
                    "enabled_accessibility_services", "sh.paseo.debug/ai.hypermemetic.voicevault.VoiceVaultKeyService");
            android.provider.Settings.Secure.putInt(getTargetContext().getContentResolver(), "accessibility_enabled", 1);
            automation.dropShellPermissionIdentity();
            Intent intent = new Intent(getTargetContext(), PaseoFixtureActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity = (PaseoFixtureActivity) startActivitySync(intent);
            for (int i = 0; i < 50; i++) {
                service = (VoiceVaultKeyService) field(VoiceVaultKeyService.class, "sInstance", null);
                if (service != null) break; pause(100);
            }
            check(service != null, "production service not bound");
            main(() -> service.getSharedPreferences("voice_vault_prefs", 0).edit()
                    .putBoolean("pref_dictation_mode_enabled", true).putBoolean("pref_auto_send_on_paste", true).commit());
            for (String label : new String[] {"Send message", "Queue message", "Send and interrupt", "Send and steer"})
                for (boolean multiline : new boolean[] {false, true}) for (boolean keyboard : new boolean[] {false, true}) {
                    String name = label + " multiline=" + multiline + " keyboard=" + keyboard;
                    setup(label, multiline, true, false, 0, keyboard); dump(name);
                    String text = multiline ? "offline dictation\nline two" : "offline dictation";
                    begin(text); pause(1300);
                    result(name, 1, "Submitted to Paseo");
                    check(text.equals(activity.submitted), "wrong submitted text");
                    check(activity.touchDowns == 0 && activity.clickActions == 1, "not one semantic click");
                }
            setup("Send message", false, true, false, 300, false);
            main(() -> activity.editor.setText("existing draft")); pause(200);
            begin("replacement"); pause(200); check(activity.clicks == 0, "clicked before exact delayed echo");
            pause(1100); result("delayed echo replaces existing draft", 1, "Submitted to Paseo");
            for (String kind : new String[] {"disabled", "ambiguous", "context only"}) {
                setup(kind.equals("context only") ? "Context window 50% used" : "Send message", false,
                        !kind.equals("disabled"), kind.equals("ambiguous"), 0, false);
                dump(kind); begin("ready draft"); pause(2300);
                result(kind, 0, kind.equals("disabled") ? "Inserted — Send disabled"
                        : kind.equals("ambiguous") ? "Inserted — multiple Send controls" : "Inserted — Send unavailable");
                check("ready draft".equals(activity.editor.getText().toString()), "inserted text lost");
            }
            setup("Send message", false, true, false, 0, false);
            main(() -> service.getSharedPreferences("voice_vault_prefs", 0).edit().putBoolean("pref_auto_send_on_paste", false).commit());
            begin("insert only"); pause(1300); result("auto-send off", 0, "Inserted — auto-send OFF");
            main(() -> service.getSharedPreferences("voice_vault_prefs", 0).edit().putBoolean("pref_auto_send_on_paste", true).commit());
            // Manual action/remount before echo: poll can see the remount before its
            // queued accessibility event. Native object identity must still cancel.
            setup("Send message", false, true, false, 1200, false); begin("pending"); pause(80);
            check(activity.editor.getText().length()==0,"manual test must precede echo");
            manualTap(); pause(1600); result("manual send remount", 1, "Auto-send canceled");
            check(activity.clickActions == 0, "automation clicked after manual submission");
            setup("Send message", false, false, false, 0, false); begin("pending"); pause(220);
            main(() -> activity.editor.setText("")); pause(1200); result("manual clear after echo", 0, "Auto-send canceled");
            setup("Send message", false, true, false, 300, false); begin("pending"); pause(120);
            main(() -> activity.installEditor("")); pause(1300); result("navigation/remount", 0, "Auto-send canceled");
            setup("Send message", false, false, false, 0, false); begin("pending"); pause(220);
            main(() -> activity.editor.setText("user edited")); pause(1200); result("manual edit after echo", 0, "Auto-send canceled");
            setup("Send message", false, true, false, 0, false);
            main(() -> activity.rejectClick = true); begin("inserted"); pause(1300);
            result("ineffective accessibility click bypassed by touch", 1, "Submitted to Paseo");
            check(activity.clickActions == 1 && activity.touchDowns == 1, "one rejected click and one physical tap required");
            setup("Send message", false, true, false, 0, false);
            main(() -> { activity.rejectClick = true; activity.ignoreTouch = true; }); begin("inserted"); pause(1800);
            result("ineffective native tap never retried", 0, "Send not confirmed — tap Send");
            check(activity.touchDowns == 1, "must attempt touch exactly once");
            setup("Send message", false, true, false, 0, false);
            main(() -> activity.ignoreClick = true); begin("inserted"); pause(1800);
            result("accepted ineffective semantic click never retried", 0, "Send not confirmed — tap Send");
            check(activity.clickActions == 1 && activity.touchDowns == 0, "accepted action must not trigger a second activation");
            setup("Send message", false, true, false, 0, false); begin(PaseoSelection.COMPOSER); pause(1300);
            result("literal placeholder text is real echo", 1, "Submitted to Paseo");
            check(PaseoSelection.COMPOSER.equals(activity.submitted), "literal hint lost");
            setup("Send message", false, true, false, 5000, false); begin("not echoed"); pause(2300);
            result("unconfirmed insertion", 0, "Copied — insertion unconfirmed; paste manually");
            // Real key events, not direct invocation of onKeyEvent. Offline clipboard only.
            setup("Send message", false, true, false, 0, false);
            main(() -> ((android.content.ClipboardManager) activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE))
                .setPrimaryClip(android.content.ClipData.newPlainText("offline", "native key submit")));
            pause(500);
            android.media.AudioManager audio = (android.media.AudioManager) activity.getSystemService(android.content.Context.AUDIO_SERVICE);
            int[] streams = {android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.STREAM_RING,
                android.media.AudioManager.STREAM_NOTIFICATION, android.media.AudioManager.STREAM_ALARM, android.media.AudioManager.STREAM_SYSTEM};
            int[] beforeVolumes = new int[streams.length];
            for(int i=0;i<streams.length;i++) beforeVolumes[i]=audio.getStreamVolume(streams[i]);
            key(android.view.KeyEvent.KEYCODE_VOLUME_DOWN); pause(1600);
            result("real single Down inserts and submits", 1, "Submitted to Paseo");
            for(int i=0;i<streams.length;i++) check(beforeVolumes[i]==audio.getStreamVolume(streams[i]), "Down leaked to volume stream " + streams[i]);
            setup("Send message", false, true, false, 0, false);
            hardwareKeys(android.view.KeyEvent.KEYCODE_VOLUME_DOWN,2); pause(450);
            check(!service.getSharedPreferences("voice_vault_prefs",0).getBoolean("pref_auto_send_on_paste",true), "double Down did not disable");
            check(activity.writes==0 && "Auto-send: OFF".equals(feedback()), "double Down inserted or failed to show OFF"); cases++; report("PASS real double Down OFF no write");
            hardwareKeys(android.view.KeyEvent.KEYCODE_VOLUME_DOWN,2); pause(450);
            check(service.getSharedPreferences("voice_vault_prefs",0).getBoolean("pref_auto_send_on_paste",false), "double Down did not enable");
            check(activity.writes==0 && "Auto-send: ON".equals(feedback()), "double Down inserted or failed to show ON"); cases++; report("PASS real double Down ON no write");
            // A completion while Down is waiting on Processing, without a recorder/network.
            setup("Send message", false, true, false, 0, true);
            main(() -> { try { setStatic(VoiceVaultService.class,"sIsProcessing",true); setStatic(VoiceVaultService.class,"sRecordingStartTime",42L); } catch(Exception e) { throw new RuntimeException(e); } });
            key(android.view.KeyEvent.KEYCODE_VOLUME_DOWN); pause(350);
            main(() -> VoiceVaultKeyService.onTranscriptionFinished(42,"offline completion"));
            main(() -> { try { setStatic(VoiceVaultService.class,"sIsProcessing",false); } catch(Exception e) { throw new RuntimeException(e); } });
            pause(1600); result("real Down waits for completion and submits",1,"Submitted to Paseo");
            finalResult.putString("stream", "OK native cases=" + cases + "; no network permission, mock handler only\n");
            finish(ActivityResult.OK, finalResult);
        } catch (Throwable t) {
            finalResult.putString("stream", android.util.Log.getStackTraceString(t)); finish(ActivityResult.FAIL, finalResult);
        }
    }
    static class ActivityResult { static final int OK = -1, FAIL = 1; }
}
