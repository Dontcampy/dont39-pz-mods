package com.dont39.pz.syncguard.client;

/**
 * The repair notice's rate limit (R9, D6): at most one per {@link #WINDOW_MS}; repairs inside the window add to the
 * next one's count. No game types. Main thread only.
 */
final class Notices {
    static final long WINDOW_MS = 10_000;

    private int waiting;
    private long lastShown;
    private boolean shown;

    void repaired() {
        waiting++;
    }

    /** The repairs to show now, or 0 when there are none or the last notice is too recent. */
    int due(long now) {
        if (waiting == 0 || (shown && now - lastShown < WINDOW_MS)) {
            return 0;
        }
        int n = waiting;
        waiting = 0;
        lastShown = now;
        shown = true;
        return n;
    }
}
