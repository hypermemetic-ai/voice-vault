package ai.hypermemetic.voicevault;

/** Uptime-based press deadlines; a queued callback is not evidence of a double press. */
final class VolumeUpTiming {
    static final long UP_WINDOW_MS = 240;

    enum ActivePress { FIRST, DOUBLE, EXPIRED_FIRST }

    private long inactiveFirst = -1;
    private long activeFirst = -1;

    boolean inactivePress(long now) {
        if (within(inactiveFirst, now)) {
            inactiveFirst = -1;
            return true;
        }
        inactiveFirst = now;
        return false;
    }

    ActivePress activePress(long now) {
        if (within(activeFirst, now)) {
            activeFirst = -1;
            return ActivePress.DOUBLE;
        }
        boolean expired = activeFirst >= 0;
        activeFirst = now;
        return expired ? ActivePress.EXPIRED_FIRST : ActivePress.FIRST;
    }

    void activeSingleFinished() {
        activeFirst = -1;
    }

    void reset() {
        inactiveFirst = -1;
        activeFirst = -1;
    }

    private static boolean within(long first, long now) {
        return first >= 0 && now >= first && now - first <= UP_WINDOW_MS;
    }
}
