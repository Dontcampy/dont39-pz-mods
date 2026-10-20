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

    /** {@code line} split as the C runtime and {@code CommandLineToArgvW} split it. */
    static List<String> splitWindows(String line) {
        List<String> args = new ArrayList<>();
        StringBuilder arg = new StringBuilder();
        boolean inArg = false;
        boolean quoted = false;
        int i = 0;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (!quoted && (c == ' ' || c == '\t')) {
                if (inArg) {
                    args.add(arg.toString());
                    arg.setLength(0);
                    inArg = false;
                }
                i++;
                continue;
            }
            inArg = true;
            if (c == '\\') {
                int start = i;
                while (i < line.length() && line.charAt(i) == '\\') {
                    i++;
                }
                int n = i - start;
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
        if (inArg) {
            args.add(arg.toString());
        }
        return args;
    }
}
