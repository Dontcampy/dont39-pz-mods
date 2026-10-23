package com.dont39.pz.fasterloading.settings;

import java.nio.file.Path;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The switch of each {@link Fix}, from {@value #FILE} in the game's cache {@code Lua} folder, where the mod options
 * page ({@code Dont39FasterLoading_Options.lua}) reads and writes the same lines.
 */
public record Settings(Set<Fix> on) {
    public static final String FILE = "Dont39FasterLoading.ini";
    static final Switches<Fix> SWITCHES = new Switches<>(Fix.class, FILE, "Faster Loading");
    public static final Settings DEFAULTS = new Settings(SWITCHES.defaults());

    public Settings {
        on = Collections.unmodifiableSet(on.isEmpty() ? EnumSet.noneOf(Fix.class) : EnumSet.copyOf(on));
    }

    public boolean on(Fix fix) {
        return on.contains(fix);
    }

    /** As {@link Switches#load}. */
    public static Settings load(Path file, Consumer<String> problem) {
        return new Settings(SWITCHES.load(file, problem));
    }

    static Settings parse(List<String> lines) {
        return new Settings(SWITCHES.parse(lines));
    }

    static String text(Settings settings) {
        return SWITCHES.text(settings.on);
    }
}
