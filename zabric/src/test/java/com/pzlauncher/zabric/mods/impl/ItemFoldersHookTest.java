package com.pzlauncher.zabric.mods.impl;

import com.pzlauncher.zabric.hook.weave.Weaving;
import com.pzlauncher.zabric.mods.impl.weave.LaterPatch;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Weaves the hook into a stand-in that, like the game's class, is loaded before the hook is installed. */
class ItemFoldersHookTest {
    private static final Path CONTENT = Path.of("/steam/workshop/content/108600").toAbsolutePath();
    private static final Path OVERRIDE = Path.of("/cache/tree/111/1700000000/111/mods").toAbsolutePath();
    private static final String STAGED = Path.of("/cache/Workshop/111/mods").toAbsolutePath().toString();

    private static boolean installed;

    /** A proxy's class has no class file to describe it from. */
    public interface Probe {
    }

    @BeforeAll
    static synchronized void install() {
        if (!installed) {
            new FolderListHost(List.of());
            Object proxy = Proxy.newProxyInstance(Probe.class.getClassLoader(), new Class<?>[]{Probe.class},
                    (self, method, args) -> null);
            assertTrue(Proxy.isProxyClass(proxy.getClass()));
            LaunchOverrides.reset();
            ItemFoldersHook.install(Weaving.instrumentation(), FolderListHost.class.getName());
            assertFalse(LaunchOverrides.failed(), "classes other than the target do not fail the hook");
            installed = true;
        }
    }

    @BeforeEach
    void launchWithTheRequest() {
        LaunchOverrides.reset();
        ItemFoldersHook.activate(Map.of("111", OVERRIDE));
    }

    @Test
    void beforeTheSideIsKnownNothingIsReplaced() {
        ItemFoldersHook.activate(Map.of());
        List<String> folders = list();
        assertEquals(STAGED, folders.get(0));
        assertEquals(item("111"), folders.get(1));
    }

    @Test
    void theRequestedItemsFolderTakesItsPlace() {
        assertEquals(expected(), list());
    }

    @Test
    void aLaterRetransformationKeepsTheHookAndWrapsIt() {
        Weaving.weave("com.pzlauncher.zabric.mods.impl.weave");
        assertEquals(expected(), list());
        assertEquals(expected(), LaterPatch.seen, "the later patch sees the list after the override");
    }

    @Test
    void afterAFailureTheGamesListStays() {
        LaunchOverrides.fail(new IllegalStateException("test"));
        List<String> folders = list();
        assertEquals(STAGED, folders.get(0));
        assertEquals(item("111"), folders.get(1));
        assertThrows(IllegalStateException.class, LaunchOverrides::current);
    }

    // an earlier root's entry first: only what this call adds is the item's
    private static List<String> list() {
        List<String> folders = new ArrayList<>(List.of(STAGED));
        new FolderListHost(List.of(item("111"), item("222"), CONTENT.resolve("111/other").toString()))
                .getInstalledItemModsFolders(folders);
        return folders;
    }

    private static List<String> expected() {
        return List.of(STAGED, OVERRIDE.toString(), item("222"), CONTENT.resolve("111/other").toString());
    }

    private static String item(String id) {
        // as the game adds it: new File(itemFolder + separator + "mods").getAbsolutePath()
        return CONTENT.resolve(id) + File.separator + "mods";
    }
}
