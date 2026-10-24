package com.pzlauncher.zabric.loader;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.utility.JavaModule;
import org.jspecify.annotations.NonNull;

import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static net.bytebuddy.matcher.ElementMatchers.is;

/**
 * Advice on the entry of ZombieBuddy's {@code Loader.loadMods(List)} and around each {@code loadJar} of its MAIN pass,
 * which it calls in a loop once it has decided on every mod, and a transformer that counts the classes its thread
 * transforms or loads inside each jar. The pass's end ({@link #passEnded}) comes from Zabric's own hook on its exit.
 * On entry to every {@code loadJar}, of any phase, it also prepares the loaded classes the jar's patches name
 * ({@link PatchTargets}). Public only because the advice is inlined into that class and calls {@link #passStarting},
 * {@link #jarLoading} and {@link #jarLoaded}.
 */
public final class JarLoadHook {
    private static final String LOADER = "me.zed_0xff.zombie_buddy.Loader";

    private static volatile Loader.JarListener listener;
    private static volatile Object mainPhase;
    private static final AtomicReference<Throwable> escaped = new AtomicReference<>();
    // not a lost pass: the loader's weaving goes on as without Zabric
    private static final AtomicReference<Throwable> unprepared = new AtomicReference<>();
    private static final Counter COUNTER = new Counter();
    private static Instrumentation instrumentation;

    // the loader's thread: the records before the pass decided, then the pass's jars
    private static Set<Object> before;
    private static Map<Path, String> jars;
    private static int index;
    private static Path current;
    private static String currentMod;
    // after an error the rest of the pass is not followed: its counts are unknown
    private static boolean passFailed;

    private JarLoadHook() {
    }

    static void install(Instrumentation inst, Loader.JarListener onJar) {
        Class<?> loader = me.zed_0xff.zombie_buddy.Loader.class;
        Method loadMods;
        Method loadJar;
        Object main;
        try {
            loadMods = loader.getMethod("loadMods", List.class);
            Class<?> phase = Class.forName(LOADER + "$Phase", false, loader.getClassLoader());
            Object[] constants = phase.isEnum() ? phase.getEnumConstants() : new Object[0];
            main = Arrays.stream(constants)
                    .filter(c -> ((Enum<?>) c).name().equals("MAIN"))
                    .findFirst()
                    .orElseThrow(() -> new NoSuchFieldException(phase.getName() + " has no constant MAIN"));
            loadJar = loader.getDeclaredMethod("loadJar", Path.class, String.class, String.class, phase);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            throw new IllegalStateException(missing(), e);
        }
        if (!Modifier.isStatic(loadMods.getModifiers()) || !Modifier.isStatic(loadJar.getModifiers())
                || loadJar.getReturnType() != boolean.class) {
            throw new IllegalStateException(missing() + "; they are " + loadMods + " and " + loadJar);
        }
        listener = onJar;
        mainPhase = main;
        instrumentation = inst;
        AtomicBoolean transformed = new AtomicBoolean();
        AtomicReference<Throwable> error = new AtomicReference<>();
        // the class is loaded (ZombieBuddy runs this premain), hence retransformation
        new AgentBuilder.Default()
                .disableClassFormatChanges()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(AgentBuilder.RedefinitionStrategy.Listener.ErrorEscalating.FAIL_FAST)
                .with(new AgentBuilder.Listener.Adapter() {
                    @Override
                    public void onTransformation(@NonNull TypeDescription type, ClassLoader cl, JavaModule module,
                                                 boolean loaded, @NonNull DynamicType dynamicType) {
                        transformed.set(true);
                    }

                    @Override
                    public void onError(@NonNull String typeName, ClassLoader cl, JavaModule module, boolean loaded,
                                        @NonNull Throwable t) {
                        error.set(t);
                    }
                })
                .type(is(loader))
                .transform((builder, type, cl, module, domain) -> builder
                        .visit(Advice.to(PassEnter.class).on(is(loadMods)))
                        .visit(Advice.to(JarAdvice.class).on(is(loadJar))))
                .installOn(inst);
        if (error.get() != null) {
            throw new IllegalStateException("cannot hook " + loadJar + " of " + Loader.name() + " " + Loader.version(),
                    error.get());
        }
        if (!transformed.get()) {
            throw new IllegalStateException(loadJar + " of " + Loader.name() + " " + Loader.version()
                    + " was not hooked");
        }
    }

    /** The classes the loader's thread has transformed or loaded inside its jars so far; any thread. */
    static long heartbeat() {
        return COUNTER.count.get();
    }

    /** What went wrong since the last call, or null; the rest of that pass was not followed. */
    static Throwable takeEscaped() {
        return escaped.getAndSet(null);
    }

    /** Called by the advice. Never throws into loadMods: other mods' loading depends on it. */
    public static synchronized void passStarting() {
        stopCounting();
        jars = null;
        index = 0;
        current = null;
        currentMod = null;
        passFailed = false;
        try {
            before = LoadStatus.records();
        } catch (Throwable t) {
            failed(t);
        }
    }

    /** What preparing a jar's patch targets could not do since the last call, or null. */
    static Throwable takeUnprepared() {
        return unprepared.getAndSet(null);
    }

    /** Called by the advice. Never throws into loadJar. */
    public static void jarLoading(Path jar, String pkg, Object phase) {
        follow(jar, phase);
        prepare(jar, pkg);
    }

    private static void prepare(Path jar, String pkg) {
        try {
            // the loader appends mod jars to it and looks its targets up there
            PatchTargets.prepare(jar, pkg, instrumentation::getAllLoadedClasses, ClassLoader.getSystemClassLoader());
        } catch (Throwable t) {
            IllegalStateException e = new IllegalStateException("classes that first load while " + Loader.name()
                    + " weaves the patches of " + jar + " can miss access widening: " + t, t);
            Throwable first = unprepared.compareAndExchange(null, e);
            if (first != null) {
                first.addSuppressed(e);
            }
        }
    }

    private static synchronized void follow(Path jar, Object phase) {
        if (phase != mainPhase || passFailed) {
            return;
        }
        try {
            if (before == null) {
                throw new IllegalStateException(mismatch("loads " + jar + " outside its pass over the mod list"));
            }
            if (current != null) {
                throw new IllegalStateException(mismatch("loads " + jar + " before it returned from " + current));
            }
            if (jars == null) {
                jars = LoadStatus.loadedSince(before);
            }
            Path real;
            try {
                real = jar.toRealPath();
            } catch (IOException e) {
                // gone since the loader recorded it: the loader reports that it cannot load it
                real = jar.toAbsolutePath().normalize();
            }
            String modId = jars.get(real);
            if (modId == null) {
                throw new IllegalStateException(mismatch("loads " + real + " without a record that it loads it in"
                        + " this pass (it recorded " + jars.keySet() + ")"));
            }
            if (index == jars.size()) {
                throw new IllegalStateException(mismatch("loads " + real + " after the " + index + " jars it recorded"
                        + " for this pass"));
            }
            index++;
            current = jar;
            currentMod = modId;
            COUNTER.thread = Thread.currentThread();
            instrumentation.addTransformer(COUNTER, true);
            listener.jar(index, jars.size(), modId, false);
        } catch (Throwable t) {
            failed(t);
        }
    }

    /** Called by the advice when loadJar returns. Never throws into loadJar. */
    public static synchronized void jarLoaded(Path jar, Object phase) {
        if (phase != mainPhase || passFailed) {
            return;
        }
        try {
            if (!jar.equals(current)) {
                throw new IllegalStateException(mismatch("returned from loading " + jar + " while it was loading "
                        + current));
            }
            current = null;
            stopCounting();
            listener.jar(index, jars.size(), currentMod, true);
        } catch (Throwable t) {
            failed(t);
        }
    }

    /**
     * At the end of the loader's pass over the mod list: the pass must have loaded each jar it recorded for it. Never
     * throws into loadMods.
     */
    static synchronized void passEnded() {
        if (listener == null) {
            return;
        }
        try {
            if (passFailed) {
                return;
            }
            if (before == null) {
                throw new IllegalStateException(mismatch("returned from a pass over the mod list that Zabric did not see"
                        + " start"));
            }
            if (current != null) {
                throw new IllegalStateException(mismatch("returned from its pass over the mod list while loading "
                        + current));
            }
            if (jars == null) {
                jars = LoadStatus.loadedSince(before);
            }
            if (index != jars.size()) {
                throw new IllegalStateException(mismatch("returned from its pass after loading " + index + " of the "
                        + jars.size() + " jars it recorded (" + jars.keySet() + ")"));
            }
        } catch (Throwable t) {
            failed(t);
        } finally {
            // a jar loaded outside a pass is caught as such
            before = null;
            jars = null;
            index = 0;
            current = null;
            currentMod = null;
            passFailed = false;
            stopCounting();
        }
    }

    private static void failed(Throwable t) {
        passFailed = true;
        escaped.compareAndSet(null, t);
        stopCounting();
    }

    private static void stopCounting() {
        if (COUNTER.thread != null) {
            COUNTER.thread = null;
            instrumentation.removeTransformer(COUNTER);
        }
    }

    // only counts: a heartbeat for code on other threads while one jar's patching holds the loader's thread
    private static final class Counter implements ClassFileTransformer {
        final AtomicLong count = new AtomicLong();
        // other threads' class loads are not the loader's work
        volatile Thread thread;

        @Override
        public byte[] transform(Module module, ClassLoader loader, String className, Class<?> redefined,
                                ProtectionDomain domain, byte[] bytes) {
            if (Thread.currentThread() == thread) {
                count.incrementAndGet();
            }
            return null;
        }
    }

    private static String missing() {
        return Loader.name() + " " + Loader.version() + " has no static void loadMods(List) and static boolean"
                + " loadJar(Path, String, String, Phase) with a Phase MAIN in " + LOADER;
    }

    private static String mismatch(String what) {
        return Loader.name() + " " + Loader.version() + " " + what;
    }

    private static final class PassEnter {
        @Advice.OnMethodEnter(suppress = Throwable.class)
        static void enter() {
            passStarting();
        }
    }

    private static final class JarAdvice {
        @Advice.OnMethodEnter(suppress = Throwable.class)
        static void enter(@Advice.Argument(0) Path jar, @Advice.Argument(1) String pkg,
                          @Advice.Argument(3) Object phase) {
            jarLoading(jar, pkg, phase);
        }

        // also when loadJar throws, so that the listener hears the jar's end and the counting stops
        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        static void exit(@Advice.Argument(0) Path jar, @Advice.Argument(3) Object phase) {
            jarLoaded(jar, phase);
        }
    }
}
