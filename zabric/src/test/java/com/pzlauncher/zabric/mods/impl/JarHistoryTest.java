package com.pzlauncher.zabric.mods.impl;

import com.pzlauncher.zabric.loader.Loader.ModJar;
import com.pzlauncher.zabric.mods.LoadPhase;
import com.pzlauncher.zabric.mods.impl.JarHistory.Entry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JarHistoryTest {
    private static final ModJar ZABRIC = new ModJar("Zabric", Path.of("/w/zabric.jar"));
    private static final ModJar MOD = new ModJar("m", Path.of("/w/m.jar"));
    private static final ModJar SERVERS_MOD = new ModJar("m", Path.of("/cache/m.jar"));
    private static final Supplier<Map<Path, String>> HASHES = () -> Map.of(ZABRIC.jar(), "aa", MOD.jar(), "bb",
            SERVERS_MOD.jar(), "cc");

    @BeforeEach
    void emptyJvm() {
        JarHistory.reset();
    }

    @Test
    void aJarKeepsThePhaseItFirstLoadedIn() {
        JarHistory.preloaded(List.of(ZABRIC), HASHES);
        JarHistory.passLoaded(List.of(ZABRIC, MOD), HASHES);
        JarHistory.passLoaded(List.of(ZABRIC, MOD, SERVERS_MOD), HASHES);

        Entry zabric = new Entry("Zabric", ZABRIC.jar(), LoadPhase.PRELOAD, "aa", null);
        Entry mod = new Entry("m", MOD.jar(), LoadPhase.STARTUP, "bb", null);
        Entry serversMod = new Entry("m", SERVERS_MOD.jar(), LoadPhase.LATER, "cc", null);
        assertEquals(List.of(zabric, mod, serversMod), JarHistory.loaded());
        assertEquals(List.of(zabric, mod, serversMod), JarHistory.lastPass());

        // leaving: the main menu's mods again
        JarHistory.passLoaded(List.of(ZABRIC, MOD), HASHES);
        assertEquals(List.of(zabric, mod), JarHistory.lastPass());
        assertEquals(List.of(zabric, mod, serversMod), JarHistory.loaded());
    }

    @Test
    void theStartupPassIsTheFirstOneEvenWithoutPreloadedJars() {
        JarHistory.passLoaded(List.of(MOD), HASHES);
        assertEquals(List.of(new Entry("m", MOD.jar(), LoadPhase.STARTUP, "bb", null)), JarHistory.loaded());
    }

    @Test
    void aJarKeepsTheHashItLoadedWith() {
        JarHistory.passLoaded(List.of(MOD), HASHES);
        JarHistory.passLoaded(List.of(MOD), () -> Map.of(MOD.jar(), "changed"));
        assertEquals("bb", JarHistory.loaded().get(0).checkedSha256());
    }

    @Test
    void noPassYet() {
        JarHistory.preloaded(List.of(ZABRIC), HASHES);
        assertThrows(IllegalStateException.class, JarHistory::lastPass);
    }

    @Test
    void aJarWithoutHashIsAnError() {
        JarHistory.passLoaded(List.of(MOD), Map::of);
        assertThrows(IllegalStateException.class, JarHistory::loaded);
        assertThrows(IllegalStateException.class, JarHistory::lastPass);
    }

    @Test
    void hashesThatDoNotReadFailOnlyTheCallsThatNeedThem() throws Exception {
        JarHistory.indexer = jars -> pkg -> pkg.equals("m") ? MOD.jar() : null;
        JarHistory.passLoaded(List.of(MOD), () -> {
            throw new IllegalStateException("the loader's records have no sha256");
        });
        IllegalStateException e = assertThrows(IllegalStateException.class, JarHistory::loaded);
        assertEquals("the loader's records have no sha256", e.getMessage());
        assertThrows(IllegalStateException.class, JarHistory::lastPass);

        Entry serving = JarHistory.servingJar("m");
        assertEquals(MOD.jar(), serving.path());
        assertNull(serving.sha256());
        assertThrows(IllegalStateException.class, serving::checkedSha256);
    }

    @Test
    void packagesAreIndexedOncePerPass() throws Exception {
        List<List<Path>> indexed = new ArrayList<>();
        Map<String, Path> serving = new HashMap<>(Map.of("z", ZABRIC.jar()));
        JarHistory.indexer = jars -> {
            indexed.add(jars);
            return serving::get;
        };
        JarHistory.preloaded(List.of(ZABRIC), HASHES);
        JarHistory.passLoaded(List.of(ZABRIC, MOD), HASHES);
        assertEquals("Zabric", JarHistory.servingJar("z").modId());
        assertNull(JarHistory.servingJar("other"));
        assertEquals(List.of(List.of(ZABRIC.jar(), MOD.jar())), indexed);

        JarHistory.passLoaded(List.of(ZABRIC, SERVERS_MOD), HASHES);
        serving.put("m", SERVERS_MOD.jar());
        Entry servers = JarHistory.servingJar("m");
        assertSame(LoadPhase.LATER, servers.phase());
        assertEquals(List.of(ZABRIC.jar(), MOD.jar(), SERVERS_MOD.jar()), indexed.get(1));
        assertEquals(2, indexed.size());
    }

    @Test
    void anIndexReadAcrossAPassIsNotKept() throws Exception {
        List<List<Path>> indexed = new ArrayList<>();
        JarHistory.indexer = jars -> {
            indexed.add(jars);
            if (indexed.size() == 1) {
                // a pass on the main thread while a worker reads the jars
                JarHistory.passLoaded(List.of(MOD, SERVERS_MOD), HASHES);
            }
            return pkg -> MOD.jar();
        };
        JarHistory.passLoaded(List.of(MOD), HASHES);
        assertEquals("m", JarHistory.servingJar("m").modId());
        JarHistory.servingJar("m");
        assertEquals(List.of(List.of(MOD.jar()), List.of(MOD.jar(), SERVERS_MOD.jar())), indexed);
        JarHistory.servingJar("m");
        assertEquals(2, indexed.size());
    }

    @Test
    void aServingJarTheLoaderDidNotRecordIsAnError() {
        JarHistory.indexer = jars -> pkg -> Path.of("/elsewhere/other.jar");
        JarHistory.passLoaded(List.of(MOD), HASHES);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> JarHistory.servingJar("m"));
        assertTrue(e.getMessage().contains("did not record loading"), e.getMessage());
    }
}
