package com.dont39.pz.zbskiponlinechecks;

import me.zed_0xff.zombie_buddy.KnownAuthors;
import me.zed_0xff.zombie_buddy.SteamWorkshop;
import me.zed_0xff.zombie_buddy.ZBSVerifier;
import me.zed_0xff.zombie_buddy.ZombieBuddy;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.utility.JavaModule;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static net.bytebuddy.matcher.ElementMatchers.is;

/**
 * Puts advice on ZombieBuddy's three network requests that skips them. Installs once per JVM, from whichever way runs
 * first; both copies of the jar resolve to one class, so one state serves both ways. Uses no game class.
 */
public final class Installer {
    /** How the advice was installed. */
    public enum Way {
        AGENT_START("agent start (ZombieBuddy's patches_jar option)"),
        MOD_ONLY("mod only (Zabric preLaunch)");

        private final String description;

        Way(String description) {
            this.description = description;
        }

        public String description() {
            return description;
        }
    }

    private record Target(Method method, Class<?> advice) {
    }

    private static Way way;
    private static RuntimeException failure;

    private Installer() {
    }

    /** Throws naming the ZombieBuddy method that does not match; a failed install is final, later calls rethrow it. */
    public static synchronized void install(Way caller) {
        if (failure != null) {
            throw failure;
        }
        if (way != null) {
            return;
        }
        try {
            advise();
        } catch (RuntimeException e) {
            failure = e;
            throw e;
        } catch (LinkageError e) {
            failure = new IllegalStateException("ZombieBuddy " + zbVersion() + " does not have the methods "
                    + "ZBSkipOnlineChecks skips", e);
            throw failure;
        }
        way = caller;
    }

    /** Records a failure found before the install could run; it is final like a failed install. */
    public static synchronized void fail(RuntimeException e) {
        if (failure == null && way == null) {
            failure = e;
        }
    }

    /** How the advice was installed, or null when it is not. */
    public static synchronized Way way() {
        return way;
    }

    /** Why the install failed, or null. */
    public static synchronized RuntimeException failure() {
        return failure;
    }

    public static String zbVersion() {
        try {
            return ZombieBuddy.getVersion();
        } catch (RuntimeException | LinkageError e) {
            return "(unknown version)";
        }
    }

    private static void advise() {
        List<Target> targets = List.of(
                target(SteamWorkshop.class, "fetchItemDetails", Map.class, SkipBanStatus.class, Set.class),
                target(KnownAuthors.class, "loadAuthors", Map.class, SkipAuthors.class),
                target(ZBSVerifier.class, "fetchJavaModZBSHexesFromSteam", List.class, SkipProfileKeys.class,
                        SteamWorkshop.SteamID64.class));
        Set<String> transformed = ConcurrentHashMap.newKeySet();
        AtomicReference<String> errorType = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        // the class literals above loaded the targets, hence retransformation
        AgentBuilder builder = new AgentBuilder.Default()
                .disableClassFormatChanges()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(AgentBuilder.RedefinitionStrategy.Listener.ErrorEscalating.FAIL_FAST)
                .with(new AgentBuilder.Listener.Adapter() {
                    @Override
                    public void onTransformation(TypeDescription type, ClassLoader cl, JavaModule module,
                                                 boolean loaded, DynamicType dynamicType) {
                        transformed.add(type.getName());
                    }

                    @Override
                    public void onError(String typeName, ClassLoader cl, JavaModule module, boolean loaded,
                                        Throwable t) {
                        errorType.compareAndSet(null, typeName);
                        error.compareAndSet(null, t);
                    }
                });
        for (Target t : targets) {
            builder = builder.type(is(t.method().getDeclaringClass()))
                    .transform((b, type, cl, module, domain) -> b.visit(Advice.to(t.advice()).on(is(t.method()))));
        }
        Instrumentation inst = instrumentation();
        ResettableClassFileTransformer transformer;
        try {
            transformer = builder.installOn(inst);
        } catch (RuntimeException e) {
            throw new IllegalStateException("cannot hook the online checks of ZombieBuddy " + zbVersion(), e);
        }
        IllegalStateException problem = null;
        if (error.get() != null) {
            problem = new IllegalStateException("cannot hook " + errorType.get() + " of ZombieBuddy " + zbVersion(),
                    error.get());
        }
        for (Target t : targets) {
            if (problem == null && !transformed.contains(t.method().getDeclaringClass().getName())) {
                problem = new IllegalStateException(t.method() + " of ZombieBuddy " + zbVersion() + " was not hooked");
            }
        }
        if (problem != null) {
            // the targets hooked so far would keep skipping, while the error says the checks run as before
            try {
                transformer.reset(inst, AgentBuilder.RedefinitionStrategy.RETRANSFORMATION);
            } catch (RuntimeException e) {
                problem.addSuppressed(e);
            }
            throw problem;
        }
    }

    private static Target target(Class<?> type, String name, Class<?> returns, Class<?> advice, Class<?>... params) {
        String wanted = "ZombieBuddy " + zbVersion() + " has no static " + returns.getSimpleName() + " " + name + "("
                + Arrays.stream(params).map(Class::getSimpleName).collect(Collectors.joining(", ")) + ") in "
                + type.getName();
        Method method;
        try {
            method = type.getDeclaredMethod(name, params);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(wanted, e);
        }
        if (!Modifier.isStatic(method.getModifiers()) || method.getReturnType() != returns) {
            throw new IllegalStateException(wanted + "; it is " + method);
        }
        return new Target(method, advice);
    }

    private static Instrumentation instrumentation() {
        Object value;
        try {
            // ZombieBuddy keeps it package-private, and at agent start Zabric, which reads it the same way, is not
            // loaded yet; the game JRE has no jdk.attach to get another one
            Field f = me.zed_0xff.zombie_buddy.Loader.class.getDeclaredField("g_instrumentation");
            f.setAccessible(true);
            value = f.get(null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalStateException(notExposed(), e);
        }
        if (!(value instanceof Instrumentation inst)) {
            throw new IllegalStateException(notExposed() + "; it is " + value);
        }
        return inst;
    }

    private static String notExposed() {
        return "ZombieBuddy " + zbVersion() + " does not expose its Instrumentation"
                + " (me.zed_0xff.zombie_buddy.Loader.g_instrumentation)";
    }

    private static final class SkipBanStatus {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        static boolean enter() {
            Skips.count(Skips.Target.BAN_STATUS);
            return true;
        }

        // mutable, like the skipped body's result; every jar's ban status stays unknown
        @Advice.OnMethodExit
        static void exit(@Advice.Return(readOnly = false) Map<?, ?> details) {
            details = new HashMap<>();
        }
    }

    private static final class SkipAuthors {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        static boolean enter() {
            Skips.count(Skips.Target.AUTHORS);
            return true;
        }

        // what ZombieBuddy returns itself when neither the network nor its cache has a list
        @Advice.OnMethodExit
        static void exit(@Advice.Return(readOnly = false) Map<?, ?> authors) {
            authors = Collections.emptyMap();
        }
    }

    private static final class SkipProfileKeys {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        static boolean enter() {
            Skips.count(Skips.Target.PROFILE_KEYS);
            return true;
        }

        // ZombieBuddy's caller treats it as a failed lookup
        @Advice.OnMethodExit(onThrowable = Throwable.class)
        static void exit(@Advice.Thrown(readOnly = false) Throwable thrown) {
            thrown = new IOException("skipped by ZBSkipOnlineChecks");
        }
    }
}
