package com.dont39.pz.loadingprogress.hooks;

import com.dont39.pz.loadingprogress.draw.FramePump;
import com.dont39.pz.loadingprogress.draw.Overlay;
import com.dont39.pz.loadingprogress.progress.Boundary;
import com.dont39.pz.loadingprogress.progress.Known;
import com.dont39.pz.loadingprogress.progress.Progress;
import com.dont39.pz.loadingprogress.progress.Settings;
import com.dont39.pz.loadingprogress.report.Report;
import com.dont39.pz.loadingprogress.report.Reports;
import com.dont39.pz.loadingprogress.text.Strings;
import com.pzlauncher.zabric.hook.ModHooks;
import com.pzlauncher.zabric.hook.ZabricHooks;
import zombie.GameWindow;
import zombie.ZomboidFileSystem;
import zombie.core.Core;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.network.GameServer;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The session's progress, which the hooks feed, its log line per flow and its timing report. */
public final class Loading {
    public static final String MOD_ID = "Dont39LoadingProgress";
    public static final String PREFIX = "[" + MOD_ID + "] ";

    // client only: a dedicated server may preload the jar through a ZombieBuddy config it shares with a client
    public static final Progress PROGRESS = new Progress(() -> !GameServer.server, Loading::settings, System::nanoTime,
            Loading::notClient, Loading::ended);

    private static final String END_LINE = "%s: %s%s after %.1f s at step %d / %d (%s); %d own frames, %d interim"
            + " frames%s; largest frame gap %s; largest visible gap %s; in the Java mods %s; outside known calls %s";

    private static final ModHooks HOOKS = ZabricHooks.mod(MOD_ID);
    // no file I/O inside a load step; one thread, so that pruning sees each load's files
    private static final ExecutorService REPORTS = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, MOD_ID + " report");
        thread.setDaemon(true);
        return thread;
    });
    private static volatile boolean reportFailed;

    private Loading() {
    }

    /**
     * Whether Zabric closed the mod's hooks (a stop, or a mod list without the mod while it is not preloaded), which
     * ends the flow. For handlers at a call's end: Zabric runs them for a call that began while the hooks were open,
     * and the flow has not moved since.
     */
    static boolean hooksClosed() {
        if (HOOKS.open()) {
            return false;
        }
        PROGRESS.stop("Zabric closed the mod's hooks", System.nanoTime());
        return true;
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

    private static void notClient() {
        DebugType.Mod.println(PREFIX + "%s loaded; dedicated server: not active",
                Loading.class.getPackage().getImplementationVersion());
    }

    // the game log is up at the first flow's begin
    private static Settings settings() {
        Path file = luaFolder().resolve(Settings.FILE);
        Settings settings = Settings.load(file, problem -> DebugType.Mod.warn(PREFIX + "settings: %s", problem));
        DebugType.Mod.println(PREFIX + "settings: bar and log %s, timing report %s (%s)",
                settings.display() ? "on" : "off", settings.report() ? "on" : "off", file);
        return settings;
    }

    // the folder Lua's getFileWriter writes to, so the mod options page shares the settings file
    private static Path luaFolder() {
        return Path.of(ZomboidFileSystem.instance.getCacheDir(), "Lua");
    }

    // reads the game's state, which only the game thread may
    private static void ended(Progress.End end) {
        if (Thread.currentThread() != GameWindow.gameThread) {
            throw new IllegalStateException("the " + end.flow().label() + " ended on the thread "
                    + Thread.currentThread().getName() + ", not on the game thread");
        }
        Object[] args = {end.flow().label(), end.outcome().name().toLowerCase(Locale.ROOT),
                end.why() == null ? "" : " (" + end.why() + ")", end.nanos() / 1e9, end.step(),
                end.flow().steps().size(), Strings.step(end.at(), false), end.ownFrames(), end.interimFrames(),
                end.interimSkips() == 0 ? "" : " (" + end.interimSkips() + " skipped for OpenGL errors the game left"
                        + " pending)",
                Strings.gap(end.largestGap()), Strings.gap(end.largestVisibleGap()),
                end.javaModGap() == null ? "none" : Strings.gap(end.javaModGap()), Strings.gap(end.unknownGap())};
        DebugType.Mod.println(PREFIX + END_LINE, args);
        // empty while the report is off
        if (end.timed().isEmpty() || reportFailed) {
            return;
        }
        Report.Context context = new Report.Context(String.format(Locale.ROOT, END_LINE, args),
                Core.getInstance().getVersion(), Loading.class.getPackage().getImplementationVersion(),
                ZomboidFileSystem.instance.base.canonicalFile.getPath(), modFolders());
        Path folder = luaFolder().resolve(Reports.FOLDER);
        LocalDateTime time = LocalDateTime.now();
        REPORTS.execute(() -> write(end, context, folder, time));
    }

    private static Map<String, String> modFolders() {
        ZomboidFileSystem files = ZomboidFileSystem.instance;
        Map<String, String> folders = new LinkedHashMap<>();
        files.getModIDs().forEach(id -> folders.put(id, files.getModDir(id)));
        return Collections.unmodifiableMap(folders);
    }

    // the report is diagnostics: a failure ends it for the session, not the bar
    private static void write(Progress.End end, Report.Context context, Path folder, LocalDateTime time) {
        if (reportFailed) {
            return;
        }
        try {
            Path summary = Reports.write(folder, time, end.flow().name().toLowerCase(Locale.ROOT),
                    Report.summary(end, context), Report.csv(end, context), Report.filesCsv(end, context));
            DebugType.Mod.println(PREFIX + "load timing report: %s", summary);
        } catch (IOException | RuntimeException e) {
            reportFailed = true;
            PROGRESS.reportOff();
            DebugType.Mod.printException(e, PREFIX + "load timing report: off for this session: writing it to "
                    + folder + " failed", LogSeverity.Error);
        }
    }
}
