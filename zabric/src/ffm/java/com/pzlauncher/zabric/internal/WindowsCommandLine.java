package com.pzlauncher.zabric.internal;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

/** This process's command line on Windows, as {@code GetCommandLineW} returns it. */
public final class WindowsCommandLine {
    private WindowsCommandLine() {
    }

    public static String read() {
        try {
            MemorySegment function = SymbolLookup.libraryLookup("kernel32", Arena.global()).find("GetCommandLineW")
                    .orElseThrow();
            MemorySegment line = (MemorySegment) Linker.nativeLinker()
                    .downcallHandle(function, FunctionDescriptor.of(ValueLayout.ADDRESS)).invokeExact();
            return line.reinterpret(Long.MAX_VALUE).getString(0, StandardCharsets.UTF_16LE);
        } catch (Throwable t) {
            throw new IllegalStateException("cannot read this process's command line: " + t, t);
        }
    }
}
