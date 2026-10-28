package com.pzlauncher.zabric.loader;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/** The loader's list of classes with global Lua functions, read from the ZombieBuddy jar the game has. */
class GlobalLuaClassesTest {
    @Test
    void theInstalledLoaderListsThem() {
        assertNotNull(Loader.globalLuaClasses());
    }
}
