package com.pzlauncher.zabric.mods.impl;

import com.pzlauncher.zabric.loader.Loader.ModJar;
import com.pzlauncher.zabric.mods.JavaMods.Jar;
import com.pzlauncher.zabric.mods.LoadPhase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JarHistoryTest {
    private static final ModJar ZABRIC = new ModJar("Zabric", Path.of("/w/zabric.jar"), "aa");
    private static final ModJar MOD = new ModJar("m", Path.of("/w/m.jar"), "bb");
    private static final ModJar SERVERS_MOD = new ModJar("m", Path.of("/cache/m.jar"), "cc");

    @BeforeEach
    void emptyJvm() {
        JarHistory.reset();
    }

    @Test
    void aJarKeepsThePhaseItFirstLoadedIn() {
        JarHistory.preloaded(List.of(ZABRIC));
        JarHistory.passLoaded(List.of(ZABRIC, MOD));
        JarHistory.passLoaded(List.of(ZABRIC, MOD, SERVERS_MOD));

        Jar zabric = new Jar("Zabric", ZABRIC.jar(), "aa", LoadPhase.PRELOAD);
        Jar mod = new Jar("m", MOD.jar(), "bb", LoadPhase.STARTUP);
        Jar serversMod = new Jar("m", SERVERS_MOD.jar(), "cc", LoadPhase.LATER);
        assertEquals(List.of(zabric, mod, serversMod), JarHistory.loaded());
        assertEquals(List.of(zabric, mod, serversMod), JarHistory.lastPass());

        // leaving: the main menu's mods again
        JarHistory.passLoaded(List.of(ZABRIC, MOD));
        assertEquals(List.of(zabric, mod), JarHistory.lastPass());
        assertEquals(List.of(zabric, mod, serversMod), JarHistory.loaded());
    }

    @Test
    void theStartupPassIsTheFirstOneEvenWithoutPreloadedJars() {
        JarHistory.passLoaded(List.of(MOD));
        assertEquals(List.of(new Jar("m", MOD.jar(), "bb", LoadPhase.STARTUP)), JarHistory.loaded());
    }

    @Test
    void noPassYet() {
        JarHistory.preloaded(List.of(ZABRIC));
        assertThrows(IllegalStateException.class, JarHistory::lastPass);
    }

    @Test
    void aJarWithoutHashIsAnError() {
        JarHistory.passLoaded(List.of(new ModJar("m", MOD.jar(), null)));
        assertThrows(IllegalStateException.class, JarHistory::loaded);
        assertThrows(IllegalStateException.class, JarHistory::lastPass);
    }
}
