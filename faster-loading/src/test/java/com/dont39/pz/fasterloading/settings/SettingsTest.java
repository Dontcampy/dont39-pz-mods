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
        assertTrue(text.contains("# stripComments:"), "a comment per key: " + text);
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
        assertEquals(OFF, load(file("﻿# comment\nstripComments = false\n")));
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
    void aMissingKeyIsReported() throws IOException {
        assertEquals(Settings.DEFAULTS, load(file("# nothing\n")));
        assertTrue(problems.get(0).contains("key stripComments is missing"), problems.toString());
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
