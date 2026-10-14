package com.pzlauncher.zabric.hook.impl;

import com.pzlauncher.zabric.hook.HookGate;
import com.pzlauncher.zabric.hook.ModHooks;
import com.pzlauncher.zabric.hook.ZabricHooks;
import com.pzlauncher.zabric.hook.weave.Target;
import com.pzlauncher.zabric.hook.weave.Weaving;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** When a mod's hooks are open, and what closes them. */
class HookRuntimeTest {
    private static final ClassLoader LOADER = HookRuntimeTest.class.getClassLoader();
    private static final String WHAT = "running MyHooks.onRender on zombie.ui.UITextBox2.render";
    private static final String PATCH = HookRuntimeTest.class.getName();

    @TempDir
    Path dir;

    private CapturedLog log;

    @BeforeEach
    void captureLog() {
        log = CapturedLog.install();
    }

    private static void pass(Map<String, List<Path>> jarsByMod, ClassLoader loader) {
        HookRuntime.passProcessed(jarsByMod, loader, Weaving.instrumentation());
    }

    @Test
    void openOnlyWhileInTheLatestPass() {
        ModHooks mod = ZabricHooks.mod("rt-pass");
        assertFalse(mod.open(), "before the first pass");
        pass(Map.of("rt-pass", List.of()), LOADER);
        assertTrue(mod.open());
        pass(Map.of("rt-other", List.of()), LOADER);
        assertFalse(mod.open(), "not in the pass");
        pass(Map.of("rt-pass", List.of()), LOADER);
        assertTrue(mod.open());
        HookRuntime.closeAll();
        assertFalse(mod.open(), "lifecycle stopped");
        assertEquals(List.of(), log.lines());
    }

    @Test
    void defaultGateFailureStopsTheModsHooksForTheSession() {
        ModHooks mod = ZabricHooks.mod("rt-fail");
        Gate gate = new Gate();
        pass(Map.of("rt-fail", List.of()), LOADER);
        assertTrue(mod.active(mod.defaultGate()));
        assertTrue(mod.active(gate));

        mod.failed(mod.defaultGate(), WHAT, new IllegalStateException("first"));
        assertFalse(mod.open());
        assertFalse(mod.active(gate), "named gates close with the mod");
        mod.failed(mod.defaultGate(), WHAT, new IllegalStateException("second"));
        pass(Map.of("rt-fail", List.of()), LOADER);
        assertFalse(mod.open(), "not reopened by a later pass");

        assertEquals(List.of(
                "error [Zabric] rt-fail: hooks stopped after an error while " + WHAT
                        + " | java.lang.IllegalStateException: first",
                "error [Zabric] rt-fail: hooks stopped; then an error while " + WHAT
                        + " | java.lang.IllegalStateException: second"), log.lines());
    }

    @Test
    void namedGateFailureGoesToTheGateOnly() {
        ModHooks mod = ZabricHooks.mod("rt-named");
        Gate gate = new Gate();
        pass(Map.of("rt-named", List.of()), LOADER);
        mod.failed(gate, WHAT, new IllegalStateException("x"));
        assertEquals(List.of(WHAT + ": java.lang.IllegalStateException: x"), gate.failures);
        assertTrue(mod.open());
        gate.active = false;
        assertFalse(mod.active(gate));
        assertTrue(mod.active(mod.defaultGate()));
        assertEquals(List.of(), log.lines());
    }

    @Test
    void aGateThatThrowsStopsTheMod() {
        ModHooks mod = ZabricHooks.mod("rt-throwing-gate");
        Gate gate = new Gate();
        gate.throwOnFailed = true;
        pass(Map.of("rt-throwing-gate", List.of()), LOADER);
        mod.failed(gate, WHAT, new IllegalStateException("x"));
        assertFalse(mod.open());
        assertEquals(List.of("error [Zabric] rt-throwing-gate: hooks stopped after an error while reporting an error"
                + " to " + Gate.class.getName() + " (" + WHAT + ") | java.lang.IllegalStateException: gate broke"),
                log.lines());
    }

    @Test
    void aGateWhoseActiveThrowsIsInactiveAndHearsWhy() {
        ModHooks mod = ZabricHooks.mod("rt-active-throws");
        Gate gate = new Gate();
        gate.throwOnActive = true;
        pass(Map.of("rt-active-throws", List.of()), LOADER);
        assertFalse(mod.active(gate));
        assertEquals(List.of("checking whether " + Gate.class.getName()
                + " is active: java.lang.IllegalStateException: active broke"), gate.failures);
        assertTrue(mod.open());
    }

    @Test
    void matchingTargetsOpenTheModAndReportReady() throws IOException {
        // the weave check runs on open mods: their targets must be woven
        Weaving.weaveReference();
        String target = Target.class.getName();
        Path jar = Listings.jar(dir, "ok.jar",
                "a.DefaultHooks", Listings.text("rt-ok", "default", Weaving.GLUE_PACKAGE + ".WeaveGlue$Target_compute",
                        target + " compute (I)I"),
                "a.MoreHooks", Listings.text("rt-ok", "default", Weaving.GLUE_PACKAGE + ".WeaveGlue$Target_greet",
                        target + " greet (Ljava/lang/String;)Ljava/lang/String;"),
                "a.NamedHooks", Listings.text("rt-ok", "a.Gate", PATCH, "no.such.Class m ()V"));
        pass(Map.of("rt-ok", List.of(jar)), LOADER);
        assertTrue(ZabricHooks.mod("rt-ok").open(), "named-gate hook classes are their gate's to check");
        assertEquals(List.of("info [Zabric] rt-ok: hooks ready (3 hook classes)"), log.lines());
    }

    @Test
    void aWeaveCheckThatCannotRunStopsTheModWithoutReady() throws IOException {
        Weaving.weaveReference();
        Path jar = Listings.jar(dir, "unchecked.jar", "a.DefaultHooks", Listings.text("rt-unchecked", "default",
                Weaving.GLUE_PACKAGE + ".WeaveGlue$Target_compute", Target.class.getName() + " compute (I)I"));
        HookRuntime.passProcessed(Map.of("rt-unchecked", List.of(jar)), LOADER, null);
        assertFalse(ZabricHooks.mod("rt-unchecked").open());
        List<String> lines = log.lines();
        assertEquals(1, lines.size(), "no ready line: " + lines);
        assertTrue(lines.get(0).startsWith("error [Zabric] rt-unchecked: hooks stopped: cannot check that ZombieBuddy"
                + " wove them | java.lang.NullPointerException"), lines.get(0));
    }

    @Test
    void aTargetMismatchStopsTheMod() throws IOException {
        Path jar = Listings.jar(dir, "bad.jar",
                "a.DefaultHooks", Listings.text("rt-mismatch", "default", "no.such.Patch",
                        "no.such.Target m ()V", "java.lang.String length ()J", "java.lang.String nope ()V"));
        pass(Map.of("rt-mismatch", List.of(jar)), LOADER);
        assertFalse(ZabricHooks.mod("rt-mismatch").open());
        String source = jar + "!/META-INF/zabric/hooks/a.DefaultHooks.txt";
        assertEquals(List.of("error [Zabric] rt-mismatch: hooks stopped: the targets of a.DefaultHooks (" + source
                + ") do not match: its patch class no.such.Patch is not there; class no.such.Target is not there;"
                + " java.lang.String has no length()J (it has length()I); java.lang.String has no method nope"),
                log.lines());
    }

    @Test
    void aListingForAnotherModStopsTheMod() throws IOException {
        Path jar = Listings.jar(dir, "other.jar",
                "a.Hooks", Listings.text("someone-else", "default", PATCH, "java.lang.String length ()I"));
        pass(Map.of("rt-claims", List.of(jar)), LOADER);
        assertFalse(ZabricHooks.mod("rt-claims").open());
        assertEquals(List.of("error [Zabric] rt-claims: hooks stopped: " + jar + "!/META-INF/zabric/hooks/a.Hooks.txt"
                + " names the mod someone-else, but ZombieBuddy loaded the jar for rt-claims"), log.lines());
    }

    @Test
    void anUnreadableListingStopsTheMod() throws IOException {
        Path jar = Listings.jar(dir, "broken.jar", "a.Hooks", "not a listing\n");
        pass(Map.of("rt-broken", List.of(jar)), LOADER);
        assertFalse(ZabricHooks.mod("rt-broken").open());
        assertEquals(List.of("error [Zabric] rt-broken: hooks stopped: cannot read the hook listings in " + jar
                + ": java.lang.IllegalArgumentException: " + jar + "!/META-INF/zabric/hooks/a.Hooks.txt: the first"
                + " line is not 'zabric-hooks v1'"), log.lines());
    }

    private static final class Gate implements HookGate {
        volatile boolean active = true;
        boolean throwOnActive;
        boolean throwOnFailed;
        final List<String> failures = new ArrayList<>();

        @Override
        public boolean active() {
            if (throwOnActive) {
                throw new IllegalStateException("active broke");
            }
            return active;
        }

        @Override
        public void failed(String what, Throwable t) {
            if (throwOnFailed) {
                throw new IllegalStateException("gate broke");
            }
            failures.add(what + ": " + t);
        }
    }
}
