package com.pzlauncher.zabric.mods.impl;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.utility.JavaModule;
import org.jspecify.annotations.NonNull;

import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

/**
 * Advice on the return of the game's {@code getInstalledItemModsFolders(List)}, which adds each installed Workshop
 * item's {@code <item>/mods} folder: it puts the requested folder in that item's place. Public only because the advice
 * is inlined into the game's class and calls {@link #start} and {@link #replace}.
 */
public final class ItemFoldersHook {
    static final String METHOD = "getInstalledItemModsFolders";
    private static final String MODS = "mods";
    private static final ElementMatcher.Junction<MethodDescription> TARGET = named(METHOD).and(not(isStatic()))
            .and(takesArguments(List.class)).and(returns(void.class));

    private static volatile Map<String, String> folders = Map.of();
    private static final AtomicInteger calls = new AtomicInteger();

    private ItemFoldersHook() {
    }

    /**
     * At premain, once per JVM. The game's class is already loaded then (the loader patches it first), hence
     * retransformation: the transformer stays registered, so it adds the advice again whenever another hook on the
     * class retransforms it, beneath those added later. Describes the class from its bytes, so that no game class is
     * loaded; every other class is told apart by name only, since runtime classes such as proxies have no bytes to
     * describe. Throws when the class is loaded and could not be hooked; a later failure goes to
     * {@link LaunchOverrides#fail}.
     */
    static void install(Instrumentation inst, String target, Map<String, Path> request) {
        Map<String, String> byItem = new HashMap<>();
        request.forEach((id, folder) -> byItem.put(id, folder.toString()));
        folders = Map.copyOf(byItem);
        AtomicBoolean transformed = new AtomicBoolean();
        Set<Class<?>> loaded = new HashSet<>();
        for (Class<?> c : inst.getAllLoadedClasses()) {
            if (c.getName().equals(target)) {
                loaded.add(c);
            }
        }
        new AgentBuilder.Default()
                .disableClassFormatChanges()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(new AgentBuilder.RedefinitionStrategy.DiscoveryStrategy.Explicit(loaded))
                .with(AgentBuilder.RedefinitionStrategy.Listener.ErrorEscalating.FAIL_FAST)
                .with(AgentBuilder.DescriptionStrategy.Default.POOL_ONLY)
                .ignore(not(named(target)))
                .with(new AgentBuilder.Listener.Adapter() {
                    @Override
                    public void onTransformation(@NonNull TypeDescription type, ClassLoader cl, JavaModule module,
                                                 boolean isLoaded, @NonNull DynamicType dynamicType) {
                        transformed.set(true);
                    }

                    @Override
                    public void onError(@NonNull String typeName, ClassLoader cl, JavaModule module, boolean isLoaded,
                                        @NonNull Throwable t) {
                        // without the advice the class runs the game's own folder list again
                        if (typeName.equals(target)) {
                            LaunchOverrides.fail(new IllegalStateException("cannot hook " + typeName + "." + METHOD,
                                    t));
                        }
                    }
                })
                .type(named(target))
                .transform((builder, type, cl, module, domain) -> {
                    if (type.getDeclaredMethods().filter(TARGET).size() != 1) {
                        throw new IllegalStateException(target + " has no void " + METHOD + "(java.util.List)");
                    }
                    return builder.visit(Advice.to(Folders.class).on(TARGET));
                })
                .installOn(inst);
        if (!loaded.isEmpty() && !transformed.get()) {
            throw new IllegalStateException(target + "." + METHOD + " was not hooked");
        }
    }

    /** How often the game listed its installed items' folders. */
    static int calls() {
        return calls.get();
    }

    /** Called by the advice on entry: where this call starts adding to the list. */
    public static int start(List<String> list) {
        return list.size();
    }

    /** Called by the advice on return. Never throws into the game, which lists mod folders on several threads. */
    public static void replace(List<String> list, int from) {
        try {
            calls.incrementAndGet();
            if (LaunchOverrides.failed()) {
                return;
            }
            Map<String, String> byItem = folders;
            for (int i = from; i < list.size(); i++) {
                // the game adds <item folder>/mods, the item folder named by its id
                Path added = Path.of(list.get(i)).normalize();
                Path item = added.getParent();
                if (item == null || item.getFileName() == null || !MODS.equals(String.valueOf(added.getFileName()))) {
                    continue;
                }
                String id = item.getFileName().toString();
                String folder = byItem.get(id);
                if (folder != null) {
                    list.set(i, folder);
                    LaunchOverrides.listed(id);
                }
            }
        } catch (Throwable t) {
            LaunchOverrides.fail(t);
        }
    }

    private static final class Folders {
        @Advice.OnMethodEnter(suppress = Throwable.class)
        static int enter(@Advice.Argument(0) List<String> list) {
            return start(list);
        }

        @Advice.OnMethodExit(suppress = Throwable.class)
        static void exit(@Advice.Argument(0) List<String> list, @Advice.Enter int from) {
            replace(list, from);
        }
    }
}
