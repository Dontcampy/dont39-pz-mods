package com.pzlauncher.zabric.relaunch.impl;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What the restart helper starts once the game has exited: this launch's program, its raw command line (minus the
 * arguments the caller left out), working folder and environment. The game hands it to the helper over its stdin.
 *
 * @param pid         the game process the helper waits for
 * @param zabricDir   where the helper records a failure
 */
public record LaunchSpec(long pid, String exe, String commandLine, Path workingDir, Map<String, String> environment,
                         Path zabricDir) {
    // the helper and the game come from the same jar; this only catches a jar replaced in between
    static final int FORMAT = 1;
    // a JVM reads these at its start, so the game's agents (ZombieBuddy's among them) would load into the helper
    static final Set<String> JVM_OPTION_VARIABLES = Set.of("_JAVA_OPTIONS", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS");
    // as Windows orders an environment block: by name, without case
    private static final Comparator<String> NAME_ORDER = (a, b) -> {
        int n = Math.min(a.length(), b.length());
        for (int i = 0; i < n; i++) {
            int c = Character.compare(Character.toUpperCase(a.charAt(i)), Character.toUpperCase(b.charAt(i)));
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(a.length(), b.length());
    };

    public LaunchSpec {
        environment = Map.copyOf(environment);
    }

    public void write(DataOutputStream out) throws IOException {
        out.writeInt(FORMAT);
        out.writeLong(pid);
        writeString(out, exe);
        writeString(out, commandLine);
        writeString(out, workingDir.toString());
        writeString(out, zabricDir.toString());
        out.writeInt(environment.size());
        for (Map.Entry<String, String> e : environment.entrySet()) {
            writeString(out, e.getKey());
            writeString(out, e.getValue());
        }
        out.flush();
    }

    public static LaunchSpec read(DataInputStream in) throws IOException {
        int format = in.readInt();
        if (format != FORMAT) {
            throw new IOException("the launch is described in format " + format + ", this helper reads " + FORMAT);
        }
        long pid = in.readLong();
        String exe = readString(in);
        String commandLine = readString(in);
        Path workingDir = Path.of(readString(in));
        Path zabricDir = Path.of(readString(in));
        int count = in.readInt();
        if (count < 0) {
            throw new IOException("the launch's environment has " + count + " variables");
        }
        Map<String, String> environment = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            environment.put(readString(in), readString(in));
        }
        return new LaunchSpec(pid, exe, commandLine, workingDir, environment, zabricDir);
    }

    /** The helper's own environment: the game's, without the variables that pass options to a JVM. */
    public Map<String, String> waiterEnvironment() {
        Map<String, String> env = new LinkedHashMap<>(environment);
        env.keySet().removeIf(name -> JVM_OPTION_VARIABLES.contains(name.toUpperCase(Locale.ROOT)));
        return env;
    }

    /** The whole environment as {@code CreateProcessW} takes it: sorted {@code name=value\0} entries, then a NUL. */
    public String environmentBlock() {
        StringBuilder block = new StringBuilder();
        environment.keySet().stream().sorted(NAME_ORDER)
                .forEach(name -> block.append(name).append('=').append(environment.get(name)).append('\0'));
        if (block.isEmpty()) {
            block.append('\0');
        }
        return block.append('\0').toString();
    }

    // not writeUTF: its 64 KB cap is below a 32767-char command line or variable in modified UTF-8
    private static void writeString(DataOutputStream out, String s) throws IOException {
        out.writeInt(s.length());
        out.writeChars(s);
    }

    private static String readString(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0) {
            throw new IOException("a string of the launch has length " + length);
        }
        char[] chars = new char[length];
        for (int i = 0; i < length; i++) {
            chars[i] = in.readChar();
        }
        return new String(chars);
    }
}
