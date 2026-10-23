package com.dont39.pz.loadingprogress.progress;

import com.dont39.pz.loadingprogress.settings.SwitchesPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsTest {
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
    void aMissingFileIsWrittenWithTheDefaults() throws IOException {
        Path file = dir.resolve("Lua").resolve(Settings.FILE);
        assertEquals(Settings.DEFAULTS, load(file));
        assertTrue(problems.isEmpty(), problems.toString());
        String text = Files.readString(file);
        assertTrue(text.contains("# display:") && text.contains("# report:"), "a comment per key: " + text);
        assertEquals(Settings.DEFAULTS, load(file), "and reads back");
        assertTrue(problems.isEmpty(), problems.toString());
    }

    @Test
    void valuesAreRead() throws IOException {
        Path file = file("# comment\n\ndisplay = false\r\n  report=TRUE  \n");
        assertEquals(new Settings(false, true), load(file));
        assertTrue(problems.isEmpty(), problems.toString());
    }

    @Test
    void aByteOrderMarkIsSkipped() throws IOException {
        assertEquals(new Settings(true, false), load(file("﻿# comment\ndisplay = true\nreport = false\n")));
        assertTrue(problems.isEmpty(), problems.toString());
    }

    @Test
    void theWrittenTextReadsBack() {
        Settings settings = new Settings(false, true);
        assertEquals(settings, Settings.parse(Settings.text(settings).lines().toList()));
    }

    @Test
    void anotherValueIsReportedAndTheDefaultsApply() throws IOException {
        String text = "display = no\nreport = false\n";
        Path file = file(text);
        assertEquals(Settings.DEFAULTS, load(file));
        assertEquals(1, problems.size());
        String problem = problems.get(0);
        assertTrue(problem.contains(file.toString()) && problem.contains("display"), problem);
        assertEquals(text, Files.readString(file), "left as it is");
    }

    @Test
    void anUnknownKeyIsReported() throws IOException {
        assertEquals(Settings.DEFAULTS, load(file("display = true\nreport = true\nbar = false\n")));
        assertTrue(problems.get(0).contains("\"bar\""), problems.toString());
    }

    @Test
    void aFileWithoutAKeyKeepsItsValuesAndGetsTheKey() throws IOException {
        Path file = file("# comment\ndisplay = false\n");
        Settings settings = load(file);
        assertEquals(new Settings(false, true), settings, "a missing key takes its default");
        assertTrue(problems.isEmpty(), problems.toString());
        assertEquals(Settings.text(settings), Files.readString(file), "rewritten with every key");
        assertEquals(settings, load(file), "and reads back");
        assertTrue(problems.isEmpty(), problems.toString());
    }

    @Test
    void aFileWithEveryKeyIsLeftAsItIs() throws IOException {
        String text = "report = false\ndisplay = true\n";
        Path file = file(text);
        assertEquals(new Settings(true, false), load(file));
        assertEquals(text, Files.readString(file));
    }

    @Test
    void theTextIsAsBefore() {
        assertEquals("""
                # Loading Progress, read once at each launch of the game; also on the mod options page.
                # display: show the progress bar and the log of what loads (true or false)
                display = false
                # report: after each load, write a timing report to Lua/Dont39LoadingProgress/ in this folder \
                (true or false)
                report = true
                """, Settings.text(new Settings(false, true)));
    }

    @Test
    void theOptionsPageListsTheSwitches() throws IOException {
        SwitchesPage.check(Settings.SWITCHES, Path.of("src/main/lua/client/Dont39LoadingProgress_Options.lua"),
                Path.of("src/main/lua/shared/Translate"));
    }

    @Test
    void aKeySetTwiceIsReported() throws IOException {
        assertEquals(Settings.DEFAULTS, load(file("display = false\nreport = true\ndisplay = false\n")));
        assertTrue(problems.get(0).contains("key display is set twice"), problems.toString());
    }

    @Test
    void aLineWithoutAValueIsReported() throws IOException {
        assertEquals(Settings.DEFAULTS, load(file("display\nreport = true\n")));
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
