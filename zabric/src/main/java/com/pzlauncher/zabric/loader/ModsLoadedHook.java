package com.pzlauncher.zabric.loader;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.utility.JavaModule;
import org.jspecify.annotations.NonNull;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static net.bytebuddy.matcher.ElementMatchers.is;

/**
 * Advice on the exit of ZombieBuddy's {@code Loader.loadMods(List)}, its whole MAIN pass. Public only because the advice
 * is inlined into that class and calls {@link #modsLoaded}.
 */
public final class ModsLoadedHook {
    private static volatile Consumer<List<String>> callback;
    private static final AtomicReference<Throwable> escaped = new AtomicReference<>();

    private ModsLoadedHook() {
    }

    static void install(Instrumentation inst, Consumer<List<String>> onLoaded) {
        Method target;
        try {
            target = me.zed_0xff.zombie_buddy.Loader.class.getMethod("loadMods", List.class);
        } catch (NoSuchMethodException | RuntimeException | LinkageError e) {
            throw new IllegalStateException(missing(), e);
        }
        if (!Modifier.isStatic(target.getModifiers()) || target.getReturnType() != void.class) {
            throw new IllegalStateException(missing() + "; it is " + target);
        }
        callback = onLoaded;
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
                .type(is(me.zed_0xff.zombie_buddy.Loader.class))
                .transform((builder, type, cl, module, domain) -> builder.visit(Advice.to(Exit.class).on(is(target))))
                .installOn(inst);
        if (error.get() != null) {
            throw new IllegalStateException("cannot hook " + target + " of " + Loader.name() + " " + Loader.version(),
                    error.get());
        }
        if (!transformed.get()) {
            throw new IllegalStateException(target + " of " + Loader.name() + " " + Loader.version()
                    + " was not hooked");
        }
    }

    /** What the callback threw into the hook since the last call, or null; the callback reports its own errors. */
    static Throwable takeEscaped() {
        return escaped.getAndSet(null);
    }

    /** Called by the advice. Never throws into loadMods: other mods' loading depends on it. */
    public static void modsLoaded(List<String> mods) {
        try {
            callback.accept(mods);
        } catch (Throwable t) {
            escaped.set(t);
        }
    }

    private static String missing() {
        return Loader.name() + " " + Loader.version() + " has no static void loadMods(List) in "
                + me.zed_0xff.zombie_buddy.Loader.class.getName();
    }

    private static final class Exit {
        @Advice.OnMethodExit(suppress = Throwable.class)
        static void exit(@Advice.Argument(0) List<String> mods) {
            modsLoaded(mods);
        }
    }
}
