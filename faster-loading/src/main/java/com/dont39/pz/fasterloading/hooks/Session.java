package com.dont39.pz.fasterloading.hooks;

import com.dont39.pz.fasterloading.settings.Fix;
import com.dont39.pz.fasterloading.settings.Settings;
import zombie.ZomboidFileSystem;
import zombie.core.Core;
import zombie.debug.DebugType;
import zombie.network.GameServer;

import java.nio.file.Path;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Whether the session's fixes run, decided at the first hook call: a client of the game build they reproduce. */
public final class Session {
    public static final String MOD_ID = "Dont39FasterLoading";
    public static final String PREFIX = "[" + MOD_ID + "] ";

    // the build whose code the fixes reproduce, by its revision; the next build may change any of it
    static final String REVISION = "4a0e9546ec";

    private static boolean decided;
    // null while the fixes do not run
    private static Settings settings;
    private static String mismatch;
    private static boolean wideningReported;
    private static boolean widened;

    private Session() {
    }

    /** Whether Zabric applied the mod's access widener list, as the main entrypoint reported it. */
    public static synchronized void widened(boolean applied) {
        wideningReported = true;
        widened = applied;
    }

    /**
     * Whether the members the mod's list widens are public; throws when the main entrypoint has not reported it yet,
     * which the mod list's load does before the game queues its first depth maps.
     */
    static synchronized boolean widened() {
        if (!wideningReported) {
            throw new IllegalStateException("a fix that needs the access widener ran before the mod's main entrypoint"
                    + " reported it");
        }
        return widened;
    }

    /**
     * Whether {@code fix} runs. Throws on the first call in a game build other than {@link #REVISION}, which stops the
     * mod's hooks through its default gate; the fixes stay off for later calls.
     */
    static synchronized boolean on(Fix fix) {
        if (!decided) {
            decided = true;
            decide();
            if (mismatch != null) {
                throw new IllegalStateException(mismatch);
            }
        }
        return settings != null && settings.on(fix);
    }

    // the game log is up from the first hook call on
    private static void decide() {
        // a dedicated server may preload the jar through a ZombieBuddy config it shares with a client
        if (GameServer.server) {
            DebugType.Mod.println(PREFIX + "%s loaded; dedicated server: not active", version());
            return;
        }
        String revision = Core.getGitRevisionString();
        if (!REVISION.equals(revision)) {
            mismatch = "game " + Core.getInstance().getVersion() + " is not 42.21 revision " + REVISION + ", the build"
                    + " whose code this version of the mod reproduces; it needs an update for this game version";
            return;
        }
        Path file = Path.of(ZomboidFileSystem.instance.getCacheDir(), "Lua", Settings.FILE);
        Settings loaded = Settings.load(file, problem -> DebugType.Mod.warn(PREFIX + "settings: %s", problem));
        String switches = Stream.of(Fix.values()).map(f -> f.label() + (loaded.on(f) ? " on" : " off"))
                .collect(Collectors.joining(", "));
        if (loaded.on().isEmpty()) {
            DebugType.Mod.println(PREFIX + "inactive: %s (%s)", switches, file);
            return;
        }
        settings = loaded;
        DebugType.Mod.println(PREFIX + "active: %s (%s)", switches, file);
    }

    private static String version() {
        return Session.class.getPackage().getImplementationVersion();
    }
}
