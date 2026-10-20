package com.pzlauncher.zabric.widener;

import com.pzlauncher.zabric.internal.GameFolders;
import com.pzlauncher.zabric.mods.impl.LaunchOverrides;
import com.pzlauncher.zabric.mods.impl.WidenRootsFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Where the game finds mods, worked out without the game ({@link GameFolders}), plus this launch's override folders and
 * the registered widening roots. Includes folders the game may not use this session: widening too much is harmless,
 * missing a list is not.
 */
final class ModFolders {
    /** A folder holding mods: {@code <dir>/<mod>}, or {@code <dir>/<item>/<itemMods>/<mod>} for Workshop items. */
    record Root(Path dir, String itemMods) {
        /** The folders that hold mod folders: {@code dir}, or each item's {@code itemMods}; only those that exist. */
        List<Path> modsFolders() throws IOException {
            if (!Files.isDirectory(dir)) {
                return List.of();
            }
            if (itemMods == null) {
                return List.of(dir);
            }
            List<Path> folders = new ArrayList<>();
            for (Path item : children(dir)) {
                Path dirOfMods = item.resolve(itemMods);
                if (Files.isDirectory(dirOfMods)) {
                    folders.add(dirOfMods);
                }
            }
            return folders;
        }
    }

    private ModFolders() {
    }

    static List<Root> roots() {
        List<Path> extra = new ArrayList<>(LaunchOverrides.folders());
        extra.addAll(WidenRootsFile.atGameStart());
        return roots(GameFolders.workshopContent(), GameFolders.cacheDir(), extra);
    }

    static List<Root> roots(List<Path> workshopContent, Path cache, List<Path> extra) {
        List<Root> roots = new ArrayList<>();
        workshopContent.forEach(content -> roots.add(new Root(content, "mods")));
        roots.add(new Root(cache.resolve("mods"), null));
        roots.add(new Root(cache.resolve("Workshop"), "Contents/mods"));
        extra.forEach(folder -> roots.add(new Root(folder, null)));
        return roots.stream().map(r -> new Root(r.dir().toAbsolutePath().normalize(), r.itemMods())).toList();
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

    static List<Path> children(Path dir) throws IOException {
        try (Stream<Path> list = Files.list(dir)) {
            return list.filter(Files::isDirectory).toList();
        }
    }
}
