package com.pzlauncher.zabric.loader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The order Zabric keeps ZombieBuddy's preload entries in, and when it rewrites the file for it. */
class PreloadConfigTest {
    @TempDir
    Path dir;

    private static PreloadConfig.Order order(List<String> stored, Map<String, Set<String>> requires) {
        return PreloadConfig.order(stored, requires);
    }

    @Test
    void zabricComesFirst() {
        PreloadConfig.Order o = order(List.of("A", "Zabric", "B"), Map.of());
        assertEquals(List.of("Zabric", "A", "B"), o.ids());
        assertTrue(o.cycles().isEmpty());
    }

    @Test
    void eachEntryFollowsTheEntriesItRequires() {
        PreloadConfig.Order o = order(List.of("Zabric", "Uses", "Other", "ZabricKotlin"),
                Map.of("Uses", Set.of("ZombieBuddy", "Zabric", "ZabricKotlin"), "ZabricKotlin", Set.of("Zabric")));
        assertEquals(List.of("Zabric", "Other", "ZabricKotlin", "Uses"), o.ids());
    }

    @Test
    void requirementsThroughAChain() {
        PreloadConfig.Order o = order(List.of("C", "B", "A"), Map.of("C", Set.of("B"), "B", Set.of("A")));
        assertEquals(List.of("A", "B", "C"), o.ids());
    }

    @Test
    void storedOrderOtherwise() {
        List<String> stored = List.of("Zabric", "D", "C", "B", "A");
        PreloadConfig.Order o = order(stored, Map.of("C", Set.of("Zabric", "Missing"), "C2", Set.of("D")));
        assertEquals(stored, o.ids());
    }

    @Test
    void entriesThatRequireEachOtherKeepTheirStoredOrder() {
        PreloadConfig.Order o = order(List.of("Zabric", "Late", "C", "A", "B", "Free"),
                Map.of("Late", Set.of("A"), "A", Set.of("B"), "B", Set.of("C"), "C", Set.of("A")));
        assertEquals(List.of("Zabric", "Free", "C", "A", "B", "Late"), o.ids());
        assertEquals(List.of(List.of("C", "A", "B")), o.cycles());
    }

    @Test
    void aCycleWaitsForWhatItRequires() {
        PreloadConfig.Order o = order(List.of("A", "B", "Base"),
                Map.of("A", Set.of("B", "Base"), "B", Set.of("A")));
        assertEquals(List.of("Base", "A", "B"), o.ids());
        assertEquals(List.of(List.of("A", "B")), o.cycles());
    }

    @Test
    void anOrderedListStaysAsItIs() {
        Map<String, Set<String>> requires = Map.of("Late", Set.of("A"), "A", Set.of("B"), "B", Set.of("C"),
                "C", Set.of("A"), "Uses", Set.of("Zabric", "ZabricKotlin"), "ZabricKotlin", Set.of("Zabric"));
        List<String> once = order(List.of("Uses", "Late", "C", "Zabric", "A", "B", "ZabricKotlin", "Free"), requires)
                .ids();
        assertEquals(once, order(once, requires).ids());
    }

    @Test
    void aSelfRequireIsNoCycle() {
        PreloadConfig.Order o = order(List.of("A", "B"), Map.of("A", Set.of("A")));
        assertEquals(List.of("A", "B"), o.ids());
        assertTrue(o.cycles().isEmpty());
    }

    @Test
    void rewritesTheFileWhenTheStoredOrderIsWrong() throws IOException {
        Path zabric = mod("Zabric", "\\ZombieBuddy");
        Path kotlin = mod("ZabricKotlin", "\\ZombieBuddy,\\Zabric");
        Path config = dir.resolve("config.json");
        writeConfig(config, List.of("ZabricKotlin", "Zabric"));

        Loader.PreloadEntry entry = PreloadConfig.keep(config, "Zabric", zabric, null);
        assertTrue(entry.wasCurrent());
        assertEquals(List.of("Zabric", "ZabricKotlin"), entry.reordered());
        assertEquals(List.of("Zabric", "ZabricKotlin"), storedOrder(config));

        entry = PreloadConfig.keep(config, "ZabricKotlin", kotlin, null);
        assertTrue(entry.wasCurrent());
        assertTrue(entry.reordered().isEmpty());
    }

    @Test
    void leavesTheFileAloneWhenNothingChanges() throws IOException {
        Path zabric = mod("Zabric", "\\ZombieBuddy");
        mod("ZabricKotlin", "\\ZombieBuddy,\\Zabric");
        Path config = dir.resolve("config.json");
        writeConfig(config, List.of("Zabric", "ZabricKotlin"));
        String before = Files.readString(config);

        Loader.PreloadEntry entry = PreloadConfig.keep(config, "Zabric", zabric, null);
        assertTrue(entry.wasCurrent());
        assertTrue(entry.reordered().isEmpty());
        assertEquals(before, Files.readString(config));
    }

    @Test
    void aNewEntryGoesAfterWhatItRequires() throws IOException {
        mod("Zabric", "\\ZombieBuddy");
        Path kotlin = mod("ZabricKotlin", "\\ZombieBuddy,\\Zabric");
        mod("Uses", "\\ZombieBuddy,\\Zabric,\\ZabricKotlin");
        mod("Other", "\\ZombieBuddy");
        Path config = dir.resolve("config.json");
        writeConfig(config, List.of("Zabric", "Uses", "Other"));

        Loader.PreloadEntry entry = PreloadConfig.keep(config, "ZabricKotlin", kotlin, null);
        assertFalse(entry.wasCurrent());
        assertEquals(List.of("Zabric", "Other", "ZabricKotlin", "Uses"), storedOrder(config));
    }

    @Test
    void readsAModInfoThatIsNotUtf8() throws IOException {
        Path zabric = mod("Zabric", "\\ZombieBuddy");
        Path other = mod("Other", "\\ZombieBuddy,\\Zabric");
        byte[] latin1 = "name=Café\n".getBytes(StandardCharsets.ISO_8859_1);
        byte[] rest = Files.readAllBytes(other.resolve("mod.info"));
        byte[] info = new byte[latin1.length + rest.length];
        System.arraycopy(latin1, 0, info, 0, latin1.length);
        System.arraycopy(rest, 0, info, latin1.length, rest.length);
        Files.write(other.resolve("mod.info"), info);
        Path config = dir.resolve("config.json");
        writeConfig(config, List.of("Other", "Zabric"));

        PreloadConfig.keep(config, "Zabric", zabric, null);
        assertEquals(List.of("Zabric", "Other"), storedOrder(config));
    }

    private Path mod(String id, String require) throws IOException {
        Path versionDir = dir.resolve("mods").resolve(id).resolve("42");
        Files.createDirectories(versionDir.resolve("media/java"));
        Files.writeString(versionDir.resolve("mod.info"), "id=" + id + "\nrequire=" + require
                + "\njavaJarFile=media/java/" + id + ".jar\njavaPkgName=com.example." + id.toLowerCase()
                + "\njavaPreload=true\n", StandardCharsets.UTF_8);
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("ZB-Preload", "true");
        try (OutputStream out = Files.newOutputStream(versionDir.resolve("media/java/" + id + ".jar"));
             JarOutputStream jar = new JarOutputStream(out, manifest)) {
            jar.flush();
        }
        return versionDir;
    }

    // the test classpath has no JSON library; Zabric's own comes with the game
    private void writeConfig(Path config, List<String> ids) throws IOException {
        List<String> entries = new ArrayList<>();
        for (String id : ids) {
            Path versionDir = dir.resolve("mods").resolve(id).resolve("42");
            entries.add("\"" + id + "\": {\"infPath\": " + quoted(versionDir.resolve("mod.info"))
                    + ", \"jarPath\": " + quoted(versionDir.resolve("media/java/" + id + ".jar")) + "}");
        }
        Files.writeString(config, "{\"preload_mods\": {" + String.join(", ", entries) + "}}");
    }

    private static String quoted(Path path) {
        return "\"" + path.toString().replace("\\", "\\\\") + "\"";
    }

    // the ids of the objects in preload_mods, in file order: each entry is the only kind of object in it
    private static List<String> storedOrder(Path config) throws IOException {
        Matcher m = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\\{").matcher(Files.readString(config));
        List<String> ids = new ArrayList<>();
        while (m.find()) {
            if (!m.group(1).equals("preload_mods")) {
                ids.add(m.group(1));
            }
        }
        return ids;
    }
}
