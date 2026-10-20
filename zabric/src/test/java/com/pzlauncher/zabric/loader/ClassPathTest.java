package com.pzlauncher.zabric.loader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClassPathTest {
    @TempDir
    Path dir;

    @Test
    void theFirstJarOnThePathServesTheClassesBothHold() throws Exception {
        Path first = jar("first.jar", "a/b/Main.class", "a/b/sub/Other.class");
        Path second = jar("second.jar", "a/b/Main.class");
        try (URLClassLoader loader = loader(first, second)) {
            assertEquals(first.toRealPath(), ClassPath.servingJar("a.b", List.of(second, first), loader));
            // a subpackage is another package
            assertEquals(first.toRealPath(), ClassPath.servingJar("a.b.sub", List.of(first, second), loader));
        }
    }

    @Test
    void classesFromTwoJarsAreAnError() throws Exception {
        Path first = jar("first.jar", "a/b/Main.class");
        Path second = jar("second.jar", "a/b/Main.class", "a/b/OnlyHere.class");
        try (URLClassLoader loader = loader(first, second)) {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> ClassPath.servingJar("a.b", List.of(first, second), loader));
            assertTrue(e.getMessage().startsWith("the classes of a.b are served from more than one jar: "),
                    e.getMessage());
        }
    }

    @Test
    void noJarHoldingThePackageIsNull() throws Exception {
        Path jar = jar("m.jar", "a/b/Main.class", "a/bc/Main.class");
        try (URLClassLoader loader = loader(jar)) {
            assertNull(ClassPath.servingJar("a", List.of(jar), loader));
            assertNull(ClassPath.servingJar("a.b.c", List.of(jar), loader));
        }
    }

    @Test
    void aJarTheClassLoaderDoesNotSearchIsAnError() throws Exception {
        Path jar = jar("m.jar", "a/b/Main.class");
        try (URLClassLoader loader = loader()) {
            assertThrows(IllegalStateException.class, () -> ClassPath.servingJar("a.b", List.of(jar), loader));
        }
    }

    @Test
    void notAPackageName() {
        assertThrows(IllegalArgumentException.class, () -> ClassPath.servingJar("a/b", List.of(), loader()));
        assertThrows(IllegalArgumentException.class, () -> ClassPath.servingJar("", List.of(), loader()));
    }

    private static URLClassLoader loader(Path... jars) throws IOException {
        URL[] urls = new URL[jars.length];
        for (int i = 0; i < jars.length; i++) {
            urls[i] = jars[i].toUri().toURL();
        }
        return new URLClassLoader(urls, null);
    }

    private Path jar(String name, String... entries) throws IOException {
        Path jar = dir.resolve(name);
        try (OutputStream out = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(out)) {
            for (String entry : entries) {
                zip.putNextEntry(new ZipEntry(entry));
                zip.closeEntry();
            }
        }
        return jar;
    }
}
