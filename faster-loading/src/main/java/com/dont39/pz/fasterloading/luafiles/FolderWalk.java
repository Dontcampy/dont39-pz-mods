package com.dont39.pz.fasterloading.luafiles;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiFunction;

/**
 * The Lua files {@code LuaManager.searchFolders(URI, File)} appends to the game's load list, in its order. The game
 * looks up a folder's canonical path once per entry in it; this looks it up once per folder, and not at all where the
 * path it built is already canonical.
 */
public final class FolderWalk {
    private final URI base;
    private final BiFunction<URI, String, String> relative;
    private final List<String> files = new ArrayList<>();
    private int canonicalCalls;

    private FolderWalk(URI base, BiFunction<URI, String, String> relative) {
        this.base = base;
        this.relative = relative;
    }

    /**
     * The game's entries for {@code folder}, each {@code relative.apply(base, absolutePath)} in lower case, as the
     * game calls {@code ZomboidFileSystem.getRelativeFile}. Throws where the game's walk would fail, before the caller
     * appends anything.
     */
    public static List<String> lua(URI base, File folder, BiFunction<URI, String, String> relative)
            throws IOException {
        return walk(base, folder, relative).files;
    }

    // with the number of canonical lookups, for the test
    static FolderWalk walk(URI base, File folder, BiFunction<URI, String, String> relative) throws IOException {
        FolderWalk walk = new FolderWalk(base, relative);
        walk.visit(folder, false);
        return walk;
    }

    List<String> files() {
        return files;
    }

    int canonicalCalls() {
        return canonicalCalls;
    }

    // listed: fo's path is a canonical folder's path plus a name that folder listed. The game asks the disk whether
    // fo is a folder and, for a folder, looks up its canonical path; one attribute read answers both for a listed
    // name, except where the lookup could change it.
    private void visit(File fo, boolean listed) throws IOException {
        Entry entry = listed ? entry(fo) : fo.isDirectory() ? Entry.LOOKUP : Entry.FILE;
        if (entry == Entry.FILE) {
            String path = fo.getAbsolutePath();
            if (path.toLowerCase().endsWith(".lua")) {
                files.add(relative.apply(base, path).toLowerCase(Locale.ENGLISH));
            }
            return;
        }
        String[] names = fo.list();
        if (names == null) {
            // the game's loop throws a NullPointerException here
            throw new IOException("the folder " + fo + " could not be listed");
        }
        if (names.length == 0) {
            return;
        }
        String parent;
        if (entry == Entry.CANONICAL) {
            parent = fo.getAbsolutePath();
        } else {
            canonicalCalls++;
            parent = fo.getCanonicalFile().getAbsolutePath();
        }
        for (String name : names) {
            visit(new File(parent + File.separator + name), true);
        }
    }

    private enum Entry {
        FILE,
        // a folder whose path is already canonical
        CANONICAL,
        // a folder whose path takes the game's lookup
        LOOKUP
    }

    // The lookup changes a listed name only through a link (Windows reparse points read as links or "other") or the
    // name normalization Windows applies to a trailing dot or space, which also fails the attribute read. There the
    // game's own folder check decides, following the link as it does.
    private static Entry entry(File fo) {
        String name = fo.getName();
        if (!name.endsWith(".") && !name.endsWith(" ")) {
            try {
                BasicFileAttributes attributes = Files.readAttributes(fo.toPath(), BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                if (!attributes.isSymbolicLink() && !attributes.isOther()) {
                    return attributes.isDirectory() ? Entry.CANONICAL : Entry.FILE;
                }
            } catch (IOException | InvalidPathException e) {
                // as below
            }
        }
        return fo.isDirectory() ? Entry.LOOKUP : Entry.FILE;
    }
}
