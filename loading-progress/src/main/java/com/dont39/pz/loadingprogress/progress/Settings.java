package com.dont39.pz.loadingprogress.progress;

import com.dont39.pz.loadingprogress.settings.Switches;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The mod's two switches, from {@value #FILE} in the game's cache {@code Lua} folder, where the mod options page
 * ({@code Dont39LoadingProgress_Options.lua}) reads and writes the same lines.
 */
public record Settings(boolean display, boolean report) {
    public static final String FILE = "Dont39LoadingProgress.ini";
    public static final Settings DEFAULTS = new Settings(true, true);

    enum Switch implements Switches.Key {
        DISPLAY("display", "show the progress bar and the log of what loads"),
        REPORT("report", "after each load, write a timing report to Lua/Dont39LoadingProgress/ in this folder");

        private final String key;
        private final String description;

        Switch(String key, String description) {
            this.key = key;
            this.description = description;
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public String description() {
            return description;
        }
    }

    static final Switches<Switch> SWITCHES = new Switches<>(Switch.class, FILE, "Loading Progress");

    /** As {@link Switches#load}. */
    public static Settings load(Path file, Consumer<String> problem) {
        return of(SWITCHES.load(file, problem));
    }

    static Settings parse(List<String> lines) {
        return of(SWITCHES.parse(lines));
    }

    static String text(Settings settings) {
        Set<Switch> on = EnumSet.noneOf(Switch.class);
        if (settings.display()) {
            on.add(Switch.DISPLAY);
        }
        if (settings.report()) {
            on.add(Switch.REPORT);
        }
        return SWITCHES.text(on);
    }

    private static Settings of(Set<Switch> on) {
        return new Settings(on.contains(Switch.DISPLAY), on.contains(Switch.REPORT));
    }
}
