package com.dont39.pz.fasterloading.settings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * The switch of each {@link Fix}, from {@value #FILE} in the game's cache {@code Lua} folder, where the mod options
 * page ({@code Dont39FasterLoading_Options.lua}) reads and writes the same lines.
 */
public record Settings(Set<Fix> on) {
    public static final String FILE = "Dont39FasterLoading.ini";
    public static final Settings DEFAULTS = new Settings(EnumSet.allOf(Fix.class));

    public Settings {
        on = Collections.unmodifiableSet(on.isEmpty() ? EnumSet.noneOf(Fix.class) : EnumSet.copyOf(on));
    }

    public boolean on(Fix fix) {
        return on.contains(fix);
    }

    /**
     * The settings in {@code file}; a missing file is written with the defaults, and a file without some keys (from an
     * older version) gets them with their defaults. An unreadable or invalid file goes to {@code problem} as one line
     * naming the file and key, and the defaults apply; the file is left as it is.
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
        Map<Fix, Boolean> values;
        try {
            values = read(lines);
        } catch (IllegalArgumentException e) {
            problem.accept(file + ": " + e.getMessage() + "; " + usingDefaults());
            return DEFAULTS;
        }
        Settings settings = of(values);
        if (values.size() < Fix.values().length) {
            try {
                Files.writeString(file, text(settings), StandardCharsets.UTF_8);
            } catch (IOException | RuntimeException e) {
                problem.accept(file + ": cannot add the keys of the newer fixes (" + e + ")");
            }
        }
        return settings;
    }

    /**
     * The file's lines, a missing key at its default; throws naming the key or line that is not as {@link #text} writes
     * it.
     */
    static Settings parse(List<String> lines) {
        return of(read(lines));
    }

    private static Map<Fix, Boolean> read(List<String> lines) {
        Map<Fix, Boolean> values = new EnumMap<>(Fix.class);
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
            Fix fix = fix(key);
            if (fix == null) {
                throw new IllegalArgumentException("unknown key \"" + key + "\" (keys: " + keys() + ")");
            }
            if (!value.equals("true") && !value.equals("false")) {
                throw new IllegalArgumentException("key " + key + " is \"" + line.substring(equals + 1).strip()
                        + "\", not true or false");
            }
            if (values.put(fix, value.equals("true")) != null) {
                throw new IllegalArgumentException("key " + key + " is set twice");
            }
        }
        return values;
    }

    private static Settings of(Map<Fix, Boolean> values) {
        Set<Fix> on = EnumSet.noneOf(Fix.class);
        for (Fix fix : Fix.values()) {
            if (values.getOrDefault(fix, DEFAULTS.on(fix))) {
                on.add(fix);
            }
        }
        return new Settings(on);
    }

    /** The file's text for {@code settings}, with a comment per key. */
    static String text(Settings settings) {
        StringBuilder text = new StringBuilder(
                "# Faster Loading, read once at each launch of the game; also on the mod options page.\n");
        for (Fix fix : Fix.values()) {
            text.append("# ").append(fix.key()).append(": ").append(fix.description()).append(" (true or false)\n")
                    .append(fix.key()).append(" = ").append(settings.on(fix)).append('\n');
        }
        return text.toString();
    }

    private static Fix fix(String key) {
        for (Fix fix : Fix.values()) {
            if (fix.key().equals(key)) {
                return fix;
            }
        }
        return null;
    }

    private static String keys() {
        return EnumSet.allOf(Fix.class).stream().map(Fix::key).collect(Collectors.joining(", "));
    }

    private static String usingDefaults() {
        return "using " + EnumSet.allOf(Fix.class).stream().map(f -> f.key() + " = " + DEFAULTS.on(f))
                .collect(Collectors.joining(", ")) + " for this session";
    }
}
