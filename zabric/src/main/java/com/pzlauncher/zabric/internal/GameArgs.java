package com.pzlauncher.zabric.internal;

import java.util.ArrayList;
import java.util.List;

/**
 * This process's arguments after the program, JVM options included, for code that runs before the game's {@code main}
 * has them. Not {@code sun.java.command}: the game's native launcher starts the JVM through JNI and leaves it unset.
 */
public final class GameArgs {
    private GameArgs() {
    }

    public static List<String> read() {
        if (System.getProperty("os.name").startsWith("Win")) {
            // ProcessHandle has no arguments on Windows
            List<String> args = splitWindows(WindowsCommandLine.read());
            return args.subList(Math.min(1, args.size()), args.size());
        }
        return ProcessHandle.current().info().arguments().map(List::of)
                .orElseThrow(() -> new IllegalStateException("cannot read this process's arguments"));
    }

    /** An argument of a Windows command line: its value, and its text {@code line.substring(start, end)}. */
    public record Span(String value, int start, int end) {
    }

    /** {@code line} split as the C runtime splits it ({@code CommandLineToArgvW} too, for any usual program path). */
    static List<String> splitWindows(String line) {
        return spansWindows(line).stream().map(Span::value).toList();
    }

    /** {@code line} split as {@link #splitWindows} splits it, with each argument's place in the line. */
    public static List<Span> spansWindows(String line) {
        List<Span> args = new ArrayList<>();
        StringBuilder arg = new StringBuilder();
        int start = -1;
        boolean quoted = false;
        int i = 0;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (!quoted && (c == ' ' || c == '\t')) {
                if (start >= 0) {
                    args.add(new Span(arg.toString(), start, i));
                    arg.setLength(0);
                    start = -1;
                }
                i++;
                continue;
            }
            if (start < 0) {
                start = i;
            }
            if (args.isEmpty()) {
                // the program name has no escapes: a path's backslash before a quote is a backslash
                if (c == '"') {
                    quoted = !quoted;
                } else {
                    arg.append(c);
                }
                i++;
            } else if (c == '\\') {
                int from = i;
                while (i < line.length() && line.charAt(i) == '\\') {
                    i++;
                }
                int n = i - from;
                boolean beforeQuote = i < line.length() && line.charAt(i) == '"';
                arg.append("\\".repeat(beforeQuote ? n / 2 : n));
                if (beforeQuote && n % 2 == 1) {
                    arg.append('"');
                    i++;
                }
            } else if (c == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    arg.append('"');
                    i += 2;
                } else {
                    quoted = !quoted;
                    i++;
                }
            } else {
                arg.append(c);
                i++;
            }
        }
        if (start >= 0) {
            args.add(new Span(arg.toString(), start, line.length()));
        }
        return args;
    }
}
