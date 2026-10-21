package com.pzlauncher.zabric.mods.impl;

import com.pzlauncher.zabric.hook.weave.Weaving;
import com.pzlauncher.zabric.internal.GameSide;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LaunchOverridesTest {
    private static final String CLIENT = GameSide.CLIENT.mainClass;
    private static final String SERVER = GameSide.SERVER.mainClass;

    @TempDir
    Path dir;

    @BeforeEach
    void freshLaunch() {
        LaunchOverrides.reset();
    }

    @Test
    void premainWidensBothSidesAndTheMainMethodTakesOnlyItsOwn() throws Exception {
        Path c = modsFolder("c/mods", "C");
        Path s = modsFolder("s/mods", "S");
        OverrideRequest.write(file(GameSide.CLIENT), Map.of("111", c), List.of());
        OverrideRequest.write(file(GameSide.SERVER), Map.of("222", s), List.of());

        LaunchOverrides.read(zabricDir(), List.of());
        assertEquals(List.of(c, s), LaunchOverrides.folders(), "widening too much is harmless");
        assertEquals(Map.of(), LaunchOverrides.request(), "premain cannot tell the side");
        assertTrue(Files.exists(file(GameSide.CLIENT)));
        assertTrue(Files.exists(file(GameSide.SERVER)));

        LaunchOverrides.preLaunch(CLIENT);
        assertNull(LaunchOverrides.discarded());
        assertEquals(Map.of("111", c), LaunchOverrides.request());
        assertFalse(Files.exists(file(GameSide.CLIENT)), "a crash later in this launch does not apply it again");
        assertEquals(Map.of("222", s), OverrideRequest.read(file(GameSide.SERVER)), "the server's request stays");

        LaunchOverrides.listed("111");
        assertEquals(Map.of("111", c), LaunchOverrides.current());
    }

    @Test
    void aServerTakesOnlyTheServersRequest() throws Exception {
        Path c = modsFolder("c/mods", "C");
        Path s = modsFolder("s/mods", "S");
        OverrideRequest.write(file(GameSide.CLIENT), Map.of("111", c), List.of());
        OverrideRequest.write(file(GameSide.SERVER), Map.of("222", s), List.of());

        LaunchOverrides.read(zabricDir(), List.of());
        LaunchOverrides.preLaunch(SERVER);
        assertEquals(Map.of("222", s), LaunchOverrides.request());
        assertFalse(Files.exists(file(GameSide.SERVER)));
        assertEquals(Map.of("111", c), OverrideRequest.read(file(GameSide.CLIENT)));
    }

    @Test
    void withoutTheMainMethodNothingIsAppliedAndTheFilesStay() throws Exception {
        OverrideRequest.write(file(GameSide.CLIENT), Map.of("111", modsFolder("c/mods", "C")), List.of());
        LaunchOverrides.read(zabricDir(), List.of());
        assertEquals(Map.of(), LaunchOverrides.request());
        assertEquals(Map.of(), LaunchOverrides.current());
        assertTrue(Files.exists(file(GameSide.CLIENT)));
    }

    @Test
    void anInvalidRequestIsDiscardedWholeAtTheMainMethod() throws Exception {
        Path a = modsFolder("a/mods", "A");
        Path b = modsFolder("b/mods", "B");
        Path file = file();
        OverrideRequest.write(file, Map.of("111", a, "222", b), List.of());
        Files.delete(b.resolve("B/common/mod.info"));

        LaunchOverrides.read(zabricDir(), List.of());
        assertEquals(List.of(), LaunchOverrides.folders());
        assertTrue(Files.exists(file));
        LaunchOverrides.preLaunch(CLIENT);
        assertFalse(Files.exists(file));
        assertTrue(LaunchOverrides.discarded().startsWith("discarded the request in " + file
                + ", so nothing is applied: item 222: "), LaunchOverrides.discarded());
        assertEquals(Map.of(), LaunchOverrides.request());
        assertEquals(Map.of(), LaunchOverrides.current());
    }

    @Test
    void aRequestForZabricWrittenByHandIsDiscarded() throws Exception {
        Path ok = modsFolder("ok/mods", "Ok");
        Path zabric = modsFolder("z/mods", "Zabric");
        Path file = file();
        Files.createDirectories(file.getParent());
        // properties syntax: a backslash is escaped
        Files.writeString(file, "111=" + ok.toString().replace("\\", "\\\\") + "\n222="
                + zabric.toString().replace("\\", "\\\\") + "\n");

        LaunchOverrides.read(zabricDir(), List.of());
        LaunchOverrides.preLaunch(CLIENT);
        assertFalse(Files.exists(file));
        assertEquals("discarded the request in " + file + ", so nothing is applied: item 222: " + zabric
                + " holds the mod Zabric, which cannot be overridden", LaunchOverrides.discarded());
        assertEquals(List.of(), LaunchOverrides.folders());
    }

    @Test
    void aRequestChangedOrWrittenAfterGameStartIsDiscarded() throws Exception {
        Path a = modsFolder("a/mods", "A");
        Path b = modsFolder("b/mods", "B");
        Path file = file();
        OverrideRequest.write(file, Map.of("111", a), List.of());
        LaunchOverrides.read(zabricDir(), List.of());
        OverrideRequest.write(file, Map.of("111", b), List.of());
        LaunchOverrides.preLaunch(CLIENT);
        assertEquals("discarded the request in " + file + ", so nothing is applied: it changed between game start"
                + " and the game's main method", LaunchOverrides.discarded());
        assertEquals(Map.of(), LaunchOverrides.request());
        assertFalse(Files.exists(file));

        LaunchOverrides.reset();
        LaunchOverrides.read(zabricDir(), List.of());
        OverrideRequest.write(file, Map.of("111", b), List.of());
        LaunchOverrides.preLaunch(CLIENT);
        assertEquals("discarded the request in " + file + ", so nothing is applied: it was written after game start,"
                + " so the mods in its folders were not widened", LaunchOverrides.discarded());
        assertFalse(Files.exists(file));
    }

    @Test
    void withoutARequestTheGamesMethodIsLeftAlone() {
        int calls = ItemFoldersHook.calls();
        LaunchOverrides.read(zabricDir(), List.of());
        LaunchOverrides.preLaunch(CLIENT);
        assertNull(LaunchOverrides.discarded());
        new UntouchedHost();
        LaunchOverrides.install(Weaving::instrumentation, UntouchedHost.class.getName());
        List<String> folders = new ArrayList<>();
        new UntouchedHost().getInstalledItemModsFolders(folders);
        assertEquals(List.of("/content/111/mods"), folders);
        assertEquals(calls, ItemFoldersHook.calls(), "no advice");
    }

    @Test
    void anUnknownMainClassTakesNoRequest() throws Exception {
        LaunchOverrides.read(zabricDir(), List.of());
        LaunchOverrides.preLaunch("some.Launcher");
        assertNull(LaunchOverrides.discarded(), "no request file, so nothing to report");

        LaunchOverrides.reset();
        OverrideRequest.write(file(), Map.of("111", modsFolder("c/mods", "C")), List.of());
        LaunchOverrides.read(zabricDir(), List.of());
        LaunchOverrides.preLaunch("some.Launcher");
        assertEquals("no request read: some.Launcher is neither the client's nor the server's main class",
                LaunchOverrides.discarded());
        assertEquals(Map.of(), LaunchOverrides.request());
        assertTrue(Files.exists(file()));
    }

    @Test
    void aFailureStopsTheOverrideForTheLaunch() {
        IllegalStateException cause = new IllegalStateException("cannot hook");
        LaunchOverrides.fail(cause);
        LaunchOverrides.fail(new IllegalStateException("a second one"));
        assertTrue(LaunchOverrides.failed());
        IllegalStateException e = assertThrows(IllegalStateException.class, LaunchOverrides::current);
        assertEquals(cause, e.getCause(), "the first failure is the one reported");
    }

    private Path zabricDir() {
        return dir.resolve("cache/Zabric");
    }

    private Path file() {
        return file(GameSide.CLIENT);
    }

    private Path file(GameSide side) {
        return OverrideRequest.file(zabricDir(), side);
    }

    private Path modsFolder(String path, String modId) throws IOException {
        Path folder = dir.resolve(path);
        Path inf = folder.resolve(modId).resolve("common/mod.info");
        Files.createDirectories(inf.getParent());
        Files.writeString(inf, "id=" + modId + "\n");
        return folder;
    }
}
