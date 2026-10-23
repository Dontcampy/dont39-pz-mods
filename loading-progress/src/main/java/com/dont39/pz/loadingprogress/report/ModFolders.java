package com.dont39.pz.loadingprogress.report;

import com.dont39.pz.loadingprogress.progress.Line;
import com.dont39.pz.loadingprogress.progress.Timed;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Which mod a log line belongs to: mods and Java mods by id, Lua and script files by the folder that holds them. */
final class ModFolders {
    private record Folder(String prefix, String id) {
    }

    // the deepest first, should one mod's folder hold another's
    private final List<Folder> folders = new ArrayList<>();
    private final String game;

    /**
     * {@code folders}: each loaded mod's folder by its id; a null folder matches no file. {@code game}: the game's
     * folder.
     */
    ModFolders(Map<String, String> folders, String game) {
        folders.forEach((id, folder) -> {
            if (folder != null && !folder.isBlank()) {
                this.folders.add(new Folder(normalized(folder) + "/", id));
            }
        });
        this.folders.sort(Comparator.comparingInt((Folder f) -> f.prefix().length()).reversed());
        this.game = normalized(Objects.requireNonNull(game, "the game's folder")) + "/";
    }

    /** The mod's id, or null for the game's own files and the kinds that belong to no mod. */
    String modOf(Timed timed) {
        Line line = timed.line();
        if (line.isStep()) {
            return null;
        }
        return switch (line.item()) {
            case MOD, JAVA_MOD -> line.name();
            case LUA, SCRIPT -> timed.path() == null ? null : byPath(timed.path());
            default -> null;
        };
    }

    /** The mod whose folder holds {@code path}, or null. */
    String modOfPath(String path) {
        return path == null ? null : byPath(path);
    }

    /** Whether the game's folder holds {@code path}; a mod's folder inside it is {@link #modOfPath}'s. */
    boolean inGame(String path) {
        return path != null && normalized(path).startsWith(game);
    }

    /** Whether {@code name} is an absolute file path, not an asset's or a pack's name. */
    static boolean isFile(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        char first = name.charAt(0);
        if (first == '/' || first == '\\') {
            return true;
        }
        return name.length() >= 3 && Character.isLetter(first) && name.charAt(1) == ':'
                && (name.charAt(2) == '/' || name.charAt(2) == '\\');
    }

    private String byPath(String path) {
        String file = normalized(path);
        for (Folder folder : folders) {
            if (file.startsWith(folder.prefix())) {
                return folder.id();
            }
        }
        return null;
    }

    // the game mixes separators; Windows file names ignore case
    private static String normalized(String path) {
        String slashes = path.replace('\\', '/');
        while (slashes.endsWith("/")) {
            slashes = slashes.substring(0, slashes.length() - 1);
        }
        return slashes.toLowerCase(Locale.ROOT);
    }
}
