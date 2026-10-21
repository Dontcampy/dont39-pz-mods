package com.pzlauncher.zabric.loader;

import com.pzlauncher.zabric.hook.weave.Weaving;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs ZombieBuddy's own {@code loadJar}, with the hook installed, against records as its pass writes them. */
class JarLoadHookTest {
    private static final String LOADER = "me.zed_0xff.zombie_buddy.Loader";
    private static final List<String> calls = new ArrayList<>();
    private static volatile Loader.JarListener listener;
    private static Map<Path, Object> status;
    private static Constructor<?> record;
    private static Method loadJar;
    private static Object premain;
    private static Object main;

    @TempDir
    Path dir;

    private final List<Path> recorded = new ArrayList<>();

    // once per JVM: installing twice would nest the advice twice
    @BeforeAll
    @SuppressWarnings("unchecked")
    static void install() throws ReflectiveOperationException {
        JarLoadHook.install(Weaving.instrumentation(), (index, total, modId, loaded) -> listener.jar(index, total,
                modId, loaded));
        Class<?> loader = Class.forName(LOADER);
        Field f = loader.getDeclaredField("g_jarLoadStatus");
        f.setAccessible(true);
        status = (Map<Path, Object>) f.get(null);
        Class<?> state = Class.forName(LOADER + "$JavaModLoadState");
        record = state.getDeclaredConstructors()[0];
        record.setAccessible(true);
        Class<?> phase = Class.forName(LOADER + "$Phase");
        for (Object c : phase.getEnumConstants()) {
            if (((Enum<?>) c).name().equals("MAIN")) {
                main = c;
            } else {
                premain = c;
            }
        }
        loadJar = loader.getDeclaredMethod("loadJar", Path.class, String.class, String.class, phase);
        loadJar.setAccessible(true);
    }

    @BeforeEach
    void record() {
        calls.clear();
        listener = (index, total, modId, loaded) -> calls.add((loaded ? "loaded " : "loading ") + index + "/" + total
                + " " + modId);
        JarLoadHook.takeEscaped();
    }

    @AfterEach
    void forget() {
        recorded.forEach(status::remove);
    }

    @Test
    void followsThePassJarByJar() throws Exception {
        // recorded by an earlier pass
        jar("stale.jar", "lh-stale", "loaded");
        JarLoadHook.passStarting();
        jar("a.jar", "lh-a", "loaded");
        Path b = jar("b.jar", "lh-b", "loaded");
        jar("skipped.jar", "lh-skipped", "(excluded)");
        // the loader's own path, not the canonical one it keys its records by
        loadJar(dir.resolve(".").resolve("a.jar"), main);
        loadJar(b, premain);
        loadJar(b, main);
        assertEquals(List.of("loading 1/2 lh-a", "loaded 1/2 lh-a", "loading 2/2 lh-b", "loaded 2/2 lh-b"), calls);
        assertNull(JarLoadHook.takeEscaped());
    }

    @Test
    void aJarWithoutARecordEndsThePassUntilTheNext() throws Exception {
        JarLoadHook.passStarting();
        Path a = jar("a.jar", "lh-a", "loaded");
        Path unrecorded = Files.createFile(dir.resolve("unrecorded.jar"));
        loadJar(unrecorded, main);
        loadJar(a, main);
        assertEquals(List.of(), calls);
        Throwable escaped = JarLoadHook.takeEscaped();
        assertTrue(escaped.getMessage().startsWith("ZombieBuddy " + Loader.version() + " loads "
                + unrecorded.toRealPath() + " without a record that it loads it in this pass"), escaped.getMessage());

        JarLoadHook.passStarting();
        Path b = jar("b.jar", "lh-b", "loaded");
        loadJar(b, main);
        assertEquals(List.of("loading 1/1 lh-b", "loaded 1/1 lh-b"), calls);
    }

    @Test
    void aJarGoneSinceItsRecordStillCounts() throws Exception {
        JarLoadHook.passStarting();
        Path gone = jar("gone.jar", "lh-gone", "loaded");
        Files.delete(gone);
        assertFalse(loadJar(gone, main), "the loader's own result: it cannot find the jar");
        assertEquals(List.of("loading 1/1 lh-gone", "loaded 1/1 lh-gone"), calls);
        assertNull(JarLoadHook.takeEscaped());
    }

    @Test
    void whatTheListenerThrowsIsKeptAndTheLoaderGoesOn() throws Exception {
        IllegalStateException thrown = new IllegalStateException("listener broke");
        listener = (index, total, modId, loaded) -> {
            throw thrown;
        };
        JarLoadHook.passStarting();
        Path a = jar("a.jar", "lh-a", "loaded");
        assertFalse(loadJar(a, main), "the loader's own result: the test jar names no package");
        assertSame(thrown, JarLoadHook.takeEscaped());
    }

    @Test
    void aPassThatEndsWithEveryRecordedJarLoadedReportsNothing() throws Exception {
        JarLoadHook.passStarting();
        JarLoadHook.passEnded();
        assertNull(JarLoadHook.takeEscaped(), "no jars recorded, none loaded");

        JarLoadHook.passStarting();
        Path a = jar("a.jar", "lh-a", "loaded");
        loadJar(a, main);
        JarLoadHook.passEnded();
        assertEquals(List.of("loading 1/1 lh-a", "loaded 1/1 lh-a"), calls);
        assertNull(JarLoadHook.takeEscaped());
    }

    @Test
    void aPassThatEndsShortOfItsRecordsIsReportedAndEnded() throws Exception {
        JarLoadHook.passStarting();
        Path a = jar("a.jar", "lh-a", "loaded");
        Path b = jar("b.jar", "lh-b", "loaded");
        loadJar(a, main);
        JarLoadHook.passEnded();
        assertEquals(List.of("loading 1/2 lh-a", "loaded 1/2 lh-a"), calls);
        Throwable escaped = JarLoadHook.takeEscaped();
        assertTrue(escaped.getMessage().startsWith("ZombieBuddy " + Loader.version() + " returned from its pass after"
                + " loading 1 of the 2 jars it recorded"), escaped.getMessage());

        loadJar(b, main);
        assertEquals(2, calls.size(), calls.toString());
        escaped = JarLoadHook.takeEscaped();
        assertTrue(escaped.getMessage().startsWith("ZombieBuddy " + Loader.version() + " loads " + b
                + " outside its pass over the mod list"), escaped.getMessage());
    }

    @Test
    void aJarWhoseLoadThrowsStillEnds() throws Exception {
        Instrumentation inst = Weaving.instrumentation();
        JarLoadHook.passStarting();
        Path gone = jar("gone.jar", "lh-gone", "loaded");
        Files.delete(gone);
        // the loader reports the missing jar on System.err and lets an Error through
        Error thrown = new AssertionError("err broke");
        PrintStream err = System.err;
        System.setErr(new PrintStream(OutputStream.nullOutputStream()) {
            @Override
            public void println(String x) {
                throw thrown;
            }
        });
        try {
            InvocationTargetException e = assertThrows(InvocationTargetException.class, () -> loadJar(gone, main));
            assertSame(thrown, e.getCause());
        } finally {
            System.setErr(err);
        }
        assertEquals(List.of("loading 1/1 lh-gone", "loaded 1/1 lh-gone"), calls);
        long beat = JarLoadHook.heartbeat();
        retransform(inst);
        assertEquals(beat, JarLoadHook.heartbeat(), "counting stopped");
        JarLoadHook.passEnded();
        assertNull(JarLoadHook.takeEscaped());
    }

    @Test
    void theHeartbeatCountsTheLoadersThreadInsideAJarOnly() throws Exception {
        Instrumentation inst = Weaving.instrumentation();
        long[] beats = new long[4];
        listener = (index, total, modId, loaded) -> {
            if (loaded) {
                retransform(inst);
                beats[3] = JarLoadHook.heartbeat();
                return;
            }
            beats[0] = JarLoadHook.heartbeat();
            retransform(inst);
            beats[1] = JarLoadHook.heartbeat();
            Thread other = new Thread(() -> retransform(inst));
            other.start();
            try {
                other.join();
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            beats[2] = JarLoadHook.heartbeat();
        };
        JarLoadHook.passStarting();
        Path a = jar("a.jar", "lh-a", "loaded");
        loadJar(a, main);
        assertNull(JarLoadHook.takeEscaped());
        assertTrue(beats[1] > beats[0], "the loader's thread inside the jar: " + beats[0] + " -> " + beats[1]);
        assertEquals(beats[1], beats[2], "another thread");
        assertEquals(beats[2], beats[3], "after the jar");
        retransform(inst);
        assertEquals(beats[3], JarLoadHook.heartbeat(), "outside the pass's jars");
    }

    private static void retransform(Instrumentation inst) {
        try {
            inst.retransformClasses(Retransformed.class);
        } catch (UnmodifiableClassException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class Retransformed {
    }

    private Path jar(String name, String modId, String reason) throws IOException, ReflectiveOperationException {
        Path jar = Files.createFile(dir.resolve(name)).toRealPath();
        status.put(jar, record.newInstance(modId, jar, null, reason, null, null));
        recorded.add(jar);
        return jar;
    }

    // a blank package makes it return after the checks the hook surrounds
    private static boolean loadJar(Path jar, Object phase) throws ReflectiveOperationException {
        return (boolean) loadJar.invoke(null, jar, "", null, phase);
    }
}
