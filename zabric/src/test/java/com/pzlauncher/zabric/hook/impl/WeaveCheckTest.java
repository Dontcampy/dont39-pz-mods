package com.pzlauncher.zabric.hook.impl;

import com.pzlauncher.zabric.hook.HookGate;
import com.pzlauncher.zabric.hook.ModHooks;
import com.pzlauncher.zabric.hook.ZabricHooks;
import com.pzlauncher.zabric.hook.check.CheckGate;
import com.pzlauncher.zabric.hook.check.Loaded;
import com.pzlauncher.zabric.hook.weave.Weaving;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.implementation.FixedValue;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fixtures in {@code hook/check}. */
class WeaveCheckTest {
    private static final ClassLoader LOADER = WeaveCheckTest.class.getClassLoader();
    private static final String CHECK = "com.pzlauncher.zabric.hook.check";

    @TempDir
    Path dir;

    private static Throwable loadedWeaveError;

    private CapturedLog log;

    @BeforeAll
    static void weave() {
        Weaving.instrumentation();
        new Loaded().value(0);
        try {
            Weaving.weave(CHECK + ".loaded");
        } catch (Throwable t) {
            loadedWeaveError = t;
        }
        Weaving.weave(CHECK + ".rejected");
        Weaving.weave(CHECK + ".late");
    }

    @BeforeEach
    void captureLog() {
        log = CapturedLog.install();
    }

    private static void pass(Map<String, List<Path>> jarsByMod) {
        HookRuntime.passProcessed(jarsByMod, LOADER, Weaving.instrumentation());
    }

    @Test
    void anUnverifiablePatchOnALoadedTargetStopsItsMod() throws IOException {
        Path jar = Listings.jar(dir, "loaded.jar", "a.LoadedHooks", Listings.text("check-loaded", "default",
                CHECK + ".loaded.LoadedGlue$Loaded_value", CHECK + ".Loaded value (I)I"));
        try (OpenProbe probe = new OpenProbe(Loaded.class.getName(), "check-loaded")) {
            pass(Map.of("check-loaded", List.of(jar)));
            assertEquals(List.of(false), probe.seen, "closed while its targets are checked");
        }
        assertFalse(ZabricHooks.mod("check-loaded").open(), "never opened");
        List<String> lines = log.lines();
        assertEquals(1, lines.size(), "no ready line: " + lines);
        assertEquals("error [Zabric] check-loaded: hooks stopped after an error while weaving LoadedHooks into " + CHECK
                + ".Loaded.value | java.lang.VerifyError", lines.get(0));
        assertEquals(2, new Loaded().value(1), "the target still runs, without the patch");
        // what ZombieBuddy does with it: its own retransformation throws it out of its patch engine
        assertInstanceOf(VerifyError.class, loadedWeaveError);
    }

    @Test
    void adviceByteBuddyRejectsStopsItsMod() throws IOException {
        Path jar = Listings.jar(dir, "rejected.jar", "a.RejectedHooks", Listings.text("check-rejected", "default",
                CHECK + ".rejected.RejectedGlue$Rejected_name", CHECK + ".Rejected name ()Ljava/lang/String;"));
        try (OpenProbe probe = new OpenProbe(CHECK + ".Rejected", "check-rejected")) {
            pass(Map.of("check-rejected", List.of(jar)));
            assertEquals(List.of(false), probe.seen, "closed while its targets are checked");
        }
        assertFalse(ZabricHooks.mod("check-rejected").open(), "never opened");
        assertEquals(List.of("error [Zabric] check-rejected: hooks stopped after an error while weaving RejectedHooks"
                        + " into " + CHECK + ".Rejected.name | java.lang.IllegalStateException: " + CHECK
                        + ".Rejected.name()Ljava/lang/String; runs without " + CHECK
                        + ".rejected.RejectedGlue$Rejected_name: ZombieBuddy did not weave it (its log may say why)"),
                log.lines());
    }

    @Test
    void aNamedGatesWeaveFailureAtThePassComesBeforeOpenAndReady() throws IOException {
        Path jar = Listings.jar(dir, "named.jar", "a.RejectedHooks", Listings.text("check-named",
                LoggingGate.class.getName(), CHECK + ".rejected.RejectedGlue$Rejected_name",
                CHECK + ".Rejected name ()Ljava/lang/String;"));
        ModHooks mod = ZabricHooks.mod("check-named");
        List<Boolean> openAtLines = noteOpen(mod);
        try (OpenProbe probe = new OpenProbe(CHECK + ".Rejected", "check-named")) {
            pass(Map.of("check-named", List.of(jar)));
            assertEquals(List.of(false), probe.seen, "closed while its targets are checked");
        }
        assertTrue(mod.open(), "a named gate's failure is its own");
        assertEquals(List.of("error gate: weaving RejectedHooks into " + CHECK + ".Rejected.name",
                "info [Zabric] check-named: hooks ready (1 hook class)"), log.lines());
        assertEquals(List.of(false, true), openAtLines, "weave check, then open, then ready");
    }

    @Test
    void targetsLoadedAfterThePassAreCheckedAsTheyLoad() throws Exception {
        // defined here: Gradle's test scan loads every class of the test output before any test runs
        String good = CHECK + ".LateGood";
        String missing = CHECK + ".LateMissing";
        assertFalse(loaded(good) || loaded(missing), "the targets must load after the pass");
        Path jar = Listings.jar(dir, "late.jar",
                "a.LateGoodHooks", Listings.text("check-late", CheckGate.class.getName(),
                        CHECK + ".late.LateGlue$LateGood_twice", good + " twice (I)I"),
                "a.LateMissingHooks", Listings.text("check-late", CheckGate.class.getName(),
                        CHECK + ".late.LateGlue$LateMissing_count", missing + " count (I)I"));
        pass(Map.of("check-late", List.of(jar)));
        assertEquals(List.of("info [Zabric] check-late: hooks ready (2 hook classes)"), log.lines());
        assertEquals(List.of(), CheckGate.INSTANCE.failures);

        Class<?> goodClass = define(good, "twice");
        define(missing, "count");
        List<String> failures = List.of("weaving LateMissingHooks into " + missing + ".count:"
                + " java.lang.IllegalStateException: " + missing + ".count(I)I runs without " + CHECK
                + ".late.LateGlue$LateMissing_count: ZombieBuddy did not weave it (its log may say why)");
        assertEquals(failures, CheckGate.INSTANCE.failures);
        assertTrue(ZabricHooks.mod("check-late").open(), "a named gate's failure is its own");
        int checks = CheckGate.INSTANCE.checks.get();
        goodClass.getMethod("twice", int.class).invoke(goodClass.getConstructor().newInstance(), 1);
        assertEquals(checks + 1, CheckGate.INSTANCE.checks.get(), "the unreported target runs its advice");

        // woven when it loaded, retransformed when the mod enters a pass again: reported only once
        pass(Map.of());
        try (OpenProbe probe = new OpenProbe(good, "check-late")) {
            pass(Map.of("check-late", List.of(jar)));
            assertEquals(List.of(false), probe.seen, "closed again while its targets are checked");
        }
        assertTrue(ZabricHooks.mod("check-late").open());
        assertEquals(failures, CheckGate.INSTANCE.failures);
        assertEquals(List.of("info [Zabric] check-late: hooks ready (2 hook classes)"), log.lines());
    }

    // shows the order of gate failures among the runtime's lines
    public static final class LoggingGate implements HookGate {
        public static final LoggingGate INSTANCE = new LoggingGate();

        @Override
        public boolean active() {
            return true;
        }

        @Override
        public void failed(String what, Throwable t) {
            HookRuntime.log().error("gate: %s", what);
        }
    }

    private List<Boolean> noteOpen(ModHooks mod) {
        List<Boolean> openAtLines = new ArrayList<>();
        HookRuntime.logTo(new HookRuntime.Log() {
            @Override
            public void info(String format, Object... args) {
                openAtLines.add(mod.open());
                log.info(format, args);
            }

            @Override
            public void error(String format, Object... args) {
                openAtLines.add(mod.open());
                log.error(format, args);
            }

            @Override
            public void exception(Throwable t, String message) {
                openAtLines.add(mod.open());
                log.exception(t, message);
            }
        });
        return openAtLines;
    }

    private static final class OpenProbe implements ClassFileTransformer, AutoCloseable {
        private final String internalName;
        private final ModHooks mod;
        final List<Boolean> seen = new CopyOnWriteArrayList<>();

        OpenProbe(String className, String modId) {
            internalName = className.replace('.', '/');
            mod = ZabricHooks.mod(modId);
            Weaving.instrumentation().addTransformer(this, true);
        }

        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                ProtectionDomain protectionDomain, byte[] classfileBuffer) {
            if (internalName.equals(className)) {
                seen.add(mod.open());
            }
            return null;
        }

        @Override
        public void close() {
            Weaving.instrumentation().removeTransformer(this);
        }
    }

    // int method(int), in a child of the test's class loader
    private static Class<?> define(String className, String method) {
        return new ByteBuddy().subclass(Object.class).name(className)
                .defineMethod(method, int.class, Visibility.PUBLIC).withParameters(int.class)
                .intercept(FixedValue.value(0))
                .make().load(LOADER, ClassLoadingStrategy.Default.WRAPPER).getLoaded();
    }

    private static boolean loaded(String className) {
        return Arrays.stream(Weaving.instrumentation().getAllLoadedClasses()).anyMatch(c -> c.getName()
                .equals(className));
    }
}
