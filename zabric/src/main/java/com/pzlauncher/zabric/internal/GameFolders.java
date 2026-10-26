package com.pzlauncher.zabric.internal;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The game's folders, worked out without the game: at premain Steam is not up and the game has not parsed its
 * arguments. Premain and the running game use the same answers, so files written in one launch are found by the next.
 */
public final class GameFolders {
    private static final String APP = "108600";
    private static final String CACHE_DIR_ARG = "-cachedir=";

    private GameFolders() {
    }

    /** The game's cache folder, as the game works it out from its {@code -cachedir} argument. */
    public static Path cacheDir() {
        return cacheDir(GameArgs.read(), System.getProperty("os.name"), System.getProperty("user.home"),
                System.getProperty("deployment.user.cachedir"));
    }

    // as the game's main and ZomboidFileSystem.getCacheDir work it out
    static Path cacheDir(List<String> args, String osName, String userHome, String deploymentCacheDir) {
        String dir = null;
        for (String arg : args) {
            if (arg.startsWith(CACHE_DIR_ARG)) {
                dir = arg.substring(CACHE_DIR_ARG.length()).trim();
            }
        }
        if (dir != null) {
            return Path.of(dir);
        }
        boolean deployment = deploymentCacheDir != null && !osName.startsWith("Win");
        return Path.of(deployment ? deploymentCacheDir : userHome, "Zomboid");
    }

    /** Where Zabric keeps its own files. */
    public static Path zabricDir() {
        return cacheDir().resolve("Zabric");
    }

    /**
     * The folders Steam installs Workshop items into, each item as {@code <folder>/<item id>/mods/<mod>}: the client's,
     * and a dedicated server's own. Either may not exist.
     */
    public static List<Path> workshopContent() {
        return workshopContent(Path.of("").toAbsolutePath().normalize());
    }

    static List<Path> workshopContent(Path cwd) {
        List<Path> folders = new ArrayList<>();
        // client: the game runs in steamapps/common/ProjectZomboid of a Steam library, on Linux in its projectzomboid
        for (Path dir = cwd; dir != null && dir.getParent() != null; dir = dir.getParent()) {
            Path steamapps = dir.getParent();
            if (isNamed(dir, "common") && isNamed(steamapps, "steamapps")) {
                folders.add(steamapps.resolve("workshop/content/" + APP));
                break;
            }
        }
        folders.add(cwd.resolve("steamapps/workshop/content/" + APP));
        return List.copyOf(folders);
    }

    private static boolean isNamed(Path dir, String name) {
        return dir.getFileName() != null && dir.getFileName().toString().equalsIgnoreCase(name);
    }
}
