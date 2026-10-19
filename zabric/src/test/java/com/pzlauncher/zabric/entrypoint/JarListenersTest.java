package com.pzlauncher.zabric.entrypoint;

import com.pzlauncher.zabric.hook.ZabricHooks;
import com.pzlauncher.zabric.hook.impl.CapturedLog;
import com.pzlauncher.zabric.hook.impl.HookRuntime;
import com.pzlauncher.zabric.hook.weave.Weaving;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JarListenersTest {
    private final JarListeners listeners = new JarListeners();
    private CapturedLog log;

    @BeforeEach
    void captureLog() {
        log = CapturedLog.install();
    }

    private static void open(String... modIds) {
        Map<String, List<Path>> pass = Arrays.stream(modIds).collect(Collectors.toMap(id -> id, id -> List.of()));
        HookRuntime.passProcessed(pass, JarListenersTest.class.getClassLoader(), Weaving.instrumentation());
    }

    private void pass(String... modIds) {
        for (int i = 0; i < modIds.length; i++) {
            listeners.jar(i + 1, modIds.length, modIds[i], false);
            listeners.jar(i + 1, modIds.length, modIds[i], true);
        }
    }

    @Test
    void hearsEachJarInOrderWithThePassTotal() {
        open("lp-order");
        Recorder recorder = new Recorder();
        listeners.listen("lp-order", recorder);
        pass("Zabric", "a", "b");
        assertEquals(List.of("loading 1/3 Zabric", "loaded 1/3 Zabric", "loading 2/3 a", "loaded 2/3 a",
                "loading 3/3 b", "loaded 3/3 b"), recorder.calls);
        assertEquals(List.of(), log.lines());
    }

    @Test
    void hearsOnlyFromItsRegistrationOnAndWhileItsModIsOpen() {
        Recorder recorder = new Recorder();
        listeners.jar(1, 2, "a", false);
        listeners.listen("lp-timing", recorder);
        listeners.jar(1, 2, "a", true);
        assertEquals(List.of(), recorder.calls, "the mod's hooks are not open yet");
        open("lp-timing");
        listeners.jar(2, 2, "b", false);
        assertEquals(List.of("loading 2/2 b"), recorder.calls);
        open("lp-other");
        listeners.jar(2, 2, "b", true);
        assertEquals(List.of("loading 2/2 b"), recorder.calls, "a pass without the mod closed its hooks");
    }

    @Test
    void aListenerThatThrowsIsRemovedAndStopsItsModOthersGoOn() {
        open("lp-throws", "lp-fine");
        LoaderProgress.Listener throwing = (index, total, modId) -> {
            throw new IllegalStateException("broke at " + index);
        };
        Recorder fine = new Recorder();
        listeners.listen("lp-throws", throwing);
        listeners.listen("lp-fine", fine);
        pass("a", "b");
        assertFalse(ZabricHooks.mod("lp-throws").open());
        assertTrue(ZabricHooks.mod("lp-fine").open());
        assertEquals(4, fine.calls.size(), fine.calls.toString());
        assertEquals(List.of("error [Zabric] lp-throws: hooks stopped after an error while running its loader progress"
                + " listener " + throwing.getClass().getName() + " at jar 1 / 2 (a)"
                + " | java.lang.IllegalStateException: broke at 1"), log.lines());
    }

    @Test
    void unavailableStopsEveryListenersModAndLaterOnes() {
        open("lp-lost", "lp-late");
        Recorder recorder = new Recorder();
        assertFalse(listeners.unavailable(new IllegalStateException("none yet")), "no listener heard it");
        listeners.listen("lp-lost", recorder);
        assertFalse(ZabricHooks.mod("lp-lost").open(), "registered after the loss");

        JarListeners fresh = new JarListeners();
        Recorder late = new Recorder();
        fresh.listen("lp-late", late);
        assertTrue(fresh.unavailable(new IllegalStateException("lost")));
        fresh.jar(1, 1, "a", false);
        assertEquals(List.of(), late.calls);
        assertFalse(ZabricHooks.mod("lp-late").open());
        assertEquals(List.of(
                "error [Zabric] lp-lost: hooks stopped after an error while following the loader's progress with "
                        + Recorder.class.getName() + " | java.lang.IllegalStateException: none yet",
                "error [Zabric] lp-late: hooks stopped after an error while following the loader's progress with "
                        + Recorder.class.getName() + " | java.lang.IllegalStateException: lost"), log.lines());
    }

    private static final class Recorder implements LoaderProgress.Listener {
        final List<String> calls = new ArrayList<>();

        @Override
        public void jarLoading(int index, int total, String modId) {
            calls.add("loading " + index + "/" + total + " " + modId);
        }

        @Override
        public void jarLoaded(int index, int total, String modId) {
            calls.add("loaded " + index + "/" + total + " " + modId);
        }
    }
}
