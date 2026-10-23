package com.dont39.pz.fasterloading.settings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsTest {
    private static final Settings OFF = new Settings(EnumSet.noneOf(Fix.class));

    @TempDir
    Path dir;

    private final List<String> problems = new ArrayList<>();

    private Settings load(Path file) {
        return Settings.load(file, problems::add);
    }

    private Path file(String text) throws IOException {
        Path file = dir.resolve(Settings.FILE);
        Files.writeString(file, text, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void everyFixIsOnByDefault() {
        for (Fix fix : Fix.values()) {
            assertTrue(Settings.DEFAULTS.on(fix), fix.key());
        }
    }

    @Test
    void aMissingFileIsWrittenWithTheDefaults() throws IOException {
        Path file = dir.resolve("Lua").resolve(Settings.FILE);
        assertEquals(Settings.DEFAULTS, load(file));
        assertTrue(problems.isEmpty(), problems.toString());
        String text = Files.readString(file);
        for (Fix fix : Fix.values()) {
            assertTrue(text.contains("# " + fix.key() + ":"), "a comment per key: " + text);
        }
        assertEquals(Settings.DEFAULTS, load(file), "and reads back");
        assertTrue(problems.isEmpty(), problems.toString());
    }

    @Test
    void valuesAreRead() throws IOException {
        Settings settings = load(file("# comment\n\n  stripComments=FALSE  \r\n"));
        assertFalse(settings.on(Fix.STRIP_COMMENTS));
        assertTrue(problems.isEmpty(), problems.toString());
    }

    @Test
    void aByteOrderMarkIsSkipped() throws IOException {
        assertEquals(OFF, load(file("﻿# comment\nstripComments = false\ntextureCompression = false\n"
                + "textureBc7 = false\ndepthMapOrder = false\nluaFolderWalk = false\n")));
        assertTrue(problems.isEmpty(), problems.toString());
    }

    @Test
    void theWrittenTextReadsBack() {
        assertEquals(OFF, Settings.parse(Settings.text(OFF).lines().toList()));
        assertEquals(Settings.DEFAULTS, Settings.parse(Settings.text(Settings.DEFAULTS).lines().toList()));
    }

    @Test
    void anotherValueIsReportedAndTheDefaultsApply() throws IOException {
        String text = "stripComments = no\n";
        Path file = file(text);
        assertEquals(Settings.DEFAULTS, load(file));
        assertEquals(1, problems.size());
        String problem = problems.get(0);
        assertTrue(problem.contains(file.toString()) && problem.contains("stripComments"), problem);
        assertEquals(text, Files.readString(file), "left as it is");
    }

    @Test
    void anUnknownKeyIsReported() throws IOException {
        assertEquals(Settings.DEFAULTS, load(file("stripComments = true\nstripcomments = false\n")));
        assertTrue(problems.get(0).contains("\"stripcomments\""), problems.toString());
    }

    @Test
    void aFileFromAnOlderVersionKeepsItsValuesAndGetsTheNewKeys() throws IOException {
        Path file = file("# comment\nstripComments = false\n");
        Settings settings = load(file);
        assertFalse(settings.on(Fix.STRIP_COMMENTS));
        assertTrue(settings.on(Fix.TEXTURE_COMPRESSION), "a missing key takes its default");
        assertTrue(problems.isEmpty(), problems.toString());
        assertEquals(Settings.text(settings), Files.readString(file), "rewritten with every key");
        assertEquals(settings, load(file), "and reads back");
        assertTrue(problems.isEmpty(), problems.toString());
    }

    @Test
    void aFileWithEveryKeyIsLeftAsItIs() throws IOException {
        String text = "stripComments = false\ntextureCompression = true\ntextureBc7 = false\n"
                + "depthMapOrder = false\nluaFolderWalk = false\n";
        Path file = file(text);
        load(file);
        assertEquals(text, Files.readString(file));
    }

    @Test
    void aFileOfVersion020GetsTheDepthMapOrderOn() throws IOException {
        Path file = file("stripComments = false\ntextureCompression = false\n");
        assertEquals(new Settings(EnumSet.of(Fix.TEXTURE_BC7, Fix.DEPTH_MAP_ORDER, Fix.LUA_FOLDER_WALK)), load(file));
        assertTrue(problems.isEmpty(), problems.toString());
        String text = Files.readString(file);
        assertTrue(text.contains("\ndepthMapOrder = true\n") && text.contains("\ntextureCompression = false\n"), text);
    }

    @Test
    void aFileOfVersion030GetsTheLuaFolderWalkOn() throws IOException {
        Path file = file("stripComments = false\ntextureCompression = false\ndepthMapOrder = false\n");
        assertEquals(new Settings(EnumSet.of(Fix.TEXTURE_BC7, Fix.LUA_FOLDER_WALK)), load(file));
        assertTrue(problems.isEmpty(), problems.toString());
        String text = Files.readString(file);
        assertTrue(text.contains("\nluaFolderWalk = true\n") && text.contains("\ndepthMapOrder = false\n"), text);
    }

    @Test
    void aFileOfVersion040GetsTextureBc7On() throws IOException {
        Path file = file("stripComments = false\ntextureCompression = true\ndepthMapOrder = false\n"
                + "luaFolderWalk = false\n");
        assertEquals(new Settings(EnumSet.of(Fix.TEXTURE_COMPRESSION, Fix.TEXTURE_BC7)), load(file));
        assertTrue(problems.isEmpty(), problems.toString());
        String text = Files.readString(file);
        assertTrue(text.contains("\ntextureBc7 = true\n") && text.contains("\nluaFolderWalk = false\n"), text);
    }

    @Test
    void aKeySetTwiceIsReported() throws IOException {
        assertEquals(Settings.DEFAULTS, load(file("stripComments = false\nstripComments = false\n")));
        assertTrue(problems.get(0).contains("key stripComments is set twice"), problems.toString());
    }

    @Test
    void aLineWithoutAValueIsReported() throws IOException {
        assertEquals(Settings.DEFAULTS, load(file("stripComments\n")));
        assertTrue(problems.get(0).contains("line 1"), problems.toString());
    }

    @Test
    void anUnreadableFileIsReported() throws IOException {
        // a folder where the file should be
        Path file = Files.createDirectories(dir.resolve(Settings.FILE));
        assertEquals(Settings.DEFAULTS, load(file));
        assertEquals(1, problems.size(), problems.toString());
    }
}
