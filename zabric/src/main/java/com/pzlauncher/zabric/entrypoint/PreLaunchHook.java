package com.pzlauncher.zabric.entrypoint;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.utility.JavaModule;
import org.jspecify.annotations.NonNull;

import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

/** Advice on the entry of the game's main method, client and dedicated server, which calls {@link Lifecycle#preLaunch}. */
final class PreLaunchHook {
    /** The main classes of the client and the dedicated server ({@code ProjectZomboid64.json}). */
    static final List<String> TARGETS = List.of("zombie.gameStates.MainScreenState", "zombie.network.GameServer");

    private static volatile Throwable error;
    private static volatile String errorType;

    private PreLaunchHook() {
    }

    /** At premain: the targets must not be loaded yet, so that the advice is in place when the JVM loads one. */
    static void install(Instrumentation inst) {
        List<String> loaded = new ArrayList<>();
        Set<String> targets = Set.copyOf(TARGETS);
        for (Class<?> c : inst.getAllLoadedClasses()) {
            if (targets.contains(c.getName())) {
                loaded.add(c.getName());
            }
        }
        if (!loaded.isEmpty()) {
            throw new IllegalStateException("the game's main class was already loaded at game start: " + loaded);
        }
        // not retransforming, like the widener, so later retransformations keep the advice
        new AgentBuilder.Default()
                .with(AgentBuilder.RedefinitionStrategy.DISABLED)
                .with(AgentBuilder.TypeStrategy.Default.DECORATE)
                .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
                .with(new AgentBuilder.Listener.Adapter() {
                    @Override
                    public void onError(@NonNull String typeName, ClassLoader cl, JavaModule module,
                                        boolean isLoaded, @NonNull Throwable t) {
                        errorType = typeName;
                        error = t;
                    }
                })
                .type(namedOneOf(TARGETS.toArray(new String[0])))
                .transform((builder, type, cl, module, domain) -> builder.visit(Advice.to(Enter.class)
                        .on(named("main").and(isStatic()).and(takesArguments(String[].class)))))
                .installOn(inst);
    }

    /** Why the advice could not be added to the class that loaded, or null. */
    static String problem() {
        Throwable t = error;
        return t == null ? null : "cannot hook " + errorType + ".main: " + t;
    }

    static Throwable error() {
        return error;
    }

    private static final class Enter {
        @Advice.OnMethodEnter(suppress = Throwable.class)
        static void enter() {
            Lifecycle.preLaunch();
        }
    }
}
