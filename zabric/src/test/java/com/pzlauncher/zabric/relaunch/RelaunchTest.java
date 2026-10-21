package com.pzlauncher.zabric.relaunch;

import com.pzlauncher.zabric.internal.GameArgs;
import com.pzlauncher.zabric.internal.WindowsProcess;
import com.pzlauncher.zabric.relaunch.impl.LaunchSpec;
import com.pzlauncher.zabric.relaunch.impl.Waiter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelaunchTest {
    private static final String LINE = "\"C:\\Program Files\\PZ\\ProjectZomboid64.exe\" -agentlib:zbNative --"
            + "  \"-cachedir=C:\\my dir\"\t+connect 10.0.0.5:16261 +password \"a \\\"b\\\" c\" -debug";

    @TempDir
    Path dir;

    // what unavailable() is made of

    @Test
    void onlyAWindowsClientWithItsProgramCommandLineJavawAndJarCanRestart() throws Exception {
        Path javaHome = Files.createDirectories(dir.resolve("jre/bin")).getParent();
        Files.writeString(javaHome.resolve("bin/javaw.exe"), "");
        Path jar = Files.writeString(dir.resolve("Zabric.jar"), "");
        URL jarUrl = jar.toUri().toURL();
        Supplier<Optional<String>> exe = () -> Optional.of("C:\\PZ\\ProjectZomboid64.exe");
        Supplier<String> line = () -> LINE;

        assertEquals(new Relaunch.Host("C:\\PZ\\ProjectZomboid64.exe", LINE, javaHome.resolve("bin/javaw.exe"), jar),
                Relaunch.host("Windows 11", false, exe, line, javaHome, jarUrl));

        Supplier<String> unread = () -> {
            throw new AssertionError("the command line is read on Windows only");
        };
        assertUnavailable("the game restarts itself only on Windows, not on Linux",
                () -> Relaunch.host("Linux", false, exe, unread, javaHome, jarUrl));
        assertUnavailable("a dedicated server does not restart itself",
                () -> Relaunch.host("Windows 11", true, exe, line, javaHome, jarUrl));
        assertUnavailable("cannot read this process's program path",
                () -> Relaunch.host("Windows 11", false, Optional::empty, line, javaHome, jarUrl));
        assertUnavailable("cannot read this process's command line: no FFM", () -> Relaunch.host("Windows 11", false,
                exe, () -> {
                    throw new IllegalStateException("cannot read this process's command line: no FFM");
                }, javaHome, jarUrl));
        assertUnavailable("the game's Java runtime has no " + dir.resolve("other/bin/javaw.exe"),
                () -> Relaunch.host("Windows 11", false, exe, line, dir.resolve("other"), jarUrl));
        assertUnavailable("Zabric's classes have no location",
                () -> Relaunch.host("Windows 11", false, exe, line, javaHome, null));
        assertUnavailable("Zabric's classes do not come from a jar file but from " + dir,
                () -> Relaunch.host("Windows 11", false, exe, line, javaHome, dir.toUri().toURL()));
    }

    private interface HostCall {
        Relaunch.Host call() throws Exception;
    }

    private static void assertUnavailable(String why, HostCall call) {
        assertEquals(why, assertThrows(Relaunch.Unavailable.class, call::call).getMessage());
    }

    // leaving arguments out

    @Test
    void nothingOmittedKeepsTheLineAsItIs() {
        assertEquals(LINE, Relaunch.omit(LINE, Set.of()));
    }

    @Test
    void anOmittedArgumentGoesWithTheSpaceBeforeIt() {
        String exe = "\"C:\\Program Files\\PZ\\ProjectZomboid64.exe\"";
        // first argument after the program
        assertEquals(exe + " --  \"-cachedir=C:\\my dir\"\t+connect 10.0.0.5:16261 +password \"a \\\"b\\\" c\""
                + " -debug", Relaunch.omit(LINE, Set.of(0)));
        // a quoted argument in the middle, after two spaces
        assertEquals(exe + " -agentlib:zbNative --\t+connect 10.0.0.5:16261 +password \"a \\\"b\\\" c\" -debug",
                Relaunch.omit(LINE, Set.of(2)));
        // the last one
        assertEquals(exe + " -agentlib:zbNative --  \"-cachedir=C:\\my dir\"\t+connect 10.0.0.5:16261 +password"
                + " \"a \\\"b\\\" c\"", Relaunch.omit(LINE, Set.of(7)));
    }

    @Test
    void joinArgumentsAndTheirValuesGoTogether() {
        List<String> args = GameArgs.spansWindows(LINE).stream().skip(1).map(GameArgs.Span::value).toList();
        assertEquals(List.of("-agentlib:zbNative", "--", "-cachedir=C:\\my dir", "+connect", "10.0.0.5:16261",
                "+password", "a \"b\" c", "-debug"), args);
        assertEquals("\"C:\\Program Files\\PZ\\ProjectZomboid64.exe\" -agentlib:zbNative --  \"-cachedir=C:\\my dir\""
                + " -debug", Relaunch.omit(LINE, Set.of(3, 4, 5, 6)));
        assertEquals("\"C:\\Program Files\\PZ\\ProjectZomboid64.exe\" -agentlib:zbNative --  \"-cachedir=C:\\my dir\""
                + "\t+connect 10.0.0.5:16261 -debug", Relaunch.omit(LINE, Set.of(5, 6)));
    }

    @Test
    void anIndexOutsideTheArgumentsThrows() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Relaunch.omit(LINE, Set.of(1, 8)));
        assertEquals("no argument 8 to leave out: this launch has 8 arguments", e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> Relaunch.omit(LINE, Set.of(-1)));
    }

    // the handshake

    @Test
    void aReadyHelperGetsTheLaunchAndTheGameQuits() throws Exception {
        FakeWaiter waiter = new FakeWaiter("ready\r\n");
        AtomicBoolean quit = new AtomicBoolean();
        Relaunch.restart(spec(), s -> waiter, Duration.ofSeconds(5), () -> quit.set(true));
        assertTrue(quit.get());
        assertFalse(waiter.destroyed());
        byte[] handedOver = waiter.stdin.toByteArray();
        assertEquals(spec(), LaunchSpec.read(new DataInputStream(new ByteArrayInputStream(handedOver))));
    }

    @Test
    void anotherAnswerAnEarlyExitOrNoAnswerDestroysTheHelperAndKeepsTheGame() {
        assertRefused(new FakeWaiter("hello\n"), Duration.ofSeconds(5), "the restart helper answered 'hello', not"
                + " 'ready'");
        assertRefused(new FakeWaiter(""), Duration.ofSeconds(5), "the restart helper exited before it answered (its"
                + " errors are in " + dir.resolve("Zabric/relaunch.log") + ")");
        assertRefused(new FakeWaiter(null), Duration.ofSeconds(1), "the restart helper did not answer within 1 s"
                + " (its errors are in " + dir.resolve("Zabric/relaunch.log") + ")");
    }

    private void assertRefused(FakeWaiter waiter, Duration handshake, String message) {
        AtomicBoolean quit = new AtomicBoolean();
        IOException e = assertThrows(IOException.class,
                () -> Relaunch.restart(spec(), s -> waiter, handshake, () -> quit.set(true)));
        assertEquals(message, e.getMessage());
        assertTrue(waiter.destroyed());
        assertFalse(quit.get());
    }

    @Test
    void aQuitThatThrowsDestroysTheHelper() {
        FakeWaiter waiter = new FakeWaiter("ready\n");
        IllegalStateException failure = new IllegalStateException("no core");
        assertSame(failure, assertThrows(IllegalStateException.class, () -> Relaunch.restart(spec(), s -> waiter,
                Duration.ofSeconds(5), () -> {
                    throw failure;
                })));
        assertTrue(waiter.destroyed());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aRealHelperAnswersAndWaitsForTheGame() throws Exception {
        Path javaw = Path.of(System.getProperty("java.home"), "bin", "javaw.exe");
        String classPath = location(Waiter.class) + File.pathSeparator + location(WindowsProcess.class);
        // this test JVM stands in for the game: it does not exit while the helper waits
        LaunchSpec spec = new LaunchSpec(ProcessHandle.current().pid(), "never.exe", "never.exe", dir,
                System.getenv(), dir.resolve("Zabric"));
        List<Process> started = new ArrayList<>();
        AtomicBoolean quit = new AtomicBoolean();
        try {
            Relaunch.restart(spec, s -> {
                Process p = Relaunch.startWaiter(javaw, classPath, s);
                started.add(p);
                return p;
            }, Duration.ofSeconds(30), () -> quit.set(true));
            assertTrue(quit.get());
            assertFalse(started.get(0).waitFor(1, TimeUnit.SECONDS), "the helper waits for the game");
        } finally {
            started.forEach(Process::destroyForcibly);
        }
    }

    private static Path location(Class<?> c) throws Exception {
        return Path.of(c.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    private LaunchSpec spec() {
        return new LaunchSpec(99, "pz.exe", LINE, dir, Map.of("A", "1"), dir.resolve("Zabric"));
    }

    /** A helper that answers {@code answer} on stdout, or (null) nothing until it is destroyed. */
    private static final class FakeWaiter extends Process {
        final ByteArrayOutputStream stdin = new ByteArrayOutputStream();
        private final CountDownLatch destroyed = new CountDownLatch(1);
        private final InputStream stdout;

        FakeWaiter(String answer) {
            stdout = answer != null ? new ByteArrayInputStream(answer.getBytes(StandardCharsets.UTF_8))
                    : new InputStream() {
                        @Override
                        public int read() throws IOException {
                            try {
                                destroyed.await();
                            } catch (InterruptedException e) {
                                throw new IOException(e);
                            }
                            return -1;
                        }
                    };
        }

        boolean destroyed() {
            return destroyed.getCount() == 0;
        }

        @Override
        public OutputStream getOutputStream() {
            return stdin;
        }

        @Override
        public InputStream getInputStream() {
            return stdout;
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() throws InterruptedException {
            destroyed.await();
            return 1;
        }

        @Override
        public int exitValue() {
            if (!destroyed()) {
                throw new IllegalThreadStateException();
            }
            return 1;
        }

        @Override
        public void destroy() {
            destroyed.countDown();
        }
    }
}
