package com.pzlauncher.zabric.hook.impl;

import com.pzlauncher.zabric.hook.HookGate;
import com.pzlauncher.zabric.hook.ModHooks;
import com.pzlauncher.zabric.hook.ZabricHooks;
import com.pzlauncher.zabric.hook.check.CheckGate;
import com.pzlauncher.zabric.hook.check.Loaded;
import com.pzlauncher.zabric.hook.check.NestedAnchor;
import com.pzlauncher.zabric.hook.check.NestedHits;
import com.pzlauncher.zabric.hook.check.PreLaunchHits;
import com.pzlauncher.zabric.hook.check.PreLoaded;
import com.pzlauncher.zabric.hook.weave.Weaving;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.implementation.FixedValue;
import net.bytebuddy.matcher.ElementMatchers;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fixtures in {@code hook/check}. */
class WeaveCheckTest {
    private static final ClassLoader LOADER = WeaveCheckTest.class.getClassLoader();
    private static final String CHECK = "com.pzlauncher.zabric.hook.check";
    private static final String PRE_LAUNCH_PATCH = CHECK + ".prelaunch.PreLaunchGlue$PreLaunchGood_twice";

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
        Weaving.weave(CHECK + ".prelaunch");
        Weaving.weave(CHECK + ".signature");
        new NestedAnchor().value(0);
        Weaving.weave(CHECK + ".nested");
        new PreLoaded().value(0);
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

    @Test
    void aModOpenedAtPreLaunchRunsItsHooksBeforeTheFirstPass() throws Exception {
        String good = CHECK + ".PreLaunchGood";
        Path jar = Listings.jar(dir, "pre.jar", "a.PreHooks", Listings.text("check-prelaunch", "default",
                PRE_LAUNCH_PATCH, good + " twice (I)I"));
        ModHooks mod = ZabricHooks.mod("check-prelaunch");
        try (URLClassLoader targets = targetLoader(good, "twice")) {
            HookRuntime.preLaunch(Map.of("check-prelaunch", List.of(jar)), Set.of(), targets,
                    Weaving.instrumentation());
            assertTrue(mod.open(), "before the first pass");
            Class<?> goodClass = targets.loadClass(good);
            int hits = PreLaunchHits.HITS.get();
            goodClass.getMethod("twice", int.class).invoke(goodClass.getConstructor().newInstance(), 1);
            assertEquals(hits + 1, PreLaunchHits.HITS.get(), "the target loaded after preLaunch runs the hook");
            try (OpenProbe probe = new OpenProbe(good, "check-prelaunch")) {
                HookRuntime.passProcessed(Map.of("check-prelaunch", List.of(jar)), targets,
                        Weaving.instrumentation());
                assertEquals(List.of(), probe.seen, "open since preLaunch, its target checked as it loaded: not held");
            }
        }
        assertTrue(mod.open());
        HookRuntime.releaseLog(log);
        assertEquals(List.of("info [Zabric] check-prelaunch: hooks ready (1 hook class)"), log.lines(),
                "once its target classes were found at the first pass");
        pass(Map.of());
        assertFalse(mod.open(), "a plain mod follows the mod list from the first pass");
    }

    @Test
    void aTargetMethodMissingWhenItsClassLoadsStopsTheModBeforeTheClassIsDefined() throws Exception {
        String missing = CHECK + ".PreLaunchMissing";
        Path jar = Listings.jar(dir, "missing.jar", "a.MissingHooks", Listings.text("check-pre-missing", "default",
                PRE_LAUNCH_PATCH, missing + " nope (I)I", missing + " count (J)J"));
        ModHooks mod = ZabricHooks.mod("check-pre-missing");
        try (URLClassLoader targets = targetLoader(missing, "count")) {
            HookRuntime.preLaunch(Map.of("check-pre-missing", List.of(jar)), Set.of("check-pre-missing"), targets,
                    Weaving.instrumentation());
            assertTrue(mod.open(), "before its target loads");
            try (OpenProbe probe = new OpenProbe(missing, "check-pre-missing")) {
                targets.loadClass(missing);
                assertEquals(List.of(false), probe.seen, "stopped before the class is defined");
            }
        }
        assertFalse(mod.open(), "launch-wide hooks stop too");
        HookRuntime.releaseLog(log);
        assertEquals(List.of("error [Zabric] check-pre-missing: hooks stopped after an error while checking the"
                        + " targets of MissingHooks in " + missing + " | java.lang.IllegalStateException: the targets of"
                        + " a.MissingHooks (" + jar + "!/META-INF/zabric/hooks/a.MissingHooks.txt) do not match: "
                        + missing + " has no method nope; " + missing + " has no count(J)J (it has count(I)I)"),
                log.lines());
    }

    @Test
    void aTargetLoadedBeforePreLaunchIsCheckedFromItsClassFile() throws IOException {
        String preLoaded = PreLoaded.class.getName();
        Path jar = Listings.jar(dir, "preloaded.jar", "a.PreLoadedHooks", Listings.text("check-pre-loaded",
                "default", PRE_LAUNCH_PATCH, preLoaded + " nope (I)I"));
        try (OpenProbe probe = new OpenProbe(preLoaded, "check-pre-loaded")) {
            HookRuntime.preLaunch(Map.of("check-pre-loaded", List.of(jar)), Set.of(), LOADER,
                    Weaving.instrumentation());
            assertEquals(List.of(), probe.seen, "not retransformed, which would load the types in its signatures");
        }
        assertFalse(ZabricHooks.mod("check-pre-loaded").open(), "never opened");
        HookRuntime.releaseLog(log);
        assertEquals(List.of("error [Zabric] check-pre-loaded: hooks stopped: the targets of a.PreLoadedHooks (" + jar
                + "!/META-INF/zabric/hooks/a.PreLoadedHooks.txt) do not match: " + preLoaded + " has no method nope"),
                log.lines());
    }

    @Test
    void aTargetLoadedBeforePreLaunchIsRetransformedAtTheFirstPassAfterItsSignatureTypes() throws Exception {
        String target = CHECK + ".SignatureTarget";
        String type = CHECK + ".SignatureType";
        Path jar = Listings.jar(dir, "signature.jar", "a.SignatureHooks", Listings.text("check-signature", "default",
                CHECK + ".signature.SignatureGlue$SignatureTarget_twice", target + " twice (I)I"));
        try (URLClassLoader targets = signatureLoader(target, type)) {
            targets.loadClass(target);
            HookRuntime.preLaunch(Map.of("check-signature", List.of(jar)), Set.of(), targets,
                    Weaving.instrumentation());
            assertFalse(loaded(type), "preLaunch loads no class the target names");
            try (OpenProbe probe = new OpenProbe(type, "check-signature")) {
                HookRuntime.passProcessed(Map.of("check-signature", List.of(jar)), targets,
                        Weaving.instrumentation());
                assertTrue(loaded(type), "the loader describes a retransformed class by reflection");
                assertEquals(1, probe.seen.size(), "loaded outside the loader's transformation, where transformers"
                        + " see it");
            }
        }
        HookRuntime.releaseLog(log);
        assertEquals(List.of("info [Zabric] check-signature: hooks ready (1 hook class)"), log.lines());
    }

    @Test
    void aTargetLoadedWhereNoTransformerRunsIsWovenAndCheckedAtTheNextPass() throws Exception {
        String target = CHECK + ".NestedTarget";
        String trigger = CHECK + ".NestedTrigger";
        Path jar = Listings.jar(dir, "nested.jar", "a.NestedHooks", Listings.text("check-nested", "default",
                CHECK + ".nested.NestedGlue$NestedTarget_twice", target + " twice (I)I"));
        try (URLClassLoader targets = targetLoader(target, "twice")) {
            // opened without loading its target
            HookRuntime.preLaunch(Map.of("check-nested", List.of(jar)), Set.of(), targets, Weaving.instrumentation());
            Class<?> targetClass = loadInsideATransformation(targets, trigger, target);
            Object instance = targetClass.getConstructor().newInstance();
            int hits = NestedHits.HITS.get();
            targetClass.getMethod("twice", int.class).invoke(instance, 1);
            assertEquals(hits, NestedHits.HITS.get(), "the loader did not see it load");

            HookRuntime.passProcessed(Map.of("check-nested", List.of(jar)), targets, Weaving.instrumentation());
            targetClass.getMethod("twice", int.class).invoke(instance, 1);
            assertEquals(hits + 1, NestedHits.HITS.get(), "woven at the pass");
        }
        assertTrue(ZabricHooks.mod("check-nested").open());
        HookRuntime.releaseLog(log);
        assertEquals(List.of("info [Zabric] check-nested: hooks ready (1 hook class)"), log.lines());
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

    // int method(int) in a class file under dir, so that the class is there before it loads
    private URLClassLoader targetLoader(String className, String method) throws IOException {
        byte[] bytes = new ByteBuddy().subclass(Object.class).name(className)
                .defineMethod(method, int.class, Visibility.PUBLIC).withParameters(int.class)
                .intercept(FixedValue.value(0))
                .make().getBytes();
        Path classes = dir.resolve("classes");
        Path file = classes.resolve(className.replace('.', '/') + ".class");
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
        return new URLClassLoader(new URL[] {classes.toUri().toURL()}, LOADER);
    }

    // as the loader's ByteBuddy loads the types of a class it retransforms
    private static Class<?> loadInsideATransformation(URLClassLoader targets, String trigger, String target)
            throws Exception {
        Path file = Path.of(targets.getURLs()[0].toURI()).resolve(trigger.replace('.', '/') + ".class");
        Files.write(file, new ByteBuddy().subclass(Object.class).name(trigger).make().getBytes());
        targets.loadClass(trigger);
        AtomicReference<Class<?>> loadedInside = new AtomicReference<>();
        ResettableClassFileTransformer other = new AgentBuilder.Default()
                .disableClassFormatChanges()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .type(ElementMatchers.named(trigger))
                .transform((builder, type, cl, module, domain) -> {
                    try {
                        loadedInside.set(Class.forName(target, false, cl));
                    } catch (ClassNotFoundException e) {
                        throw new IllegalStateException(e);
                    }
                    return builder;
                })
                .installOn(Weaving.instrumentation());
        other.reset(Weaving.instrumentation(), AgentBuilder.RedefinitionStrategy.DISABLED);
        assertNotNull(loadedInside.get(), target + " did not load inside the retransformation of " + trigger);
        return loadedInside.get();
    }

    // a target with int twice(int) and a method returning type
    private URLClassLoader signatureLoader(String target, String type) throws IOException {
        DynamicType.Unloaded<Object> typeClass = new ByteBuddy().subclass(Object.class).name(type).make();
        byte[] targetBytes = new ByteBuddy().subclass(Object.class).name(target)
                .defineMethod("twice", int.class, Visibility.PUBLIC).withParameters(int.class)
                .intercept(FixedValue.value(0))
                .defineMethod("type", typeClass.getTypeDescription(), Visibility.PUBLIC)
                .intercept(FixedValue.nullValue())
                .make().getBytes();
        Path classes = dir.resolve("classes");
        for (Map.Entry<String, byte[]> e : Map.of(target, targetBytes, type, typeClass.getBytes()).entrySet()) {
            Path file = classes.resolve(e.getKey().replace('.', '/') + ".class");
            Files.createDirectories(file.getParent());
            Files.write(file, e.getValue());
        }
        return new URLClassLoader(new URL[] {classes.toUri().toURL()}, LOADER);
    }

    private static boolean loaded(String className) {
        return Arrays.stream(Weaving.instrumentation().getAllLoadedClasses()).anyMatch(c -> c.getName()
                .equals(className));
    }
}
