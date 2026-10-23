package com.dont39.pz.modsettings;

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
 * A mod's switches in an ini file in the game's cache {@code Lua} folder, where its mod options page
 * ({@code Switches.lua} beside this file, shipped as {@code <mod id>_Switches.lua}) reads and writes the same lines.
 * Every switch is on by default.
 *
 * <p>Compiled into each mod under the mod's own package by {@code pz-switches}: the mods share one class loader.
 */
public final class Switches<K extends Enum<K> & Switches.Key> {
    /** A switch: its key in the file and the text of the comment line above it. */
    public interface Key {
        String key();

        String description();
    }

    private final Class<K> type;
    private final String file;
    private final String header;

    /** {@code name} goes into the file's first line, as the options page writes it. */
    public Switches(Class<K> type, String file, String name) {
        this.type = type;
        this.file = file;
        this.header = "# " + name + ", read once at each launch of the game; also on the mod options page.";
    }

    /** The file's name. */
    public String file() {
        return file;
    }

    /** The file's first line. */
    public String header() {
        return header;
    }

    /** The keys in the file's order. */
    public List<K> keys() {
        return List.of(type.getEnumConstants());
    }

    /** Every switch on. */
    public Set<K> defaults() {
        return Collections.unmodifiableSet(EnumSet.allOf(type));
    }

    /**
     * The switches on in {@code path}; a missing file is written with the defaults, and a file without some keys (from
     * an older version) gets them with their defaults. An unreadable or invalid file goes to {@code problem} as one line
     * naming the file and key, and the defaults apply; the file is left as it is.
     */
    public Set<K> load(Path path, Consumer<String> problem) {
        List<String> lines;
        try {
            lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            try {
                Files.createDirectories(path.getParent());
                Files.writeString(path, text(defaults()), StandardCharsets.UTF_8);
            } catch (IOException | RuntimeException written) {
                problem.accept(path + ": cannot write the default settings (" + written + ")");
            }
            return defaults();
        } catch (IOException | RuntimeException e) {
            problem.accept(path + ": cannot read it (" + e + "); " + usingDefaults());
            return defaults();
        }
        Map<K, Boolean> values;
        try {
            values = read(lines);
        } catch (IllegalArgumentException e) {
            problem.accept(path + ": " + e.getMessage() + "; " + usingDefaults());
            return defaults();
        }
        Set<K> on = on(values);
        if (values.size() < keys().size()) {
            try {
                Files.writeString(path, text(on), StandardCharsets.UTF_8);
            } catch (IOException | RuntimeException e) {
                problem.accept(path + ": cannot add the missing keys (" + e + ")");
            }
        }
        return on;
    }

    /**
     * The file's lines, a missing key at its default; throws naming the key or line that is not as {@link #text}
     * writes it.
     */
    public Set<K> parse(List<String> lines) {
        return on(read(lines));
    }

    /** The file's text with the switches in {@code on} on, a comment per key. */
    public String text(Set<K> on) {
        StringBuilder text = new StringBuilder(header).append('\n');
        for (K key : keys()) {
            text.append("# ").append(key.key()).append(": ").append(key.description()).append(" (true or false)\n")
                    .append(key.key()).append(" = ").append(on.contains(key)).append('\n');
        }
        return text.toString();
    }

    private Map<K, Boolean> read(List<String> lines) {
        Map<K, Boolean> values = new EnumMap<>(type);
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
            String name = line.substring(0, equals).strip();
            String value = line.substring(equals + 1).strip().toLowerCase(Locale.ROOT);
            K key = key(name);
            if (key == null) {
                throw new IllegalArgumentException("unknown key \"" + name + "\" (keys: " + names() + ")");
            }
            if (!value.equals("true") && !value.equals("false")) {
                throw new IllegalArgumentException("key " + name + " is \"" + line.substring(equals + 1).strip()
                        + "\", not true or false");
            }
            if (values.put(key, value.equals("true")) != null) {
                throw new IllegalArgumentException("key " + name + " is set twice");
            }
        }
        return values;
    }

    private Set<K> on(Map<K, Boolean> values) {
        Set<K> on = EnumSet.noneOf(type);
        for (K key : keys()) {
            if (values.getOrDefault(key, true)) {
                on.add(key);
            }
        }
        return Collections.unmodifiableSet(on);
    }

    private K key(String name) {
        for (K key : keys()) {
            if (key.key().equals(name)) {
                return key;
            }
        }
        return null;
    }

    private String names() {
        return keys().stream().map(Key::key).collect(Collectors.joining(", "));
    }

    private String usingDefaults() {
        return "using " + keys().stream().map(k -> k.key() + " = true").collect(Collectors.joining(", "))
                + " for this session";
    }
}
