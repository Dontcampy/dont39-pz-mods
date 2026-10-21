package com.pzlauncher.zabric.relaunch;

import com.pzlauncher.zabric.entrypoint.Lifecycle;
import com.pzlauncher.zabric.internal.GameArgs;
import com.pzlauncher.zabric.internal.GameFolders;
import com.pzlauncher.zabric.internal.WindowsCommandLine;
import com.pzlauncher.zabric.relaunch.impl.LaunchSpec;
import com.pzlauncher.zabric.relaunch.impl.RelaunchFailure;
import com.pzlauncher.zabric.relaunch.impl.Waiter;
import zombie.core.Core;
import zombie.debug.DebugType;
import zombie.network.GameServer;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import static com.pzlauncher.zabric.internal.Log.PREFIX;

/**
 * Restarts the game with this launch's program, command line, working folder and environment, so that the next launch
 * runs as this one did, whatever a launcher in between would change. A small helper process waits for the game to
 * exit and starts it again; a failure after the game quit shows a message box and is shown on the next launch's main
 * menu.
 * <p>
 * Windows clients only. Call on the main thread, from a main entrypoint or later. Every method throws when Zabric did
 * not run at game start or its entrypoints are stopped for the session.
 */
public final class Relaunch {
    static final Duration HANDSHAKE = Duration.ofSeconds(10);

    /** What a restart needs of this launch. */
    record Host(String exe, String commandLine, Path javaw, Path jar) {
    }

    /** Why this launch cannot restart itself. */
    static final class Unavailable extends Exception {
        Unavailable(String why) {
            super(why);
        }
    }

    /** Starts the helper for {@code spec}. */
    interface WaiterStart {
        Process start(LaunchSpec spec) throws IOException;
    }

    private Relaunch() {
    }

    /** Why this launch cannot restart itself (another OS, a dedicated server, ...), or null when it can. */
    public static String unavailable() {
        Lifecycle.requireRunning("Relaunch.unavailable");
        try {
            host();
            return null;
        } catch (Unavailable e) {
            return e.getMessage();
        }
    }

    /** This launch's arguments after the program, JVM options included, as the process got them. Any OS and side. */
    public static List<String> arguments() {
        Lifecycle.requireRunning("Relaunch.arguments");
        return GameArgs.read();
    }

    /**
     * Quits the game to start it again, without the arguments at {@code omitted} (indices into {@link #arguments()}),
     * which are cut from the command line together with the space before them; the rest stays as it is. Returns once
     * the game is quitting. Throws, and leaves the game running, when the restart cannot be set up:
     * {@link #unavailable()} names a reason, an index is not in {@link #arguments()}, or the helper process did not
     * start.
     */
    public static void restart(Set<Integer> omitted) throws IOException {
        Lifecycle.requireRunning("Relaunch.restart");
        Host host;
        try {
            host = host();
        } catch (Unavailable e) {
            // the reasons read on their own; callers put them after their own "cannot restart"
            throw new IllegalStateException(e.getMessage());
        }
        LaunchSpec spec = new LaunchSpec(ProcessHandle.current().pid(), host.exe(),
                omit(host.commandLine(), omitted), Path.of(System.getProperty("user.dir")), System.getenv(),
                GameFolders.zabricDir());
        Process waiter = restart(spec, s -> startWaiter(host.javaw(), host.jar().toString(), s), HANDSHAKE,
                () -> Core.getInstance().quitToDesktop());
        DebugType.Mod.println(PREFIX + "restart: the game quits; helper process %d starts it again once it exited",
                waiter.pid());
    }

    private static Host host() throws Unavailable {
        return host(System.getProperty("os.name"), GameServer.server, () -> ProcessHandle.current().info().command(),
                WindowsCommandLine::read, Path.of(System.getProperty("java.home")), codeSource());
    }

    // checked in the order a later check depends on an earlier one: the command line is read only on Windows
    static Host host(String osName, boolean server, Supplier<Optional<String>> exe, Supplier<String> commandLine,
                     Path javaHome, URL zabricLocation) throws Unavailable {
        if (!osName.startsWith("Win")) {
            throw new Unavailable("the game restarts itself only on Windows, not on " + osName);
        }
        if (server) {
            throw new Unavailable("a dedicated server does not restart itself");
        }
        String program = exe.get().orElseThrow(() -> new Unavailable("cannot read this process's program path"));
        String line;
        try {
            line = commandLine.get();
        } catch (RuntimeException e) {
            throw new Unavailable(e.getMessage());
        }
        Path javaw = javaHome.resolve("bin").resolve("javaw.exe");
        if (!Files.isRegularFile(javaw)) {
            throw new Unavailable("the game's Java runtime has no " + javaw);
        }
        return new Host(program, line, javaw, zabricJar(zabricLocation));
    }

    private static Path zabricJar(URL location) throws Unavailable {
        if (location == null) {
            throw new Unavailable("Zabric's classes have no location");
        }
        Path jar;
        try {
            jar = Path.of(location.toURI());
        } catch (Exception e) {
            throw new Unavailable("Zabric's classes do not come from a jar file but from " + location);
        }
        if (!Files.isRegularFile(jar)) {
            throw new Unavailable("Zabric's classes do not come from a jar file but from " + jar);
        }
        return jar;
    }

    private static URL codeSource() {
        CodeSource source = Relaunch.class.getProtectionDomain().getCodeSource();
        return source == null ? null : source.getLocation();
    }

    /** {@code line} without the arguments at {@code omitted} (indices after the program) and the space before each. */
    static String omit(String line, Set<Integer> omitted) {
        List<GameArgs.Span> spans = GameArgs.spansWindows(line);
        int arguments = Math.max(0, spans.size() - 1);
        for (int i : omitted) {
            if (i < 0 || i >= arguments) {
                throw new IllegalArgumentException("no argument " + i + " to leave out: this launch has " + arguments
                        + " arguments");
            }
        }
        StringBuilder out = new StringBuilder();
        int from = 0;
        for (int i : new TreeSet<>(omitted)) {
            out.append(line, from, spans.get(i).end());
            from = spans.get(i + 1).end();
        }
        return out.append(line, from, line.length()).toString();
    }

    static Process startWaiter(Path java, String classPath, LaunchSpec spec) throws IOException {
        Path log = RelaunchFailure.log(spec.zabricDir());
        Files.createDirectories(log.getParent());
        ProcessBuilder builder = new ProcessBuilder(java.toString(), "--enable-native-access=ALL-UNNAMED", "-cp",
                classPath, Waiter.class.getName())
                .directory(spec.workingDir().toFile())
                .redirectError(ProcessBuilder.Redirect.appendTo(log.toFile()));
        builder.environment().clear();
        builder.environment().putAll(spec.waiterEnvironment());
        return builder.start();
    }

    /**
     * Hands {@code spec} to a helper from {@code start}, and quits once it answered within {@code handshake}. Returns
     * the helper.
     */
    static Process restart(LaunchSpec spec, WaiterStart start, Duration handshake, Runnable quit) throws IOException {
        Process waiter = start.start(spec);
        try {
            handshake(waiter, spec, handshake);
            quit.run();
            return waiter;
        } catch (Throwable t) {
            waiter.destroyForcibly();
            throw t;
        }
    }

    private static void handshake(Process waiter, LaunchSpec spec, Duration timeout) throws IOException {
        Path log = RelaunchFailure.log(spec.zabricDir());
        // the write is timed too: a helper that does not read fills the pipe
        FutureTask<String> answer = new FutureTask<>(() -> {
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(waiter.getOutputStream()))) {
                spec.write(out);
            } catch (IOException e) {
                throw new IOException("cannot hand the launch to the restart helper (its errors are in " + log
                        + "): " + e, e);
            }
            try {
                return new BufferedReader(new InputStreamReader(waiter.getInputStream(), StandardCharsets.UTF_8))
                        .readLine();
            } catch (IOException e) {
                throw new IOException("cannot read the restart helper's answer: " + e, e);
            }
        });
        Thread reader = new Thread(answer, "Zabric restart helper");
        reader.setDaemon(true);
        reader.start();
        String line;
        try {
            line = answer.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new IOException("the restart helper did not answer within " + timeout.toSeconds() + " s (its"
                    + " errors are in " + log + ")");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof IOException io) {
                throw io;
            }
            throw new IOException("the restart helper's handshake failed: " + e.getCause(), e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("interrupted while waiting for the restart helper");
        }
        if (line == null) {
            throw new IOException("the restart helper exited before it answered (its errors are in " + log + ")");
        }
        if (!line.equals(Waiter.READY)) {
            throw new IOException("the restart helper answered '" + line + "', not '" + Waiter.READY + "'");
        }
    }
}
