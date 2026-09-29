package ai.hypermemetic.voicevault;

public final class AndroidStateHarness {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static void status(FloatingStatus state, long now, FloatingStatus.Kind kind, String text) {
        FloatingStatus.Snapshot value = state.snapshot(now);
        check(value.kind == kind, "Expected " + kind + " but got " + value.kind);
        check(value.text.equals(text), "Expected " + text + " but got " + value.text);
    }
    public static void main(String[] args) {
        FloatingStatus state = new FloatingStatus();
        PendingDictation pending = new PendingDictation();
        switch (args[0]) {
            case "timer":
                state.recording();
                state.feedback("Auto-Send: OFF", false, 100);
                state.timer("00:02");
                status(state, 2100, FloatingStatus.Kind.DISABLED, "Auto-Send: OFF");
                status(state, 2300, FloatingStatus.Kind.RECORDING, "00:02");
                check(state.isRecording(), "Recording must remain active under feedback");
                break;
            case "processing":
                state.recording();
                state.feedback("Auto-Send: ON", true, 100);
                state.processing();
                status(state, 200, FloatingStatus.Kind.ENABLED, "Auto-Send: ON");
                status(state, 2300, FloatingStatus.Kind.PROCESSING, "Processing");
                check(!state.isRecording(), "Processing brick must not stop recording");
                break;
            case "completion":
                state.recording();
                state.feedback("Dictation Mode: OFF", false, 100);
                state.success("Copied", 2000);
                status(state, 2300, FloatingStatus.Kind.SUCCESS, "Copied");
                status(state, 3400, FloatingStatus.Kind.IDLE, "");
                state.feedback("Auto-Send: ON", true, 4000);
                state.success("Copied", 4100);
                status(state, 5600, FloatingStatus.Kind.ENABLED, "Auto-Send: ON");
                status(state, 6200, FloatingStatus.Kind.IDLE, "");
                break;
            case "replacement":
                state.recording();
                state.feedback("ON", true, 100);
                state.feedback("OFF", false, 2000);
                status(state, 2300, FloatingStatus.Kind.DISABLED, "OFF");
                state.timer("00:04");
                status(state, 4200, FloatingStatus.Kind.RECORDING, "00:04");
                break;
            case "idle":
                state.feedback("Dictation Mode: ON", true, 100);
                status(state, 2300, FloatingStatus.Kind.IDLE, "");
                state.feedback("Dictation Mode: OFF", false, 3000);
                state.dismissBase();
                status(state, 3100, FloatingStatus.Kind.DISABLED, "Dictation Mode: OFF");
                status(state, 5200, FloatingStatus.Kind.IDLE, "");
                break;
            case "new-recording":
                state.success("Copied", 100);
                state.recording();
                state.timer("00:01");
                status(state, 1500, FloatingStatus.Kind.RECORDING, "00:01");
                state.feedback("ON", true, 2000);
                state.recording();
                status(state, 2100, FloatingStatus.Kind.ENABLED, "ON");
                status(state, 4200, FloatingStatus.Kind.RECORDING, "00:00");
                break;
            case "fresh":
                pending.begin(1, "chat.app", 4);
                check(pending.complete(0, "old", "chat.app", 4) == null, "Never insert an old recording");
                check(pending.isPending(), "Old result cannot consume a newer request");
                check("new".equals(pending.complete(1, " new ", "chat.app", 4)), "Insert exact fresh result");
                check(pending.complete(1, "new", "chat.app", 4) == null, "Consume result once");
                break;
            case "context":
                pending.begin(1, "chat.app", 4);
                check(pending.complete(1, "text", "another.app", 4) == null, "Never insert into another app");
                check(!pending.isPending(), "Changed context cancels request");
                pending.begin(2, "chat.app", 4);
                check(pending.complete(2, "text", "chat.app", 5) == null, "Never insert into another window");
                break;
            case "failure":
                pending.begin(1, "chat.app", 4);
                check(pending.complete(1, null, "chat.app", 4) == null, "No insertion on failure");
                check(!pending.isPending(), "Failure clears pending");
                pending.begin(2, "chat.app", 4);
                check(pending.complete(2, "  ", "chat.app", 4) == null, "No insertion on no speech");
                check(!pending.isPending(), "No speech clears pending");
                break;
            case "cancel":
                pending.begin(1, "chat.app", 4);
                pending.cancel();
                check(pending.complete(1, "text", "chat.app", 4) == null, "No insertion after cancel");
                pending.begin(2, "chat.app", 4);
                check(pending.complete(1, "old", "chat.app", 4) == null, "Canceled late result ignored");
                check("new".equals(pending.complete(2, "new", "chat.app", 4)), "Next recording can complete");
                break;
            default: throw new AssertionError("Unknown scenario");
        }
    }
}
