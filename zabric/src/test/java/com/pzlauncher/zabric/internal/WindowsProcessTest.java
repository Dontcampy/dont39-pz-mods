package com.pzlauncher.zabric.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledOnOs(OS.WINDOWS)
class WindowsProcessTest {
    @TempDir
    Path dir;

    @Test
    void startsTheProgramWithTheRawLineTheBlocksEnvironmentAndTheFolder() throws Exception {
        Path out = dir.resolve("out.txt");
        String systemRoot = System.getenv("SystemRoot");
        String cmd = Path.of(systemRoot, "System32", "cmd.exe").toString();
        // PATH is in this process's environment, not in the block
        String line = "cmd.exe /d /c echo %ZABRIC_TEST%>\"" + out + "\"& cd>>\"" + out + "\"& echo %PATH%>>\"" + out
                + "\"";
        String block = "SystemRoot=" + systemRoot + "\0ZABRIC_TEST=from the block\0\0";

        long pid = WindowsProcess.start(cmd, line, dir.toString(), block);
        ProcessHandle.of(pid).ifPresent(p -> p.onExit().join());

        assertEquals(List.of("from the block", dir.toString(), "%PATH%"), Files.readAllLines(out));
    }

    @Test
    void aProgramThatCannotStartThrowsWithTheWindowsError() {
        Path missing = dir.resolve("missing.exe");
        IOException e = assertThrows(IOException.class,
                () -> WindowsProcess.start(missing.toString(), "missing.exe", dir.toString(), "\0\0"));
        assertTrue(e.getMessage().contains(missing + ": CreateProcessW failed with Windows error 2"), e.getMessage());
    }
}
