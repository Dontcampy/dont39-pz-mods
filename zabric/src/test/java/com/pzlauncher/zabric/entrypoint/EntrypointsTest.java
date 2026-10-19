package com.pzlauncher.zabric.entrypoint;

import com.pzlauncher.zabric.entrypoint.Entrypoints.Declared;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EntrypointsTest {
    @TempDir
    Path dir;

    @Test
    void readsTheEntrypointsAndLaunchWideHooks() throws IOException {
        assertEquals(new Declared("a.Pre", "a.Main", true), Entrypoints.declared(jar(
                Lifecycle.PRE_LAUNCH, " a.Pre ", Lifecycle.MAIN, "a.Main", Lifecycle.HOOKS, Lifecycle.HOOKS_LAUNCH)));
        assertEquals(new Declared(null, "a.Main", false), Entrypoints.declared(jar(Lifecycle.MAIN, "a.Main")));
        assertEquals(Declared.NONE, Entrypoints.declared(jar()));
    }

    @Test
    void aHooksValueOtherThanLaunchIsAnError() throws IOException {
        Path jar = jar(Lifecycle.PRE_LAUNCH, "a.Pre", Lifecycle.HOOKS, "always");
        assertEquals("its manifest has Zabric-Hooks: 'always'; the only value is launch",
                assertThrows(IllegalStateException.class, () -> Entrypoints.declared(jar)).getMessage());
        Path blank = jar(Lifecycle.PRE_LAUNCH, "a.Pre", Lifecycle.HOOKS, " ");
        assertEquals("its manifest has Zabric-Hooks: ' '; the only value is launch",
                assertThrows(IllegalStateException.class, () -> Entrypoints.declared(blank)).getMessage());
    }

    @Test
    void launchWideHooksWithoutAPreLaunchEntrypointAreIgnoredWithAReason() {
        assertEquals("its manifest has Zabric-Hooks: launch but no Zabric-PreLaunch entrypoint, so Zabric does not"
                        + " keep it preloaded; ignored: its hooks run only while it is in the mod list",
                Entrypoints.launchHooksProblem(new Declared(null, "a.Main", true)));
        assertNull(Entrypoints.launchHooksProblem(new Declared("a.Pre", null, true)));
        assertNull(Entrypoints.launchHooksProblem(new Declared(null, "a.Main", false)));
    }

    // name, value, ...
    private Path jar(String... attributes) throws IOException {
        Manifest manifest = new Manifest();
        Attributes main = manifest.getMainAttributes();
        main.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        for (int i = 0; i < attributes.length; i += 2) {
            main.putValue(attributes[i], attributes[i + 1]);
        }
        Path jar = Files.createTempFile(dir, "mod", ".jar");
        try (OutputStream out = Files.newOutputStream(jar); JarOutputStream ignored = new JarOutputStream(out,
                manifest)) {
            return jar;
        }
    }
}
