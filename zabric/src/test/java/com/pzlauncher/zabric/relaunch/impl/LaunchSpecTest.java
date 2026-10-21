package com.pzlauncher.zabric.relaunch.impl;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LaunchSpecTest {
    private static final Map<String, String> ENV = Map.of(
            "Path", "C:\\Windows;C:\\游戏", "_JAVA_OPTIONS", "-agentlib:zbNative", "a", "1", "B", "2",
            "java_tool_options", "-Dx=1", "JDK_JAVA_OPTIONS", "-Dy=2");

    @Test
    void readsBackAsWritten() throws Exception {
        // an unpaired surrogate is legal in a Windows command line and must survive as is
        LaunchSpec spec = new LaunchSpec(4242, "C:\\PZ\\ProjectZomboid64.exe",
                "\"C:\\PZ\\ProjectZomboid64.exe\"  -agentlib:zbNative -- \"-cachedir=C:\\my dir\" \uD800x",
                Path.of("C:\\PZ"), ENV, Path.of("C:\\Users\\p\\Zomboid\\Zabric"));
        assertEquals(spec, LaunchSpec.read(input(bytes(spec))));
    }

    @Test
    void aStringPastWriteUtfsLimitReadsBack() throws Exception {
        LaunchSpec spec = spec(Map.of("LONG", "游".repeat(32767)));
        assertEquals(spec, LaunchSpec.read(input(bytes(spec))));
    }

    @Test
    void anotherFormatIsRefused() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        new DataOutputStream(bytes).writeInt(LaunchSpec.FORMAT + 1);
        IOException e = assertThrows(IOException.class, () -> LaunchSpec.read(input(bytes.toByteArray())));
        assertEquals("the launch is described in format 2, this helper reads 1", e.getMessage());
    }

    @Test
    void theBlockIsSortedWithoutCaseAndEndsInTwoNuls() {
        // by upper case, as Windows sorts: '_' comes after the letters
        assertEquals("a=1\0B=2\0java_tool_options=-Dx=1\0JDK_JAVA_OPTIONS=-Dy=2\0Path=C:\\Windows;C:\\游戏\0"
                + "_JAVA_OPTIONS=-agentlib:zbNative\0\0", spec(ENV).environmentBlock());
        assertEquals("\0\0", spec(Map.of()).environmentBlock());
    }

    @Test
    void onlyTheHelperLosesTheJvmOptionVariables() {
        LaunchSpec spec = spec(ENV);
        assertEquals(Map.of("Path", "C:\\Windows;C:\\游戏", "a", "1", "B", "2"), spec.waiterEnvironment());
        assertEquals(ENV, spec.environment());
    }

    private static LaunchSpec spec(Map<String, String> env) {
        return new LaunchSpec(1, "pz.exe", "pz.exe", Path.of("dir"), env, Path.of("zabric"));
    }

    private static byte[] bytes(LaunchSpec spec) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        spec.write(new DataOutputStream(bytes));
        return bytes.toByteArray();
    }

    private static DataInputStream input(byte[] bytes) {
        return new DataInputStream(new ByteArrayInputStream(bytes));
    }
}
