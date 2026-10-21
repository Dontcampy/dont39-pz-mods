package com.pzlauncher.zabric.mods.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WidenRootsFileTest {
    @TempDir
    Path dir;

    @Test
    void foldersAreAddedOnceAndDroppedWhenGone() throws Exception {
        Path file = dir.resolve("cache/Zabric").resolve(WidenRootsFile.FILE);
        Path a = Files.createDirectories(dir.resolve("a/mods"));
        Path b = Files.createDirectories(dir.resolve("b/mods"));
        WidenRootsFile.add(file, a);
        WidenRootsFile.add(file, b);
        WidenRootsFile.add(file, a);
        assertEquals(new WidenRootsFile.Read(List.of(a, b), List.of()), WidenRootsFile.read(file));

        Files.delete(b);
        Files.writeString(file, Files.readString(file) + "relative/mods\n");
        WidenRootsFile.Read read = WidenRootsFile.read(file);
        assertEquals(new WidenRootsFile.Read(List.of(a), List.of(b.toString(), "relative/mods")), read);

        WidenRootsFile.drop(file, read.dropped());
        assertEquals(a + "\n", Files.readString(file));
        WidenRootsFile.drop(file, List.of(a.toString()));
        assertFalse(Files.exists(file));
        assertEquals(new WidenRootsFile.Read(List.of(), List.of()), WidenRootsFile.read(file));
    }

    @Test
    void onlyExistingAbsoluteFolders() {
        Path file = dir.resolve(WidenRootsFile.FILE);
        assertThrows(IllegalArgumentException.class, () -> WidenRootsFile.add(file, Path.of("relative")));
        assertThrows(IllegalArgumentException.class, () -> WidenRootsFile.add(file, dir.resolve("missing")));
        assertFalse(Files.exists(file));
    }
}
