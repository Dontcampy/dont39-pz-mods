package com.pzlauncher.zabric.internal;

import java.nio.file.Path;
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

    /** The game's cache folder: its {@code -cachedir} argument, else {@code ~/Zomboid}. */
    public static Path cacheDir() {
        // the agent sees only the command line the JVM recorded
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

    /** Where Zabric keeps its own files. */
    public static Path zabricDir() {
        return cacheDir().resolve("Zabric");
    }

    /**
     * The folders Steam installs Workshop items into, each item as {@code <folder>/<item id>/mods/<mod>}: the client's,
     * and a dedicated server's own. Either may not exist.
     */
    public static List<Path> workshopContent() {
        Path cwd = Path.of("").toAbsolutePath();
        return List.of(
                // client: the game folder is steamapps/common/ProjectZomboid of a Steam library
                cwd.resolve("../../workshop/content/" + APP).normalize(),
                cwd.resolve("steamapps/workshop/content/" + APP).normalize());
    }
}
