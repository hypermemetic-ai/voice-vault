package ai.hypermemetic.voicevault;

/** One request for one recording, bound to the window where the shortcut was used. */
final class PendingDictation {
    private long recordingId;
    private String packageName;
    private int windowId;

    void begin(long id, String targetPackage, int targetWindow) {
        recordingId = id;
        packageName = targetPackage;
        windowId = targetWindow;
    }
    boolean isPending() { return packageName != null; }
    boolean matchesWindow(String targetPackage, int targetWindow) {
        return isPending() && packageName.equals(targetPackage) && windowId == targetWindow;
    }
    void cancel() { packageName = null; }
    String complete(long id, String text, String targetPackage, int targetWindow) {
        if (!isPending() || recordingId != id) return null;
        boolean matches = matchesWindow(targetPackage, targetWindow);
        cancel();
        return matches && text != null && !text.trim().isEmpty() ? text.trim() : null;
    }
}
