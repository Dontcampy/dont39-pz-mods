package com.pzlauncher.zabric.relaunch.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelaunchFailureTest {
    @TempDir
    Path dir;

    @Test
    void aFailureGoesToTheLogTheMarkerAndTheBoxAndIsShownOnce() throws Exception {
        Path zabric = dir.resolve("Zabric");
        List<String> boxes = new ArrayList<>();
        RelaunchFailure.record(zabric, "first", boxes::add);
        RelaunchFailure.record(zabric, "second", boxes::add);

        List<String> log = Files.readAllLines(RelaunchFailure.log(zabric));
        assertEquals(2, log.size());
        assertTrue(log.get(0).endsWith(" first") && log.get(1).endsWith(" second"), log::toString);
        assertEquals(2, boxes.size());
        assertTrue(boxes.get(1).contains("second") && boxes.get(1).contains(RelaunchFailure.log(zabric).toString()),
                boxes.get(1));

        assertEquals("second", RelaunchFailure.take(zabric));
        assertNull(RelaunchFailure.take(zabric));
    }

    @Test
    void aBoxThatFailsLeavesTheLogAndTheMarker() throws Exception {
        RelaunchFailure.record(dir, "reason", text -> {
            throw new IllegalStateException("MessageBoxW failed");
        });
        assertTrue(Files.readString(RelaunchFailure.log(dir)).contains("reason"));
        assertEquals("reason", RelaunchFailure.take(dir));
    }

    @Test
    void theBoxShowsEvenWhenNothingCanBeWritten() throws Exception {
        Path notADir = Files.writeString(dir.resolve("file"), "");
        List<String> boxes = new ArrayList<>();
        RelaunchFailure.record(notADir, "reason", boxes::add);
        assertEquals(1, boxes.size());
    }

    @Test
    void aMarkerThatCannotBeReadSaysSo() throws Exception {
        Path marker = Files.createDirectories(RelaunchFailure.marker(dir));
        String text = RelaunchFailure.take(dir);
        assertTrue(text.startsWith("a failed restart was recorded in " + marker + ", which cannot be read: "), text);
        assertFalse(Files.exists(marker));
    }
}
