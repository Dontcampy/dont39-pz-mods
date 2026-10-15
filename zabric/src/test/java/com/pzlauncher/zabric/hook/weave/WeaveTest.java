package com.pzlauncher.zabric.hook.weave;

import com.pzlauncher.zabric.hook.ZabricHooks;
import com.pzlauncher.zabric.hook.impl.CapturedLog;
import com.pzlauncher.zabric.hook.impl.HookRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the reference glue ({@code glue/WeaveGlue.java}) as ZombieBuddy weaves it at game start. */
class WeaveTest {
    private CapturedLog log;

    @BeforeAll
    static void weaveAndCheck() {
        Weaving.weaveReference();
        CapturedLog log = CapturedLog.install();
        TestGate.INSTANCE.reset();
        pass(Map.of("weave", List.of(Weaving.listings())));
        // logged only after the weave check found the loaded target woven
        assertEquals(List.of("info [Zabric] weave: hooks ready (4 hook classes)"), log.lines());
        assertEquals(List.of(), TestGate.INSTANCE.failures);
    }

    @BeforeEach
    void open() {
        log = CapturedLog.install();
        TestGate.INSTANCE.reset();
        Events.take();
        pass(Map.of("weave", List.of(Weaving.listings())));
    }

    private static void pass(Map<String, List<Path>> jarsByMod) {
        HookRuntime.passProcessed(jarsByMod, WeaveTest.class.getClassLoader(), Weaving.instrumentation());
    }

    @AfterEach
    void nothingStopped() {
        assertEquals(List.of(), log.lines());
        assertEquals(List.of(), TestGate.INSTANCE.failures);
        assertTrue(ZabricHooks.mod("weave").open());
    }

    @Test
    void headCancelSkipsTheTargetAndFinallyStillRuns() {
        TestGate.INSTANCE.skipPing = true;
        new Target().ping();
        assertEquals(List.of("always afterPing"), Events.take());

        TestGate.INSTANCE.skipPing = false;
        new Target().ping();
        assertEquals(List.of("ping body", "always afterPing"), Events.take());
    }

    @Test
    void headCancelWithValueReturnsItAndSkipsReturnHandlers() {
        assertEquals(-1, new Target().compute(-2));
        assertEquals(List.of(), Events.take());
    }

    @Test
    void modifyReturnValueThenShareFromHeadToReturn() {
        // DefaultHooks (h0) adds one before GatedHooks (h1) records
        assertEquals(7, new Target().compute(3));
        assertEquals(List.of("compute body 3", "record 3 -> 7"), Events.take());
    }

    @Test
    void modifyVariableAndReturnCancelWithValue() {
        assertEquals("HELLO BOB", new Target().greet("  bob "));
        assertEquals(List.of("greet body bob"), Events.take());
        assertEquals("hello amy", new Target().greet(" amy"));
        assertEquals(List.of("greet body amy"), Events.take());
    }

    @Test
    void finallyRunsWhenTheTargetThrows() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new Target().boom());
        assertEquals("boom", e.getMessage());
        assertEquals(List.of("boom body", "afterBoom boom"), Events.take());
    }

    @Test
    void closedModLeavesTheTargetUntouched() {
        pass(Map.of());
        TestGate.INSTANCE.skipPing = true;
        Target target = new Target();
        assertEquals(-4, target.compute(-2));
        assertEquals("hello  bob ", target.greet(" bob "));
        target.ping();
        assertThrows(IllegalStateException.class, target::boom);
        assertEquals(List.of("compute body -2", "greet body  bob ", "ping body", "boom body"), Events.take());
        pass(Map.of("weave", List.of(Weaving.listings())));
    }

    @Test
    void closedGateSkipsItsHandlersButNotOthersOrAlways() {
        TestGate.INSTANCE.active = false;
        TestGate.INSTANCE.skipPing = true;
        Target target = new Target();
        assertEquals(7, target.compute(3));
        assertEquals(-3, target.compute(-2));
        target.ping();
        assertThrows(IllegalStateException.class, target::boom);
        assertEquals(List.of("compute body 3", "compute body -2", "ping body", "always afterPing", "boom body"),
                Events.take());
    }

    @Test
    void throwingHandlerReachesItsGateAndTheTargetRuns() {
        assertEquals(101, new Target().fail(1));
        assertEquals(List.of("fail body"), Events.take());
        assertEquals(List.of("running GatedHooks.throwing on com.pzlauncher.zabric.hook.weave.Target.fail:"
                + " java.lang.IllegalStateException: handler failed on 1"), TestGate.INSTANCE.failures);
        TestGate.INSTANCE.failures.clear();
    }

    @Test
    void constructorReturnSeesTheBuiltReceiver() {
        assertEquals("ann", new Shapes("ann").name);
        assertEquals(List.of("Shapes body ann", "created ann"), Events.take());
    }

    @Test
    void arrayArgumentReachesTheHandler() {
        Shapes shapes = new Shapes("s");
        Events.take();
        assertEquals(2, shapes.count(new String[] {"a", "b"}));
        assertEquals(List.of("beforeCount a,b", "count body 2"), Events.take());
    }

    @Test
    void finallyOnANonVoidTargetGetsTheValueOrTheThrowable() {
        Shapes shapes = new Shapes("s");
        Events.take();
        assertEquals(12, shapes.parse("12"));
        assertEquals(List.of("parse body 12", "parsed 12", "afterParse 12 null"), Events.take());
        assertThrows(NumberFormatException.class, () -> shapes.parse("x"));
        assertEquals(List.of("parse body x", "afterParse null NumberFormatException"), Events.take());
    }

    @Test
    void modifyVariableByIndexAndByOrdinal() {
        Shapes shapes = new Shapes("s");
        Events.take();
        assertEquals("a7B", shapes.pick(" a ", 7, "b"));
        assertEquals(List.of("pick body a 7 B"), Events.take());
    }

    @Test
    void exitPairsWithItsCallsEnter() {
        Target target = new Target();
        target.run(() -> TestGate.INSTANCE.active = false);
        assertEquals(List.of("beforeRun", "afterRun"), Events.take());
        target.run(() -> TestGate.INSTANCE.active = true);
        assertEquals(List.of(), Events.take());
    }
}
