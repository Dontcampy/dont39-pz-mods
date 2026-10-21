package com.pzlauncher.zabric.internal;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GameFoldersTest {
    private static final String HOME = "/home/p";

    @Test
    void theLastCacheDirArgumentWins() {
        List<String> args = List.of("-Dx=-cachedir=/no", "zombie.network.GameServer", "-cachedir=/first",
                "-cachedir= /my dir ", "-port", "1");
        assertEquals(Path.of("/my dir"), GameFolders.cacheDir(args, "Linux", HOME, null));
    }

    @Test
    void withoutTheArgumentTheGameUsesZomboidInTheHomeFolder() {
        assertEquals(Path.of(HOME, "Zomboid"), GameFolders.cacheDir(List.of("-servername", "s"), "Linux", HOME, null));
    }

    @Test
    void deploymentUserCacheDirCountsOffWindowsOnly() {
        assertEquals(Path.of("/d", "Zomboid"), GameFolders.cacheDir(List.of(), "Linux", HOME, "/d"));
        assertEquals(Path.of(HOME, "Zomboid"), GameFolders.cacheDir(List.of(), "Windows 11", HOME, "/d"));
    }
}
