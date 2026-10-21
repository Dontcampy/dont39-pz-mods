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
        assertEquals(List.of("pz.exe", "C:\\a\\b\\"), GameArgs.splitWindows("pz.exe C:\\a\\b\\"));
        assertEquals(List.of("pz.exe", "a\\\"b"), GameArgs.splitWindows("pz.exe a\\\\\\\"b"));
        assertEquals(List.of("pz.exe", "a\\", "b"), GameArgs.splitWindows("pz.exe \"a\\\\\" b"));
    }

    @Test
    void quotesInsideQuotesAndEmptyArguments() {
        assertEquals(List.of("pz.exe", "a\"b", "", "c"), GameArgs.splitWindows("pz.exe \"a\"\"b\" \"\" c"));
        assertEquals(List.of(), GameArgs.splitWindows("  "));
    }

    @Test
    void theProgramNameHasNoEscapes() {
        assertEquals(List.of("C:\\pz dir\\", "a\"b"), GameArgs.splitWindows("\"C:\\pz dir\\\" a\\\"b"));
        assertEquals(List.of("C:\\pz\\ab.exe", "x"), GameArgs.splitWindows("C:\\pz\\a\"\"b.exe x"));
    }

    @Test
    void readsThisProcesssArguments() {
        // the test JVM's own: Gradle's worker main class is on its command line
        assertTrue(GameArgs.read().stream().anyMatch(a -> a.endsWith("GradleWorkerMain")), GameArgs.read()::toString);
    }

    @Test
    void spansCoverEachArgumentsTextWithoutTheSpaceAround() {
        String line = "\"C:\\Program Files\\pz.exe\"  -a\t\"b c\" d\\\"e ";
        List<GameArgs.Span> spans = GameArgs.spansWindows(line);
        assertEquals(List.of("C:\\Program Files\\pz.exe", "-a", "b c", "d\"e"),
                spans.stream().map(GameArgs.Span::value).toList());
        assertEquals(List.of("\"C:\\Program Files\\pz.exe\"", "-a", "\"b c\"", "d\\\"e"),
                spans.stream().map(s -> line.substring(s.start(), s.end())).toList());
    }
}
