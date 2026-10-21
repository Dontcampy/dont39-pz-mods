package com.pzlauncher.zabric.mods.impl;

import com.pzlauncher.zabric.internal.GameSide;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OverrideRequestTest {
    @TempDir
    Path dir;

    @Test
    void aValidRequestReadsBackAsWritten() throws Exception {
        Path folder = modsFolder("tree/111/mods", "Spiffo", "common");
        Path other = modsFolder("tree/222/mods", "Other", "42.13");
        Path file = file();
        OverrideRequest.write(file, Map.of("111", folder, "222", other), content());
        assertEquals(Map.of("111", folder, "222", other), OverrideRequest.read(file));
        try (Stream<Path> left = Files.list(file.getParent())) {
            assertEquals(List.of(file), left.toList(), "no temp file left over");
        }
    }

    @Test
    void aNewRequestReplacesThePendingOneAndAnEmptyOneWithdrawsIt() throws Exception {
        Path file = file();
        OverrideRequest.write(file, Map.of("111", modsFolder("a/mods", "A", "common")), content());
        Path b = modsFolder("b/mods", "B", "common");
        OverrideRequest.write(file, Map.of("222", b), content());
        assertEquals(Map.of("222", b), OverrideRequest.read(file));

        OverrideRequest.write(file, Map.of(), content());
        assertFalse(Files.exists(file));
        assertEquals(Map.of(), OverrideRequest.read(file));
    }

    @Test
    void anInvalidItemThrowsAndWritesNothing() throws Exception {
        Path file = file();
        Path pending = modsFolder("pending/mods", "P", "common");
        OverrideRequest.write(file, Map.of("333", pending), content());

        Path valid = modsFolder("valid/mods", "V", "common");
        Path empty = Files.createDirectories(dir.resolve("empty/mods"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> OverrideRequest.write(file,
                Map.of("111", valid, "12a", valid, "222", Path.of("relative/mods"), "444", dir.resolve("missing"),
                        "555", empty), content()));
        assertEquals("launch override not requested: '12a' is not a Workshop item id; item 222: "
                + Path.of("relative/mods") + " is not an absolute path; item 444: " + dir.resolve("missing")
                + " does not exist; item 555: " + empty + " holds no mod folder (one with common/mod.info or a"
                + " version folder's mod.info)", e.getMessage());
        assertEquals(Map.of("333", pending), OverrideRequest.read(file));
    }

    @Test
    void theLoaderAndZabricsLayersAreRefused() throws Exception {
        Path zabric = modsFolder("z/mods", "Zabric", "common");
        Path kotlin = modsFolder("k/mods", "ZabricKotlin", "42");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> OverrideRequest.write(file(),
                Map.of("111", zabric, "222", kotlin), content()));
        assertEquals("launch override not requested: item 111: " + zabric + " holds the mod Zabric, which cannot be"
                + " overridden; item 222: " + kotlin + " holds the mod ZabricKotlin, which cannot be overridden",
                e.getMessage());

        // the installed copy holds it even though the requested folder does not
        Path installed = modsFolder("content/333/mods", "ZombieBuddy", "common");
        Path innocent = modsFolder("i/mods", "Innocent", "common");
        e = assertThrows(IllegalArgumentException.class, () -> OverrideRequest.write(file(), Map.of("333", innocent),
                content()));
        assertEquals("launch override not requested: item 333: its installed copy " + installed + " holds the mod"
                + " ZombieBuddy, which cannot be overridden", e.getMessage());
        assertFalse(Files.exists(file()));
    }

    @Test
    void peekingChecksAndKeepsTheFileTakingDeletesIt() throws Exception {
        Path file = file();
        assertNull(OverrideRequest.peek(file, content()));
        assertNull(OverrideRequest.take(file));

        Path folder = modsFolder("tree/111/mods", "Spiffo", "common");
        OverrideRequest.write(file, Map.of("111", folder), content());
        OverrideRequest.Peeked peeked = OverrideRequest.peek(file, content());
        assertEquals(Map.of("111", folder), peeked.request());
        assertArrayEquals(Files.readAllBytes(file), peeked.content());
        assertArrayEquals(peeked.content(), OverrideRequest.take(file));
        assertFalse(Files.exists(file));

        // valid when written, not at the next start
        OverrideRequest.write(file, Map.of("111", folder), content());
        Files.delete(folder.resolve("Spiffo/common/mod.info"));
        OverrideRequest.Invalid e = assertThrows(OverrideRequest.Invalid.class,
                () -> OverrideRequest.peek(file, content()));
        assertTrue(e.getMessage().contains("holds no mod folder"), e.getMessage());

        Files.writeString(file, "111=\\u00zz\n");
        assertThrows(IllegalStateException.class, () -> OverrideRequest.peek(file, content()));

        Files.write(file, new byte[]{'1', '=', (byte) 0xff});
        assertThrows(IOException.class, () -> OverrideRequest.peek(file, content()));
        assertTrue(Files.exists(file), "only the game's main method deletes it");
    }

    private Path file() {
        return OverrideRequest.file(dir.resolve("cache/Zabric"), GameSide.CLIENT);
    }

    private List<Path> content() {
        return List.of(dir.resolve("content"));
    }

    // a folder of mod folders holding one mod with its mod.info in versionDir
    private Path modsFolder(String path, String modId, String versionDir) throws IOException {
        Path folder = dir.resolve(path);
        Path inf = folder.resolve(modId).resolve(versionDir).resolve("mod.info");
        Files.createDirectories(inf.getParent());
        Files.writeString(inf, "name=" + modId + "\nid=" + modId + "\n");
        return folder;
    }
}
