package com.pzlauncher.zabric.loader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which mods of a folder ZombieBuddy loads a jar for on a client, which load at game start, and their ids. */
class ModInfoTest {
    private static final String JAVA = "javaJarFile=media/java/alpha.jar\njavaPkgName=com.example.alpha\n";

    @TempDir
    Path dir;

    private Path mods() {
        return dir.resolve("mods");
    }

    private List<Loader.JavaMod> read(String version) throws IOException {
        return ModInfo.javaMods(mods(), mod -> version);
    }

    private List<Loader.JavaMod> read() throws IOException {
        return read("42");
    }

    private void mod(String name, Map<String, String> files, List<String> jars) throws IOException {
        for (Map.Entry<String, String> file : files.entrySet()) {
            write(mods().resolve(name).resolve(file.getKey()), file.getValue().getBytes(StandardCharsets.UTF_8));
        }
        for (String jar : jars) {
            write(mods().resolve(name).resolve(jar), jar(Map.of()));
        }
    }

    private void mod(String name, Map<String, String> files) throws IOException {
        mod(name, files, List.of("42/media/java/alpha.jar"));
    }

    @Test
    void theVersionFoldersModInfoAndJar() throws Exception {
        mod("Alpha", Map.of("common/.keep", "", "42/mod.info", "name=Alpha Mod\nid=Alpha\n" + JAVA));
        Loader.JavaMod m = read().get(0);
        assertEquals(new Loader.JavaMod(mods().resolve("Alpha"), "Alpha", "Alpha Mod",
                mods().resolve("Alpha/42/media/java/alpha.jar"), "com.example.alpha", false), m);
    }

    @Test
    void commonsModInfoAndJarAndCommonsModInfoWithTheJarInTheVersionFolder() throws Exception {
        mod("Alpha", Map.of("common/mod.info", "id=Alpha\n" + JAVA, "42/.keep", ""),
                List.of("common/media/java/alpha.jar"));
        mod("Beta", Map.of("common/mod.info", "id=Beta\n" + JAVA, "42/.keep", ""));
        List<Loader.JavaMod> found = read();
        assertEquals(mods().resolve("Alpha/common/media/java/alpha.jar"), found.get(0).jar());
        assertEquals(mods().resolve("Beta/42/media/java/alpha.jar"), found.get(1).jar());
    }

    @Test
    void aNameOutsideAsciiReadsAsUtf8() throws Exception {
        mod("Alpha", Map.of("42/mod.info", "name=阿尔法 Mod\nid=Alpha\n" + JAVA));
        assertEquals("阿尔法 Mod", read().get(0).name());
    }

    @Test
    void theVersionFolderIsTheOneTheGamePicks() throws Exception {
        String newer = ("id=Alpha\n" + JAVA).replace("alpha.jar", "new.jar");
        mod("Alpha", Map.of("42/mod.info", "id=Alpha\n" + JAVA, "42.13/mod.info", newer),
                List.of("42/media/java/alpha.jar", "42.13/media/java/new.jar"));
        assertEquals(mods().resolve("Alpha/42.13/media/java/new.jar"), read("42.13").get(0).jar());
        assertEquals(mods().resolve("Alpha/42/media/java/alpha.jar"), read("42").get(0).jar());
    }

    @Test
    void modsZombieBuddyDoesNotLoadOnAClient() throws Exception {
        mod("Alpha", Map.of("42/mod.info", "id=Alpha\njavaJarFile=media/java/alpha.jar\n"));
        mod("Beta", Map.of("42/mod.info", ("id=Beta\n" + JAVA).replace("media/java/", "media/java/server/")),
                List.of("42/media/java/server/alpha.jar"));
        mod("Gamma", Map.of("42/mod.info", "id=Gamma\n" + JAVA), List.of());
        mod("Delta", Map.of("42/mod.info", "id=Delta\nname=Delta\n"));
        assertEquals(List.of(), read());
    }

    @Test
    void keysAreCaseInsensitiveAndTheFirstValidJarWins() throws Exception {
        mod("Alpha", Map.of("42/mod.info", "ID=x\nid=Alpha\nJavaJarFile=media/java/alpha.zip\n"
                + "JAVAJARFILE=media/java/alpha.jar\njavajarfile=media/java/other.jar\n"
                + "javaPkgName=com.example.alpha\njavaPkgName=com.example.other\n"));
        Loader.JavaMod m = read().get(0);
        assertEquals(mods().resolve("Alpha/42/media/java/alpha.jar"), m.jar());
        assertEquals("com.example.alpha", m.pkg());
        assertEquals("Alpha", m.id());
        assertEquals("Alpha", m.name());
    }

    @Test
    void theLastIdLineWinsAndNoIdIsNull() throws Exception {
        mod("Alpha", Map.of("42/mod.info", "id=First\n  id=Alpha  \n" + JAVA));
        mod("Beta", Map.of("42/mod.info", JAVA));
        mod("Gamma", Map.of("42/mod.info", "id=Gamma\nid=\n" + JAVA));
        List<Loader.JavaMod> found = read();
        assertEquals("Alpha", found.get(0).id());
        assertEquals(null, found.get(1).id());
        assertEquals("Beta", found.get(1).name());
        assertEquals(null, found.get(2).id());
    }

    @Test
    void preloadByModInfoOrByTheJarsManifest() throws Exception {
        mod("Alpha", Map.of("42/mod.info", "id=Alpha\njavaPreload=TRUE\n" + JAVA));
        modWithManifest("Beta", Map.of("ZB-Preload", "true"));
        modWithManifest("Gamma", Map.of("Zabric-PreLaunch", "com.example.Pre"));
        mod("Delta", Map.of("42/mod.info", "id=Delta\njavaPreload=true\njavaPreload=false\n" + JAVA),
                List.of());
        write(mods().resolve("Delta/42/media/java/alpha.jar"), jar(Map.of("Zabric-Main", "com.example.Main")));
        Map<String, Boolean> preload = new LinkedHashMap<>();
        read().forEach(m -> preload.put(m.id(), m.preload()));
        assertEquals(Map.of("Alpha", true, "Beta", true, "Gamma", true, "Delta", false), preload);
    }

    @Test
    void aJarThatDoesNotReadIsAnErrorNamingIt() throws Exception {
        mod("Alpha", Map.of("42/mod.info", "id=Alpha\n" + JAVA, "42/media/java/alpha.jar", "no jar"), List.of());
        IOException e = assertThrows(IOException.class, this::read);
        assertTrue(e.getMessage().contains("alpha.jar"), e.getMessage());
    }

    @Test
    void noModsFolderHasNoJavaMods() throws Exception {
        assertEquals(List.of(), read());
    }

    @Test
    void modIdsTakeEveryIdOfEveryModInfoReadWidely() throws Exception {
        mod("Alpha", Map.of("42/mod.info", "id=Alpha\n", "41/mod.info", " ID = Zabric \n", "common/mod.info",
                "id=Common\nid=Last\n"), List.of());
        mod("Beta", Map.of("common/.keep", ""), List.of());
        write(mods().resolve("file.txt"), new byte[0]);
        assertEquals(Map.of(mods().resolve("Alpha"), Set.of("Zabric", "Alpha", "Common", "Last")),
                ModInfo.modIds(mods()));
        assertFalse(ModInfo.modIds(mods()).containsKey(mods().resolve("Beta")));
    }

    private void modWithManifest(String name, Map<String, String> attributes) throws IOException {
        write(mods().resolve(name).resolve("42/mod.info"),
                ("id=" + name + "\n" + JAVA).getBytes(StandardCharsets.UTF_8));
        write(mods().resolve(name).resolve("42/media/java/alpha.jar"), jar(attributes));
    }

    private static void write(Path file, byte[] bytes) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
    }

    private byte[] jar(Map<String, String> attributes) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.forEach((k, v) -> manifest.getMainAttributes().putValue(k, v));
        Path tmp = Files.createTempFile(dir, "jar", ".jar");
        try (OutputStream out = Files.newOutputStream(tmp); JarOutputStream jar = new JarOutputStream(out, manifest)) {
            jar.flush();
        }
        return Files.readAllBytes(tmp);
    }
}
