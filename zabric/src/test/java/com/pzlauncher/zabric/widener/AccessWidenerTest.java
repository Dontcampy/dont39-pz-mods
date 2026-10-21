package com.pzlauncher.zabric.widener;

import com.pzlauncher.zabric.classtweaker.ClassTweaker;
import com.pzlauncher.zabric.widener.AccessWidener.Outcome;
import com.pzlauncher.zabric.widener.AccessWidener.Scan;
import com.pzlauncher.zabric.widener.WidenerScan.Found;
import com.pzlauncher.zabric.widener.WidenerScan.Listed;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccessWidenerTest {
    private static final String LIST = ClassTweaker.HEADER + "\naccessible class java/lang/String\n";
    private static final String NAME = AccessWidener.LIST_DIR + "m" + WidenerScan.LIST_SUFFIX;

    @Test
    void aListThatChangedWhileTheGameRanAsksForARestart(@TempDir Path dir) throws Exception {
        Path jar = jar(dir.resolve("mods/m/media/java"), LIST);
        Outcome outcome = check(scan(dir.resolve("mods"), Map.of(), Map.of()), jar);
        assertEquals("the list in " + jar + " was not there or was different when the game started (the mod was"
                + " installed or updated while the game ran); restart the game once", outcome.why());
        assertTrue(outcome.restart());
    }

    @Test
    void otherProblemsDoNotAskForARestart(@TempDir Path dir) throws Exception {
        Path mods = dir.resolve("mods");
        Path jar = jar(mods.resolve("m/media/java"), LIST);
        Outcome outside = check(scan(dir.resolve("elsewhere"), Map.of(), Map.of()), jar);
        assertTrue(outside.why().startsWith(jar + " is outside the folders Zabric reads at game start"), outside.why());
        assertFalse(outside.restart());

        Path unreadable = WidenerScan.real(mods.resolve("m"));
        Outcome notRead = check(scan(mods, Map.of(), Map.of(unreadable, "denied")), jar);
        assertEquals("Zabric could not read " + unreadable + " at game start (denied), so the list in " + jar
                + " was not applied", notRead.why());
        assertFalse(notRead.restart());

        Path bare = jar(dir.resolve("bare"), null);
        Outcome none = check(scan(dir, Map.of(), Map.of()), bare);
        assertEquals(bare + " has no " + NAME, none.why());
        assertFalse(none.restart());
    }

    @Test
    void aListFoundAtGameStartIsInEffect(@TempDir Path dir) throws Exception {
        Path jar = jar(dir.resolve("mods/m/media/java"), LIST);
        byte[] bytes = LIST.getBytes(StandardCharsets.UTF_8);
        Listed listed = new Listed("m", jar + "!/" + NAME, ClassTweaker.parse("list", LIST.lines().toList()), null);
        Outcome outcome = check(scan(dir.resolve("mods"), Map.of("m", Map.of(WidenerScan.sha256(bytes), listed)),
                Map.of()), jar);
        assertNull(outcome.why());
        assertEquals(1, outcome.directives());
        assertFalse(outcome.restart());
    }

    @Test
    void aListInAnOverrideFolderOrARegisteredRootIsInEffect(@TempDir Path dir) throws Exception {
        Path extra = dir.resolve("tree/111/mods");
        Path jar = jar(extra.resolve("m/media/java"), LIST);
        List<ModFolders.Root> roots = ModFolders.roots(List.of(dir.resolve("content")), dir.resolve("cache"),
                List.of(extra));
        assertTrue(roots.contains(new ModFolders.Root(extra.toAbsolutePath().normalize(), null)), roots.toString());

        Found found = WidenerScan.run(roots);
        assertTrue(found.modsFolders().contains(WidenerScan.real(extra)));
        assertFalse(found.modsFolders().contains(WidenerScan.real(dir.resolve("cache/mods"))), "does not exist");
        Outcome outcome = check(new Scan(found, 1), jar);
        assertNull(outcome.why());
        assertEquals(1, outcome.directives());
    }

    @Test
    void aWorkshopItemsModsFolderCountsAsScanned(@TempDir Path dir) throws Exception {
        Path item = Files.createDirectories(dir.resolve("content/111/mods/m"));
        Found found = WidenerScan.run(ModFolders.roots(List.of(dir.resolve("content")), dir.resolve("cache"),
                List.of()));
        assertTrue(found.modsFolders().contains(WidenerScan.real(item.getParent())));
        assertFalse(found.modsFolders().contains(WidenerScan.real(dir.resolve("content"))));
    }

    private static Scan scan(Path root, Map<String, Map<String, Listed>> lists, Map<Path, String> unreadable) {
        return new Scan(new Found(List.of(WidenerScan.real(root)), lists, unreadable, Set.of()), 0);
    }

    // asks from a class loaded out of the jar, as a mod does
    private static Outcome check(Scan scan, Path jar) throws Exception {
        try (URLClassLoader loader = new URLClassLoader(new URL[]{jar.toUri().toURL()}, null)) {
            return AccessWidener.check(scan, "m", Class.forName(WidenerHost.class.getName(), false, loader));
        }
    }

    private static Path jar(Path dir, String list) throws IOException {
        Files.createDirectories(dir);
        Path jar = dir.resolve("m.jar");
        String host = WidenerHost.class.getName().replace('.', '/') + ".class";
        try (OutputStream out = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(out);
             InputStream in = WidenerHost.class.getClassLoader().getResourceAsStream(host)) {
            zip.putNextEntry(new ZipEntry(host));
            in.transferTo(zip);
            zip.closeEntry();
            if (list != null) {
                zip.putNextEntry(new ZipEntry(NAME));
                zip.write(list.getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return jar;
    }
}
