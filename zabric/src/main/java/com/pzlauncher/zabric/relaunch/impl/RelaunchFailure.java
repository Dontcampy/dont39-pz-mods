package com.pzlauncher.zabric.relaunch.impl;

import com.pzlauncher.zabric.internal.AtomicWrite;
import com.pzlauncher.zabric.internal.WindowsProcess;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.function.Consumer;

/**
 * A restart that failed after the game quit: nobody is left to see an exception, so it goes to the log, a marker the
 * next launch's main menu shows, and a message box.
 */
public final class RelaunchFailure {
    private static final String TITLE = "Project Zomboid: restart failed";

    private RelaunchFailure() {
    }

    /** The restart helper's log; its stderr goes there too. */
    public static Path log(Path zabricDir) {
        return zabricDir.resolve("relaunch.log");
    }

    static Path marker(Path zabricDir) {
        return zabricDir.resolve("relaunch-failed.txt");
    }

    /** Records {@code reason}, and shows it in a message box on Windows. Never throws. */
    public static void record(Path zabricDir, String reason) {
        record(zabricDir, reason, System.getProperty("os.name").startsWith("Win")
                ? text -> WindowsProcess.errorBox(TITLE, text) : null);
    }

    // each step is tried even when one before failed: any one of them tells the player
    static void record(Path zabricDir, String reason, Consumer<String> box) {
        Path log = log(zabricDir);
        try {
            Files.createDirectories(zabricDir);
            Files.writeString(log, stamp(reason) + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException e) {
            System.err.println(stamp("cannot write " + log + ": " + e));
        }
        try {
            AtomicWrite.write(marker(zabricDir), reason);
        } catch (IOException | RuntimeException e) {
            System.err.println(stamp("cannot write " + marker(zabricDir) + ": " + e));
        }
        if (box == null) {
            return;
        }
        try {
            box.accept("The game quit to restart, but could not start again:\n\n" + reason
                    + "\n\nStart the game yourself. Details: " + log);
        } catch (RuntimeException | Error e) {
            System.err.println(stamp("cannot show the message box: " + e));
        }
    }

    /**
     * The failure recorded since the last call, removed as it is read; null when there is none. A marker that cannot
     * be read or removed comes back as a text that says so.
     */
    public static String take(Path zabricDir) {
        Path marker = marker(zabricDir);
        if (!Files.exists(marker)) {
            return null;
        }
        String text;
        try {
            text = Files.readString(marker, StandardCharsets.UTF_8);
        } catch (IOException e) {
            text = "a failed restart was recorded in " + marker + ", which cannot be read: " + e;
        }
        try {
            Files.delete(marker);
        } catch (IOException e) {
            text += " (cannot delete " + marker + ": " + e + ")";
        }
        return text;
    }

    /** {@code line} with the local time in front, for the log. */
    static String stamp(String line) {
        return OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS) + " " + line;
    }
}
