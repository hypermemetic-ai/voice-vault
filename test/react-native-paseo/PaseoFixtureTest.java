package ai.hypermemetic.voicevault;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Real React Native bridge, with mock submission only. Run on a disposable emulator. */
public class PaseoFixtureTest extends Instrumentation {
    VoiceVaultKeyService service;
    boolean baseline;
    int submissions, cases;
    void report(String message) { Bundle b = new Bundle(); b.putString("stream", message + "\n"); sendStatus(0, b); }
    void check(boolean condition, String detail) { if (!condition) throw new AssertionError(detail); }
    Object field(Class<?> cls, String name, Object obj) throws Exception {
        Field f = cls.getDeclaredField(name); f.setAccessible(true); return f.get(obj);
    }
    Object invoke(String name, Class<?>[] types, Object... args) throws Exception {
        Method m = VoiceVaultKeyService.class.getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(service, args);
    }
    String feedback() throws Exception {
        return ((FloatingStatus) field(FloatingPillOverlay.class, "sStatus", null)).snapshot(SystemClock.uptimeMillis()).text;
    }
    AccessibilityNodeInfo root() throws Exception { return (AccessibilityNodeInfo) invoke("getApplicationRoot", new Class<?>[0]); }
    String result() throws Exception {
        AccessibilityNodeInfo root = root();
        try {
            for (AccessibilityNodeInfo n : root.findAccessibilityNodeInfosByText("Fixture result")) {
                try { if ("Fixture result".contentEquals(n.getContentDescription())) return String.valueOf(n.getText()); }
                finally { n.recycle(); }
            }
            throw new AssertionError("Mock result missing");
        } finally { root.recycle(); }
    }
    void control(String label) throws Exception {
        AccessibilityNodeInfo root = root();
        boolean clicked = false;
        try {
            for (AccessibilityNodeInfo n : root.findAccessibilityNodeInfosByText(label)) {
                try {
                    if (!clicked && n.refresh() && n.isClickable() && n.isEnabled()
                            && n.getContentDescription() != null && label.contentEquals(n.getContentDescription())) {
                        clicked = n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    }
                } finally { n.recycle(); }
            }
        } finally { root.recycle(); }
        check(clicked, "Fixture control unavailable: " + label); SystemClock.sleep(200);
    }
    void attempt(String name, String text, String terminal, boolean sends) throws Exception {
        runOnMainSync(() -> {
            FloatingPillOverlay.showFeedback(service, "Sentinel", false);
            try { invoke("pasteIntoFocusedField", new Class<?>[] {String.class}, text); }
            catch (Exception e) { throw new RuntimeException(e); }
        });
        long deadline = SystemClock.uptimeMillis() + 3200;
        while (SystemClock.uptimeMillis() < deadline && !terminal.equals(feedback())) SystemClock.sleep(40);
        check(terminal.equals(feedback()), name + " unexpected status: " + feedback());
        if (sends) submissions++;
        SystemClock.sleep(200);
        check(result().startsWith("Mock submissions: " + submissions + ";"), name + " wrong callback count: " + result());
        if (sends) check(result().equals("Mock submissions: " + submissions + "; " + text), name + " wrong draft delivered");
        check(field(VoiceVaultKeyService.class, "mFlowComposer", service) == null, "Flow still pending");
        cases++; report("PASS " + name + " callbacks=" + submissions + " feedback=" + terminal);
    }
    @Override public void onCreate(Bundle args) { super.onCreate(args); baseline = args != null && "true".equals(args.getString("baseline")); start(); }
    @Override public void onStart() {
        Bundle finalResult = new Bundle();
        try {
            UiAutomation auto = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            auto.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS");
            android.provider.Settings.Secure.putString(getTargetContext().getContentResolver(), "enabled_accessibility_services",
                    "sh.paseo.debug/ai.hypermemetic.voicevault.VoiceVaultKeyService");
            android.provider.Settings.Secure.putInt(getTargetContext().getContentResolver(), "accessibility_enabled", 1);
            auto.dropShellPermissionIdentity();
            for (int i = 0; i < 50; i++) {
                service = (VoiceVaultKeyService) field(VoiceVaultKeyService.class, "sInstance", null);
                if (service != null) break; SystemClock.sleep(100);
            }
            check(service != null, "Production service not bound");
            runOnMainSync(() -> service.getSharedPreferences("voice_vault_prefs", 0).edit()
                    .putBoolean("pref_dictation_mode_enabled", true).putBoolean("pref_auto_send_on_paste", true).commit());
            getTargetContext().startActivity(new Intent().setClassName("sh.paseo", "sh.paseo.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            SystemClock.sleep(3000);
            if (baseline) {
                attempt("1.2.17 standard", "standard synthetic draft", "Submitted to Paseo", true);
                control("Move Send");
                attempt("1.2.17 moved rejects functional Send", "moved synthetic draft", "Inserted — send manually", false);
            } else {
                for (String label : new String[] {"Send message", "Queue message", "Send and interrupt", "Send and steer"}) {
                    for (boolean moved : new boolean[] {false, true}) {
                        for (String text : new String[] {"synthetic draft", "synthetic multiline\ndraft"})
                            attempt(label + " moved=" + moved + " multiline=" + text.contains("\n"), text, "Submitted to Paseo", true);
                        control("Move Send");
                    }
                    control("Next label");
                }
                control("Disable Send");
                attempt("disabled RN Pressable", "disabled draft", "Inserted — Send disabled", false);
                control("Reset draft"); control("Disable Send"); control("Duplicate Send");
                attempt("ambiguous RN Pressables", "ambiguous draft", "Inserted — multiple Send controls", false);
            }
            finalResult.putString("stream", "OK React Native cases=" + cases + "; synthetic mock handler only\n"); finish(-1, finalResult);
        } catch (Throwable t) {
            finalResult.putString("stream", android.util.Log.getStackTraceString(t)); finish(1, finalResult);
        }
    }
}
