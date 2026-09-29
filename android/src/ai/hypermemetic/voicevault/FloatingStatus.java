package ai.hypermemetic.voicevault;

/** The brick's underlying status keeps advancing while a temporary message is visible. */
final class FloatingStatus {
    enum Kind { IDLE, RECORDING, PROCESSING, SUCCESS, ENABLED, DISABLED }
    static final long FEEDBACK_MS = 2200;
    static final long SUCCESS_MS = 1400;
    private Kind base = Kind.IDLE;
    private String text = "";
    private long successUntil;
    private String feedback;
    private Kind feedbackKind;
    private long feedbackUntil;

    void recording() { base = Kind.RECORDING; text = "00:00"; successUntil = 0; }
    void timer(String value) { if (base == Kind.RECORDING) text = value; }
    void processing() { base = Kind.PROCESSING; text = "Processing"; successUntil = 0; }
    void success(String value, long now) {
        base = Kind.SUCCESS;
        text = value == null ? "Copied" : value;
        successUntil = now + SUCCESS_MS;
    }
    void feedback(String value, boolean enabled, long now) {
        feedback = value;
        feedbackKind = enabled ? Kind.ENABLED : Kind.DISABLED;
        feedbackUntil = now + FEEDBACK_MS;
    }
    void dismissBase() { base = Kind.IDLE; text = ""; successUntil = 0; }
    boolean isRecording() { return base == Kind.RECORDING; }

    Snapshot snapshot(long now) {
        if (base == Kind.SUCCESS && now >= successUntil) dismissBase();
        if (feedback != null && now >= feedbackUntil) feedback = null;
        if (feedback != null) return new Snapshot(feedbackKind, feedback, feedbackUntil);
        return new Snapshot(base, text, base == Kind.SUCCESS ? successUntil : 0);
    }

    static final class Snapshot {
        final Kind kind;
        final String text;
        final long expiresAt;
        Snapshot(Kind kind, String text, long expiresAt) {
            this.kind = kind;
            this.text = text;
            this.expiresAt = expiresAt;
        }
    }
}
