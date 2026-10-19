package com.dont39.pz.loadingprogress.hooks;

import com.dont39.pz.loadingprogress.draw.FramePump;
import com.dont39.pz.loadingprogress.progress.Boundary;
import com.dont39.pz.loadingprogress.progress.Progress;
import com.dont39.pz.loadingprogress.text.Strings;
import zombie.debug.DebugType;
import zombie.network.GameServer;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** The session's progress, which the hooks feed, and its log line per flow. */
public final class Loading {
    public static final String MOD_ID = "Dont39LoadingProgress";
    public static final String PREFIX = "[" + MOD_ID + "] ";

    // client only: a dedicated server may preload the jar through a ZombieBuddy config it shares with a client
    public static final Progress PROGRESS = new Progress(() -> !GameServer.server, Loading::ended);

    private Loading() {
    }

    static void reach(Boundary boundary) {
        PROGRESS.reach(boundary, System.nanoTime());
        tick();
    }

    /** From each per-item and boundary hook: the gaps between them are what an own frame may fill. */
    static void tick() {
        FramePump.tick(PROGRESS);
    }

    private static void ended(Progress.End end) {
        DebugType.Mod.println(PREFIX + "%s: %s%s after %.1f s at step %d / %d (%s); %d own frames, largest frame gap"
                        + " %d ms in %s", end.flow().label(), end.outcome().name().toLowerCase(Locale.ROOT),
                end.why() == null ? "" : " (" + end.why() + ")", end.nanos() / 1e9, end.step(),
                end.flow().steps().size(), Strings.step(end.at(), false), end.ownFrames(),
                TimeUnit.NANOSECONDS.toMillis(end.largestGap()), Strings.step(end.largestGapStep(), false));
    }
}
