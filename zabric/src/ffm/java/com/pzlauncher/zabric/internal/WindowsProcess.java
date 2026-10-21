package com.pzlauncher.zabric.internal;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.nio.charset.StandardCharsets;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/** Windows process start and message box, for the restart helper. */
public final class WindowsProcess {
    private static final int CREATE_UNICODE_ENVIRONMENT = 0x400;
    private static final int MB_ICONERROR = 0x10;
    private static final int MB_SETFOREGROUND = 0x10000;

    private static final StructLayout STARTUPINFOW = MemoryLayout.structLayout(
            JAVA_INT.withName("cb"), MemoryLayout.paddingLayout(4),
            ADDRESS.withName("lpReserved"), ADDRESS.withName("lpDesktop"), ADDRESS.withName("lpTitle"),
            MemoryLayout.sequenceLayout(8, JAVA_INT).withName("dwXThroughDwFlags"),
            JAVA_SHORT.withName("wShowWindow"), JAVA_SHORT.withName("cbReserved2"), MemoryLayout.paddingLayout(4),
            ADDRESS.withName("lpReserved2"), ADDRESS.withName("hStdInput"), ADDRESS.withName("hStdOutput"),
            ADDRESS.withName("hStdError"));
    private static final StructLayout PROCESS_INFORMATION = MemoryLayout.structLayout(
            ADDRESS.withName("hProcess"), ADDRESS.withName("hThread"), JAVA_INT.withName("dwProcessId"),
            JAVA_INT.withName("dwThreadId"));

    private WindowsProcess() {
    }

    /**
     * Starts {@code exe} with {@code commandLine} exactly as given (the program's own argument parsing sees it as
     * is), in {@code workingDir}, with {@code environmentBlock} ({@code name=value\0} entries, sorted, ending in an
     * extra {@code \0}) and no inherited handles. Returns the new process's id.
     */
    public static long start(String exe, String commandLine, String workingDir, String environmentBlock)
            throws IOException {
        StructLayout state = Linker.Option.captureStateLayout();
        VarHandle lastError = state.varHandle(MemoryLayout.PathElement.groupElement("GetLastError"));
        try (Arena arena = Arena.ofConfined()) {
            SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", arena);
            MethodHandle create = Linker.nativeLinker().downcallHandle(kernel32.find("CreateProcessW").orElseThrow(),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS,
                            ADDRESS, ADDRESS, ADDRESS),
                    Linker.Option.captureCallState("GetLastError"));
            MethodHandle close = Linker.nativeLinker().downcallHandle(kernel32.find("CloseHandle").orElseThrow(),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS));
            MemorySegment captured = arena.allocate(state);
            MemorySegment startup = arena.allocate(STARTUPINFOW);
            startup.set(JAVA_INT, 0, (int) STARTUPINFOW.byteSize());
            MemorySegment info = arena.allocate(PROCESS_INFORMATION);
            // CreateProcessW may write into the command line, so it gets its own copy
            int ok = (int) create.invokeExact(captured, wide(arena, exe), wide(arena, commandLine), MemorySegment.NULL,
                    MemorySegment.NULL, 0, CREATE_UNICODE_ENVIRONMENT, wide(arena, environmentBlock),
                    wide(arena, workingDir), startup, info);
            if (ok == 0) {
                throw new IOException("cannot start " + exe + ": CreateProcessW failed with Windows error "
                        + (int) lastError.get(captured, 0L));
            }
            // the new process does not depend on these handles; a failed close only leaks them until the helper exits
            int closedProcess = (int) close.invokeExact(info.get(ADDRESS, 0));
            int closedThread = (int) close.invokeExact(info.get(ADDRESS, ADDRESS.byteSize()));
            return Integer.toUnsignedLong(info.get(JAVA_INT, 2 * ADDRESS.byteSize()));
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException("cannot call CreateProcessW: " + t, t);
        }
    }

    /** Shows an error message box with no owner window and waits until it is closed. */
    public static void errorBox(String title, String text) {
        StructLayout state = Linker.Option.captureStateLayout();
        VarHandle lastError = state.varHandle(MemoryLayout.PathElement.groupElement("GetLastError"));
        int result;
        int error;
        try (Arena arena = Arena.ofConfined()) {
            MethodHandle box = Linker.nativeLinker().downcallHandle(
                    SymbolLookup.libraryLookup("user32", arena).find("MessageBoxW").orElseThrow(),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT),
                    Linker.Option.captureCallState("GetLastError"));
            MemorySegment captured = arena.allocate(state);
            result = (int) box.invokeExact(captured, MemorySegment.NULL, wide(arena, text), wide(arena, title),
                    MB_ICONERROR | MB_SETFOREGROUND);
            error = (int) lastError.get(captured, 0L);
        } catch (Throwable t) {
            throw new IllegalStateException("cannot call MessageBoxW: " + t, t);
        }
        if (result == 0) {
            throw new IllegalStateException("MessageBoxW failed with Windows error " + error);
        }
    }

    private static MemorySegment wide(Arena arena, String s) {
        return arena.allocateFrom(s, StandardCharsets.UTF_16LE);
    }
}
