package com.pzlauncher.zabric.hook.impl;

import com.pzlauncher.zabric.internal.Jars;
import com.pzlauncher.zabric.listing.HookListingFormat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The listing the processor writes per hook class (one file each, so bundling jars merge without clashes):
 * <pre>
 * zabric-hooks v1
 * mod &lt;mod id&gt;
 * gate default | gate &lt;gate class&gt;
 * patch &lt;patch class&gt;                                            (one or more)
 * hook &lt;target class&gt; &lt;method&gt; &lt;descriptor&gt; &lt;patch class&gt;   (one or more)
 * </pre>
 * Readers skip line kinds they do not need; a change old readers must understand takes a new header.
 */
public record HookListing(String source, String hookClass, String modId, String gate, List<String> patches,
                          List<Hook> hooks) {
    public static final String DIR = HookListingFormat.DIR;
    public static final String SUFFIX = HookListingFormat.SUFFIX;
    public static final String HEADER = HookListingFormat.HEADER;
    public static final String DEFAULT_GATE = HookListingFormat.DEFAULT_GATE;

    public record Hook(String owner, String method, String descriptor, String patch) {
        @Override
        public String toString() {
            return owner + "." + method + descriptor;
        }
    }

    public boolean defaultGate() {
        return gate.equals(DEFAULT_GATE);
    }

    public static HookListing parse(String source, String hookClass, List<String> lines) {
        if (lines.isEmpty() || !lines.get(0).strip().equals(HEADER)) {
            throw new IllegalArgumentException(source + ": the first line is not '" + HEADER + "'");
        }
        String modId = null;
        String gate = null;
        List<String> patches = new ArrayList<>();
        List<Hook> hooks = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.isEmpty()) {
                continue;
            }
            String[] f = line.split(" ");
            String at = source + ":" + (i + 1) + ": ";
            switch (f[0]) {
                case "mod" -> {
                    fields(at, line, f, 2);
                    if (modId != null) {
                        throw new IllegalArgumentException(at + "a second mod line");
                    }
                    modId = f[1];
                }
                case "gate" -> {
                    fields(at, line, f, 2);
                    if (gate != null) {
                        throw new IllegalArgumentException(at + "a second gate line");
                    }
                    gate = f[1];
                }
                case "patch" -> {
                    fields(at, line, f, 2);
                    patches.add(f[1]);
                }
                case "hook" -> {
                    fields(at, line, f, 5);
                    hooks.add(new Hook(f[1], f[2], f[3], f[4]));
                }
                default -> {
                }
            }
        }
        if (modId == null || gate == null || patches.isEmpty() || hooks.isEmpty()) {
            throw new IllegalArgumentException(source + ": needs a mod line, a gate line, and at least one patch and"
                    + " one hook line");
        }
        for (Hook hook : hooks) {
            if (!patches.contains(hook.patch())) {
                throw new IllegalArgumentException(source + ": the hook of " + hook + " names " + hook.patch()
                        + ", which is not a patch line");
            }
        }
        return new HookListing(source, hookClass, modId, gate, List.copyOf(patches), List.copyOf(hooks));
    }

    private static void fields(String at, String line, String[] f, int count) {
        if (f.length != count) {
            throw new IllegalArgumentException(at + "expected " + count + " fields separated by single spaces: " + line);
        }
    }

    public static HookListing read(Path location, String hookClass) throws IOException {
        String name = DIR + hookClass + SUFFIX;
        String source = location + "!/" + name;
        if (Files.isDirectory(location)) {
            Path file = location.resolve(name);
            if (!Files.isRegularFile(file)) {
                throw new NoSuchFileException(source);
            }
            return parse(source, hookClass, Files.readAllLines(file, StandardCharsets.UTF_8));
        }
        try (ZipFile zip = new ZipFile(location.toFile())) {
            ZipEntry entry = zip.getEntry(name);
            if (entry == null) {
                throw new NoSuchFileException(source);
            }
            return parse(source, hookClass, Jars.lines(zip, entry));
        }
    }

    public static List<HookListing> readAll(Path jar) throws IOException {
        List<HookListing> all = new ArrayList<>();
        if (Files.isDirectory(jar)) {
            Path dir = jar.resolve(DIR);
            if (!Files.isDirectory(dir)) {
                return all;
            }
            try (Stream<Path> files = Files.list(dir)) {
                for (Path file : files.sorted().toList()) {
                    String name = file.getFileName().toString();
                    if (name.endsWith(SUFFIX) && Files.isRegularFile(file)) {
                        String hookClass = name.substring(0, name.length() - SUFFIX.length());
                        all.add(parse(jar + "!/" + DIR + name, hookClass,
                                Files.readAllLines(file, StandardCharsets.UTF_8)));
                    }
                }
            }
            return all;
        }
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (ZipEntry entry : Collections.list(zip.entries())) {
                String name = entry.getName();
                if (!name.startsWith(DIR) || !name.endsWith(SUFFIX) || name.indexOf('/', DIR.length()) >= 0) {
                    continue;
                }
                String hookClass = name.substring(DIR.length(), name.length() - SUFFIX.length());
                all.add(parse(jar + "!/" + name, hookClass, Jars.lines(zip, entry)));
            }
        }
        return all;
    }

    // TargetCheck does the check; these stay for the published surface and the existing callers
    public static String problem(Class<?> hookClass) {
        return TargetCheck.problem(hookClass);
    }

    public static String gateProblem(Class<?> gate) {
        return TargetCheck.gateProblem(gate);
    }

    static String gateProblem(Class<?> gate, Path location) {
        return TargetCheck.gateProblem(gate, location);
    }

    static String problem(Class<?> hookClass, Path location) {
        return TargetCheck.problem(hookClass, location);
    }

    public String problem(ClassLoader loader) {
        return TargetCheck.problem(this, loader);
    }
}
