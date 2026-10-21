package com.dont39.pz.loadingprogress.report;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportsTest {
    @TempDir
    Path dir;

    private static final LocalDateTime START = LocalDateTime.of(2026, 10, 20, 9, 5, 0);

    private List<String> files() throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(file -> file.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void aLoadIsASummaryAndACsv() throws IOException {
        Path summary = Reports.write(dir, START, "launch", "summary\n", "a,b\r\n");
        assertEquals(dir.resolve("2026-10-20_09-05-00_launch.txt"), summary);
        assertEquals("summary\n", Files.readString(summary));
        byte[] csv = Files.readAllBytes(dir.resolve("2026-10-20_09-05-00_launch.csv"));
        assertArrayEquals(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'a', ',', 'b', '\r', '\n'}, csv);
    }

    @Test
    void aClashGetsACounter() throws IOException {
        Reports.write(dir, START, "launch", "1", "1");
        Path second = Reports.write(dir, START, "launch", "2", "2");
        assertEquals("2026-10-20_09-05-00_launch_2.txt", second.getFileName().toString());
        assertEquals("1", Files.readString(dir.resolve("2026-10-20_09-05-00_launch.txt")));
    }

    @Test
    void theNewestTenLoadsAreKept() throws IOException {
        Files.writeString(dir.resolve("notes.txt"), "mine", StandardCharsets.UTF_8);
        for (int i = 0; i < 12; i++) {
            Reports.write(dir, START.plusMinutes(i), i % 2 == 0 ? "start" : "reload", "s", "c");
        }
        List<String> files = files();
        assertEquals(2 * Reports.KEEP + 1, files.size(), files.toString());
        assertTrue(files.contains("notes.txt"), "only report files are deleted");
        assertTrue(files.contains("2026-10-20_09-07-00_start.txt"), files.toString());
        assertTrue(files.stream().noneMatch(file -> file.startsWith("2026-10-20_09-05")
                || file.startsWith("2026-10-20_09-06")), "the two oldest went: " + files);
    }
}
