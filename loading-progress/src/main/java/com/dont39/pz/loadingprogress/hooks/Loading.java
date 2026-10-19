package com.dont39.pz.loadingprogress.hooks;

import com.dont39.pz.loadingprogress.draw.FramePump;
import com.dont39.pz.loadingprogress.draw.Overlay;
import com.dont39.pz.loadingprogress.progress.Boundary;
import com.dont39.pz.loadingprogress.progress.Known;
import com.dont39.pz.loadingprogress.progress.Progress;
import com.dont39.pz.loadingprogress.text.Strings;
import zombie.GameWindow;
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

    /** From Zabric's loader progress: ZombieBuddy's pass has no game method between its jars to hook. */
    static void javaMod(int index, int total, String modId, boolean loaded) {
        if (!PROGRESS.active()) {
            return;
        }
        if (Thread.currentThread() != GameWindow.gameThread) {
            throw new IllegalStateException("Zabric reported Java mod " + index + " / " + total + " (" + modId
                    + ") on the thread " + Thread.currentThread().getName() + ", not on the game thread");
        }
        PROGRESS.javaMod(index, total, modId, loaded, System.nanoTime());
        Overlay.javaModSeen(modId, total);
        if (!loaded) {
            FramePump.jarStarts(PROGRESS);
        } else if (index == total) {
            // otherwise the next jar follows at once, with its own frame
            tick();
        }
    }

    /** A {@link Known} call begins; on the game thread only, whose frames it holds up. */
    static void knownCall(Known kind) {
        if (PROGRESS.active() && Thread.currentThread() == GameWindow.gameThread) {
            PROGRESS.knownCall(kind, System.nanoTime());
        }
    }

    static void knownCallEnded() {
        if (PROGRESS.active() && Thread.currentThread() == GameWindow.gameThread) {
            PROGRESS.knownCallEnded(System.nanoTime());
        }
    }

    private static void ended(Progress.End end) {
        DebugType.Mod.println(PREFIX + "%s: %s%s after %.1f s at step %d / %d (%s); %d own frames, %d interim frames%s;"
                        + " largest frame gap %s; largest visible gap %s; in the Java mods %s; outside known calls %s",
                end.flow().label(), end.outcome().name().toLowerCase(Locale.ROOT),
                end.why() == null ? "" : " (" + end.why() + ")", end.nanos() / 1e9, end.step(),
                end.flow().steps().size(), Strings.step(end.at(), false), end.ownFrames(), end.interimFrames(),
                end.interimSkips() == 0 ? "" : " (" + end.interimSkips() + " skipped for OpenGL errors the game left"
                        + " pending)",
                gap(end.largestGap()), gap(end.largestVisibleGap()),
                end.javaModGap() == null ? "none" : gap(end.javaModGap()), gap(end.unknownGap()));
    }

    private static String gap(Progress.Gap gap) {
        Progress.Shown at = gap.item();
        String item = at == null ? null : Strings.itemLine(at.item(), at.index(), at.total(), at.name(), false);
        return millis(gap.nanos()) + " in " + Strings.step(gap.step(), false) + (item == null ? "" : " (" + item + ")")
                + (gap.known() == null ? "" : ", " + millis(gap.knownNanos()) + " of it in known calls, mostly "
                + gap.known().label());
    }

    private static String millis(long nanos) {
        return TimeUnit.NANOSECONDS.toMillis(nanos) + " ms";
    }
}
