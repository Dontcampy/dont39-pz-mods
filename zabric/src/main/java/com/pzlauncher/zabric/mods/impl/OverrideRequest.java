package com.pzlauncher.zabric.mods.impl;

import com.pzlauncher.zabric.internal.AtomicWrite;
import com.pzlauncher.zabric.internal.GameFolders;
import com.pzlauncher.zabric.internal.GameSide;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The request file of a launch override: Workshop item id = absolute path of the folder of mod folders that replaces
 * the item's {@code mods} folder. One file per side, so that a dedicated server started on the same machine leaves the
 * client's request alone. Plain file IO, as premain reads it.
 */
public final class OverrideRequest {
    // they run before any pass over the mod list, so a launch cannot take their code from another folder
    static final Set<String> REFUSED = Set.of("ZombieBuddy", "Zabric", "ZabricKotlin");
    private static final Pattern ITEM_ID = Pattern.compile("[0-9]+");

    /** A request that reads but names items that cannot be overridden. */
    static final class Invalid extends IllegalStateException {
        Invalid(String problems) {
            super(problems);
        }
    }

    private OverrideRequest() {
    }

    public static Path file(GameSide side) {
        return file(GameFolders.zabricDir(), side);
    }

    static Path file(Path zabricDir, GameSide side) {
        return zabricDir.resolve("launch-override-" + side.id + ".properties");
    }

    /**
     * Writes {@code request} to {@code file}, replacing a pending one; an empty request removes it. Throws naming every
     * invalid item, and then writes nothing.
     */
    public static void write(Path file, Map<String, Path> request, List<Path> workshopContent) throws IOException {
        Map<String, Path> sorted = new TreeMap<>(request);
        List<String> problems = problems(sorted, workshopContent);
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("launch override not requested: " + String.join("; ", problems));
        }
        if (sorted.isEmpty()) {
            Files.deleteIfExists(file);
            return;
        }
        Properties properties = new Properties();
        sorted.forEach((id, folder) -> properties.setProperty(id, folder.normalize().toString()));
        StringWriter out = new StringWriter();
        properties.store(out, "Zabric launch override for the next launch: Workshop item id = folder of mod folders");
        AtomicWrite.write(file, out.toString());
    }

    /** The request in {@code file}, empty when there is none; throws when the file does not read as one. */
    public static Map<String, Path> read(Path file) throws IOException {
        if (!Files.exists(file)) {
            return Map.of();
        }
        return parse(file, Files.readString(file, StandardCharsets.UTF_8));
    }

    /** A request file as game start read it: its bytes, and the request in them. */
    record Peeked(byte[] content, Map<String, Path> request) {
    }

    /**
     * At premain, which cannot tell which side it runs on: reads and checks the request without deleting it; null when
     * there is no file. Throws when anything is wrong with it: no partial request.
     */
    static Peeked peek(Path file, List<Path> workshopContent) throws IOException {
        if (!Files.exists(file)) {
            return null;
        }
        byte[] content = Files.readAllBytes(file);
        Map<String, Path> request = parse(file, utf8(content));
        List<String> problems = problems(request, workshopContent);
        if (!problems.isEmpty()) {
            throw new Invalid(String.join("; ", problems));
        }
        return new Peeked(content, request);
    }

    /**
     * At the start of the game's main method: the file's bytes, deleted before anything else, so that a crash later in
     * the launch cannot apply it again; null when there is no file.
     */
    static byte[] take(Path file) throws IOException {
        if (!Files.exists(file)) {
            return null;
        }
        byte[] content = Files.readAllBytes(file);
        Files.delete(file);
        return content;
    }

    private static String utf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static Map<String, Path> parse(Path file, String content) throws IOException {
        Properties properties = new Properties();
        try {
            properties.load(new StringReader(content));
            Map<String, Path> request = new TreeMap<>();
            for (String id : properties.stringPropertyNames()) {
                request.put(id, Path.of(properties.getProperty(id)));
            }
            return Collections.unmodifiableMap(request);
        } catch (RuntimeException e) {
            throw new IllegalStateException(file + " is not a launch override request: " + e, e);
        }
    }

    /** What is wrong with each item of {@code request}, empty when nothing is. */
    static List<String> problems(Map<String, Path> request, List<Path> workshopContent) {
        List<String> problems = new ArrayList<>();
        request.forEach((id, folder) -> {
            if (!ITEM_ID.matcher(id).matches()) {
                problems.add("'" + id + "' is not a Workshop item id");
                return;
            }
            if (!folder.isAbsolute()) {
                problems.add("item " + id + ": " + folder + " is not an absolute path");
                return;
            }
            if (!Files.isDirectory(folder)) {
                problems.add("item " + id + ": " + folder + " does not exist");
                return;
            }
            try {
                Map<Path, Set<String>> mods = mods(folder);
                if (mods.isEmpty()) {
                    problems.add("item " + id + ": " + folder + " holds no mod folder (one with common/mod.info or"
                            + " a version folder's mod.info)");
                }
                refused(mods).forEach(modId -> problems.add("item " + id + ": " + folder + " holds the mod " + modId
                        + ", which cannot be overridden"));
            } catch (IOException | RuntimeException e) {
                problems.add("item " + id + ": cannot read " + folder + ": " + e);
            }
            for (Path content : workshopContent) {
                Path installed = content.resolve(id).resolve("mods");
                if (!Files.isDirectory(installed)) {
                    continue;
                }
                try {
                    refused(mods(installed)).forEach(modId -> problems.add("item " + id + ": its installed copy "
                            + installed + " holds the mod " + modId + ", which cannot be overridden"));
                } catch (IOException | RuntimeException e) {
                    problems.add("item " + id + ": cannot read its installed copy " + installed + ": " + e);
                }
            }
        });
        return problems;
    }

    private static Set<String> refused(Map<Path, Set<String>> mods) {
        Set<String> refused = new LinkedHashSet<>();
        mods.values().forEach(ids -> ids.stream().filter(REFUSED::contains).forEach(refused::add));
        return refused;
    }

    // the mod folders the game would find in a folder of mod folders, with every id their mod.info files name
    private static Map<Path, Set<String>> mods(Path modsFolder) throws IOException {
        Map<Path, Set<String>> mods = new TreeMap<>();
        for (Path mod : children(modsFolder)) {
            Set<String> ids = new LinkedHashSet<>();
            boolean found = false;
            for (Path version : children(mod)) {
                Path inf = version.resolve("mod.info");
                if (Files.isRegularFile(inf)) {
                    found = true;
                    ids.addAll(ids(inf));
                }
            }
            if (found) {
                mods.put(mod, ids);
            }
        }
        return mods;
    }

    private static List<String> ids(Path inf) throws IOException {
        // the ids that matter are ASCII; this never fails on another encoding
        String text = new String(Files.readAllBytes(inf), StandardCharsets.ISO_8859_1);
        List<String> ids = new ArrayList<>();
        text.lines().forEach(line -> {
            int eq = line.indexOf('=');
            if (eq > 0 && line.substring(0, eq).strip().equalsIgnoreCase("id")) {
                ids.add(line.substring(eq + 1).strip());
            }
        });
        return ids;
    }

    private static List<Path> children(Path dir) throws IOException {
        try (Stream<Path> list = Files.list(dir)) {
            return list.filter(Files::isDirectory).toList();
        }
    }
}
