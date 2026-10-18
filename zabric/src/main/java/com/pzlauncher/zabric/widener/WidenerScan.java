package com.pzlauncher.zabric.widener;

import com.pzlauncher.zabric.classtweaker.ClassTweaker;
import com.pzlauncher.zabric.classtweaker.ClassTweaker.Entry;
import com.pzlauncher.zabric.internal.Jars;

import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Finds the {@code .classtweaker} lists in the jars of every installed mod at premain. */
final class WidenerScan {
    static final String LIST_SUFFIX = ".classtweaker";

    /** One mod's list as found in one jar; {@code problem} is set when it was not applied. */
    record Listed(String modId, String source, List<Entry> entries, String problem) {
    }

    /**
     * {@code lists} by mod id, then by the list's sha256; {@code unreadable}: the error for each folder or jar it could
     * not read.
     */
    record Found(List<Path> roots, Map<String, Map<String, Listed>> lists, Map<Path, String> unreadable) {
    }

    private WidenerScan() {
    }

    static Found run() {
        List<ModFolders.Root> roots = ModFolders.roots();
        Map<String, Map<String, Listed>> lists = new LinkedHashMap<>();
        Map<Path, String> unreadable = new LinkedHashMap<>();
        for (ModFolders.Root root : roots) {
            List<Path> mods;
            try {
                mods = root.modFolders();
            } catch (IOException | RuntimeException e) {
                unreadable.put(real(root.dir()), e.toString());
                continue;
            }
            for (Path mod : mods) {
                try {
                    for (Path jar : ModFolders.jars(mod)) {
                        readLists(jar, lists, unreadable);
                    }
                } catch (IOException | RuntimeException e) {
                    unreadable.put(real(mod), e.toString());
                }
            }
        }
        List<Path> scanned = roots.stream().map(r -> real(r.dir())).toList();
        return new Found(scanned, lists, unreadable);
    }

    private static void readLists(Path jar, Map<String, Map<String, Listed>> lists, Map<Path, String> unreadable) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (ZipEntry entry : Collections.list(zip.entries())) {
                String name = entry.getName();
                String file = name.substring(Math.min(name.length(), AccessWidener.LIST_DIR.length()));
                if (!name.startsWith(AccessWidener.LIST_DIR) || !file.endsWith(LIST_SUFFIX) || file.contains("/")) {
                    continue;
                }
                String modId = file.substring(0, file.length() - LIST_SUFFIX.length());
                byte[] bytes = Jars.bytes(zip, entry);
                String source = jar + "!/" + name;
                Listed listed;
                try {
                    listed = new Listed(modId, source, ClassTweaker.parse(source, Jars.lines(bytes)), null);
                } catch (IllegalArgumentException e) {
                    listed = new Listed(modId, source, List.of(), "cannot read the list: " + e.getMessage());
                }
                lists.computeIfAbsent(modId, k -> new LinkedHashMap<>()).putIfAbsent(sha256(bytes), listed);
            }
        } catch (IOException | RuntimeException e) {
            unreadable.put(real(jar), e.toString());
        }
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // every JRE has SHA-256
        }
    }

    // compare as the file system does (case, links); a folder that does not exist yet stays as it is
    static Path real(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return path.toAbsolutePath().normalize();
        }
    }
}
