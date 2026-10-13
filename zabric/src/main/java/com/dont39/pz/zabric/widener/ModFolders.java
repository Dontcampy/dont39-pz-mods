package com.dont39.pz.zabric.widener;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Where the game finds mods, worked out without the game: at premain Steam is not up and the game has not parsed its
 * arguments. Includes folders the game may not use this session: widening too much is harmless, missing a list is not.
 */
final class ModFolders {
    private static final String APP = "108600";
    private static final String CACHE_DIR_ARG = "-cachedir=";

    /** A folder holding mods: {@code <dir>/<mod>}, or {@code <dir>/<item>/<itemMods>/<mod>} for Workshop items. */
    record Root(Path dir, String itemMods) {
        List<Path> modFolders() throws IOException {
            if (!Files.isDirectory(dir)) {
                return List.of();
            }
            if (itemMods == null) {
                return children(dir);
            }
            List<Path> mods = new ArrayList<>();
            for (Path item : children(dir)) {
                Path dirOfMods = item.resolve(itemMods);
                if (Files.isDirectory(dirOfMods)) {
                    mods.addAll(children(dirOfMods));
                }
            }
            return mods;
        }
    }

    private ModFolders() {
    }

    static List<Root> roots() {
        Path cwd = Path.of("").toAbsolutePath();
        Path cache = cacheDir();
        return Stream.of(
                // client: the game folder is steamapps/common/ProjectZomboid of a Steam library
                new Root(cwd.resolve("../../workshop/content/" + APP), "mods"),
                // dedicated server: downloads into its own folder
                new Root(cwd.resolve("steamapps/workshop/content/" + APP), "mods"),
                new Root(cache.resolve("mods"), null),
                new Root(cache.resolve("Workshop"), "Contents/mods")
        ).map(r -> new Root(r.dir().normalize(), r.itemMods())).toList();
    }

    /** Every jar under a {@code media/java} folder of the mod folder, in any version folder. */
    static List<Path> jars(Path modFolder) throws IOException {
        List<Path> javaDirs = new ArrayList<>();
        javaDirs.add(modFolder.resolve("media/java"));
        for (Path child : children(modFolder)) {
            javaDirs.add(child.resolve("media/java"));
        }
        List<Path> jars = new ArrayList<>();
        for (Path dir : javaDirs) {
            if (Files.isDirectory(dir)) {
                try (Stream<Path> files = Files.walk(dir)) {
                    files.filter(p -> p.getFileName().toString().endsWith(".jar") && Files.isRegularFile(p))
                            .forEach(jars::add);
                }
            }
        }
        return jars;
    }

    // the game's -cachedir argument; the agent sees only the command line the JVM recorded
    private static Path cacheDir() {
        String command = System.getProperty("sun.java.command", "");
        int at = command.indexOf(CACHE_DIR_ARG);
        while (at > 0 && command.charAt(at - 1) != ' ') {
            at = command.indexOf(CACHE_DIR_ARG, at + 1);
        }
        if (at < 0) {
            return Path.of(System.getProperty("user.home"), "Zomboid");
        }
        String value = command.substring(at + CACHE_DIR_ARG.length());
        int next = value.indexOf(" -");
        value = (next < 0 ? value : value.substring(0, next)).strip();
        if (value.length() > 1 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        return Path.of(value);
    }

    private static List<Path> children(Path dir) throws IOException {
        try (Stream<Path> list = Files.list(dir)) {
            return list.filter(Files::isDirectory).toList();
        }
    }
}
