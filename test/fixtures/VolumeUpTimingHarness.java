package ai.hypermemetic.voicevault;

/** Deterministic uptime/queued-handler simulation of the key service's Up paths. */
public final class VolumeUpTimingHarness {
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    private static final class Keys {
        final VolumeUpTiming timing = new VolumeUpTiming();
        boolean mode;
        int passed, consumed, toggles, recordings, downSingles, downDoubles;
        long upDue = -1, downDue = -1;

        void mode(boolean enabled) {
            mode = enabled;
            cancel(); // preference change / service transition
        }

        void cancel() {
            timing.reset();
            upDue = downDue = -1;
        }

        void up(long now, boolean repeat) {
            if (repeat) { if (mode) consumed++; else passed++; return; }
            if (!mode) {
                if (timing.inactivePress(now)) { consumed++; mode(true); toggles++; }
                else passed++;
                return;
            }
            consumed++;
            VolumeUpTiming.ActivePress press = timing.activePress(now);
            if (press == VolumeUpTiming.ActivePress.DOUBLE) {
                mode(false);
                toggles++;
                return;
            }
            if (press == VolumeUpTiming.ActivePress.EXPIRED_FIRST) recordings++;
            upDue = now + VolumeUpTiming.UP_WINDOW_MS + 1;
        }

        void down(long now, boolean repeat) {
            if (!mode) { passed++; return; }
            consumed++;
            if (repeat) return;
            if (downDue >= 0) { downDue = -1; downDoubles++; }
            else downDue = now + 320;
        }

        void handler(long now) {
            if (upDue >= 0 && now >= upDue) {
                upDue = -1;
                timing.activeSingleFinished();
                if (mode) recordings++;
            }
            if (downDue >= 0 && now >= downDue) { downDue = -1; downSingles++; }
        }
    }

    public static void main(String[] args) {
        check(VolumeUpTiming.UP_WINDOW_MS == 250, "Up deadline must be 250ms");
        for (int delta : new int[]{249, 250, 251}) {
            Keys off = new Keys();
            off.up(100, false);
            off.up(100 + delta, false);
            check(off.mode == (delta <= 250), "off deadline " + delta);
            check(off.toggles == (delta <= 250 ? 1 : 0), "off toggle " + delta);
            check(off.passed == (delta <= 250 ? 1 : 2), "off pass-through " + delta);

            Keys on = new Keys();
            on.mode(true);
            on.up(100, false);
            // Deliberately leave the first callback pending, even past its due time.
            on.up(100 + delta, false);
            check(on.mode == (delta > 250), "on deadline " + delta);
            check(on.toggles == (delta <= 250 ? 1 : 0), "on toggle " + delta);
            check(on.recordings == (delta > 250 ? 1 : 0), "expired first single " + delta);
            on.handler(1000);
            check(on.recordings == (delta > 250 ? 2 : 0), "new single or canceled double " + delta);
        }

        Keys single = new Keys();
        single.mode(true);
        single.up(0, false); // uptime zero is a real press
        single.handler(250);
        check(single.recordings == 0, "inclusive boundary must not fire single early");
        single.handler(251);
        check(single.recordings == 1, "active single recording toggle");
        single.up(500, false);
        single.up(550, true);
        single.handler(751);
        check(single.recordings == 2 && single.toggles == 0, "repeat must not pair with first");

        Keys inactive = new Keys();
        inactive.up(0, false);
        inactive.up(1, true);
        inactive.up(250, false);
        check(inactive.mode && inactive.toggles == 1 && inactive.passed == 2,
                "inactive repeat passes through without changing first-press time");

        Keys canceled = new Keys();
        canceled.mode(true);
        canceled.up(10, false);
        canceled.mode(false); // tile / service transition cancels pending single
        canceled.handler(1000);
        check(canceled.recordings == 0, "mode transition must cancel pending single");
        canceled.up(1000, false);
        canceled.cancel(); // onInterrupt / unbind / destroy
        canceled.up(1100, false);
        check(!canceled.mode && canceled.toggles == 0, "cancellation clears inactive pair");

        Keys down = new Keys();
        down.mode(true);
        down.down(0, false);
        down.down(300, true);
        down.down(320, false);
        check(down.downDoubles == 1 && down.downSingles == 0, "Down double remains 320ms");
        down.down(500, false);
        down.handler(819);
        check(down.downSingles == 0, "Down single still waits 320ms");
        down.handler(820);
        check(down.downSingles == 1, "Down single fires at 320ms");
        down.mode(false);
        down.down(1000, false);
        check(down.passed == 1, "inactive Down passes through");
    }
}
