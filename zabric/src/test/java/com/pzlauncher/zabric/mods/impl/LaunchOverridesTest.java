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
    // as the game's launcher records them (console.txt)
    private static final String CLIENT = "zombie.gameStates.MainScreenState";
    private static final String SERVER = "zombie.network.GameServer -servername test";

    @TempDir
    Path dir;

    @BeforeEach
    void freshLaunch() {
        LaunchOverrides.reset();
    }

    @Test
    void aValidRequestIsReadOnceAndAppliesToTheItemsTheGameLists() throws Exception {
        Path a = modsFolder("a/mods", "A");
        Path b = modsFolder("b/mods", "B");
        Path file = file();
        OverrideRequest.write(file, Map.of("111", a, "222", b), List.of());

        LaunchOverrides.read(CLIENT, zabricDir(), List.of());
        assertFalse(Files.exists(file), "a crash later in this launch does not apply it again");
        assertNull(LaunchOverrides.discarded());
        assertEquals(List.of(a, b), LaunchOverrides.folders());

        LaunchOverrides.listed("111");
        assertEquals(Map.of("111", a), LaunchOverrides.current(), "222 is not installed");
    }

    @Test
    void anInvalidRequestIsDiscardedWhole() throws Exception {
        Path a = modsFolder("a/mods", "A");
        Path b = modsFolder("b/mods", "B");
        Path file = file();
        OverrideRequest.write(file, Map.of("111", a, "222", b), List.of());
        Files.delete(b.resolve("B/common/mod.info"));

        LaunchOverrides.read(CLIENT, zabricDir(), List.of());
        assertFalse(Files.exists(file));
        assertTrue(LaunchOverrides.discarded().startsWith("discarded the request in " + file
                + ", so nothing is applied: item 222: "), LaunchOverrides.discarded());
        assertEquals(Map.of(), LaunchOverrides.request());
        assertEquals(List.of(), LaunchOverrides.folders());
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

        LaunchOverrides.read(CLIENT, zabricDir(), List.of());
        assertFalse(Files.exists(file));
        assertEquals("discarded the request in " + file + ", so nothing is applied: item 222: " + zabric
                + " holds the mod Zabric, which cannot be overridden", LaunchOverrides.discarded());
        assertEquals(List.of(), LaunchOverrides.folders());
    }

    @Test
    void eachSideReadsOnlyItsOwnRequest() throws Exception {
        Map<String, Path> clients = Map.of("111", modsFolder("c/mods", "C"));
        Map<String, Path> servers = Map.of("222", modsFolder("s/mods", "S"));
        OverrideRequest.write(file(GameSide.CLIENT), clients, List.of());
        OverrideRequest.write(file(GameSide.SERVER), servers, List.of());

        LaunchOverrides.read(SERVER, zabricDir(), List.of());
        assertEquals(servers, LaunchOverrides.request());
        assertFalse(Files.exists(file(GameSide.SERVER)));
        assertEquals(clients, OverrideRequest.read(file(GameSide.CLIENT)), "a server leaves the client's request");

        OverrideRequest.write(file(GameSide.SERVER), servers, List.of());
        LaunchOverrides.reset();
        LaunchOverrides.read(CLIENT, zabricDir(), List.of());
        assertEquals(clients, LaunchOverrides.request());
        assertFalse(Files.exists(file(GameSide.CLIENT)));
        assertEquals(servers, OverrideRequest.read(file(GameSide.SERVER)), "a client leaves the server's request");
    }

    @Test
    void anUnknownMainClassReadsNoRequest() throws Exception {
        LaunchOverrides.read("some.Launcher -cachedir=x", zabricDir(), List.of());
        assertNull(LaunchOverrides.discarded(), "no request file, so nothing to report");

        OverrideRequest.write(file(GameSide.CLIENT), Map.of("111", modsFolder("c/mods", "C")), List.of());
        LaunchOverrides.read("some.Launcher -cachedir=x", zabricDir(), List.of());
        assertEquals(Map.of(), LaunchOverrides.request());
        assertEquals("no request read: cannot tell a client from a dedicated server by the main class of"
                + " 'some.Launcher -cachedir=x' (expected zombie.gameStates.MainScreenState or"
                + " zombie.network.GameServer)", LaunchOverrides.discarded());
        assertTrue(Files.exists(file(GameSide.CLIENT)));
    }

    @Test
    void withoutARequestTheGamesMethodIsLeftAlone() {
        int calls = ItemFoldersHook.calls();
        LaunchOverrides.read(CLIENT, zabricDir(), List.of());
        assertNull(LaunchOverrides.discarded());
        new UntouchedHost();
        LaunchOverrides.install(Weaving::instrumentation, UntouchedHost.class.getName());
        List<String> folders = new ArrayList<>();
        new UntouchedHost().getInstalledItemModsFolders(folders);
        assertEquals(List.of("/content/111/mods"), folders);
        assertEquals(calls, ItemFoldersHook.calls(), "no advice");
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
