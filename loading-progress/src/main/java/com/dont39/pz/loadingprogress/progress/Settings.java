package com.dont39.pz.loadingprogress.progress;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The mod's two switches, from {@value #FILE} in the game's cache {@code Lua} folder, where the mod options page
 * ({@code Dont39LoadingProgress_Options.lua}) reads and writes the same lines.
 */
public record Settings(boolean display, boolean report) {
    public static final String FILE = "Dont39LoadingProgress.ini";
    public static final Settings DEFAULTS = new Settings(true, true);

    static final String DISPLAY = "display";
    static final String REPORT = "report";

    /**
     * The settings in {@code file}; a missing file is written with the defaults. An unreadable or invalid file goes to
     * {@code problem} as one line naming the file and key, and the defaults apply; the file is left as it is.
     */
    public static Settings load(Path file, Consumer<String> problem) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, text(DEFAULTS), StandardCharsets.UTF_8);
            } catch (IOException | RuntimeException written) {
                problem.accept(file + ": cannot write the default settings (" + written + ")");
            }
            return DEFAULTS;
        } catch (IOException | RuntimeException e) {
            problem.accept(file + ": cannot read it (" + e + "); " + usingDefaults());
            return DEFAULTS;
        }
        try {
            return parse(lines);
        } catch (IllegalArgumentException e) {
            problem.accept(file + ": " + e.getMessage() + "; " + usingDefaults());
            return DEFAULTS;
        }
    }

    /** The file's lines; throws naming the key or line that is not as {@link #text} writes it. */
    static Settings parse(List<String> lines) {
        Map<String, Boolean> values = new HashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            // a hand edit in Notepad may start it with a byte order mark; the options page skips it too
            String line = lines.get(i);
            if (i == 0 && line.startsWith("﻿")) {
                line = line.substring(1);
            }
            line = line.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int equals = line.indexOf('=');
            if (equals < 0) {
                throw new IllegalArgumentException("line " + (i + 1) + " is not \"key = value\": " + line);
            }
            String key = line.substring(0, equals).strip();
            String value = line.substring(equals + 1).strip().toLowerCase(Locale.ROOT);
            if (!key.equals(DISPLAY) && !key.equals(REPORT)) {
                throw new IllegalArgumentException("unknown key \"" + key + "\" (keys: " + DISPLAY + ", " + REPORT
                        + ")");
            }
            if (!value.equals("true") && !value.equals("false")) {
                throw new IllegalArgumentException("key " + key + " is \"" + line.substring(equals + 1).strip()
                        + "\", not true or false");
            }
            if (values.put(key, value.equals("true")) != null) {
                throw new IllegalArgumentException("key " + key + " is set twice");
            }
        }
        for (String key : List.of(DISPLAY, REPORT)) {
            if (!values.containsKey(key)) {
                throw new IllegalArgumentException("key " + key + " is missing");
            }
        }
        return new Settings(values.get(DISPLAY), values.get(REPORT));
    }

    /** The file's text for {@code settings}, with a comment per key. */
    static String text(Settings settings) {
        return "# Loading Progress, read once at each launch of the game; also on the mod options page.\n"
                + "# display: show the progress bar and the log of what loads (true or false)\n"
                + DISPLAY + " = " + settings.display() + "\n"
                + "# report: after each load, write a timing report to Lua/Dont39LoadingProgress/ in this folder"
                + " (true or false)\n"
                + REPORT + " = " + settings.report() + "\n";
    }

    private static String usingDefaults() {
        return "using " + DISPLAY + " = " + DEFAULTS.display() + ", " + REPORT + " = " + DEFAULTS.report()
                + " for this session";
    }
}
