package com.pzlauncher.zabric.internal;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameArgsTest {
    @Test
    void aQuotedArgumentKeepsItsSpaces() {
        assertEquals(List.of("ProjectZomboid64.exe", "-cachedir=C:\\my dir\\z", "-debug"),
                GameArgs.splitWindows("ProjectZomboid64.exe \"-cachedir=C:\\my dir\\z\"  \t-debug"));
        assertEquals(List.of("-cachedir=C:\\my dir"), GameArgs.splitWindows("-cachedir=\"C:\\my dir\""));
    }

    @Test
    void backslashesAreLiteralUnlessAQuoteFollows() {
        assertEquals(List.of("C:\\a\\b\\"), GameArgs.splitWindows("C:\\a\\b\\"));
        assertEquals(List.of("a\\\"b"), GameArgs.splitWindows("a\\\\\\\"b"));
        assertEquals(List.of("a\\", "b"), GameArgs.splitWindows("\"a\\\\\" b"));
    }

    @Test
    void quotesInsideQuotesAndEmptyArguments() {
        assertEquals(List.of("a\"b", "", "c"), GameArgs.splitWindows("\"a\"\"b\" \"\" c"));
        assertEquals(List.of(), GameArgs.splitWindows("  "));
    }

    @Test
    void readsThisProcesssArguments() {
        // the test JVM's own: Gradle's worker main class is on its command line
        assertTrue(GameArgs.read().stream().anyMatch(a -> a.endsWith("GradleWorkerMain")), GameArgs.read()::toString);
    }
}
