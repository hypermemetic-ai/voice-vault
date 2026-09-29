package ai.hypermemetic.voicevault;

/** Display state independent of the persisted dictation-mode preference and overlay window. */
final class FloatingPillState {
    enum Phase { IDLE, RECORDING, PROCESSING, SUCCESS }

    private Phase phase = Phase.IDLE;
    private boolean dictationMode;
    private String text = "";

    void setDictationMode(boolean enabled) { dictationMode = enabled; }
    boolean isDictationMode() { return dictationMode; }
    Phase phase() { return phase; }
    boolean isVisible() { return dictationMode || phase != Phase.IDLE; }
    String text() { return text; }

    void recording() {
        phase = Phase.RECORDING;
        text = "00:00";
    }

    void timer(String value) {
        if (phase == Phase.RECORDING) text = value;
    }

    void processing() {
        phase = Phase.PROCESSING;
        text = "Processing";
    }

    void success(String message) {
        phase = Phase.SUCCESS;
        text = message != null ? message : "Copied";
    }

    void idle() {
        phase = Phase.IDLE;
        text = "";
    }
}
