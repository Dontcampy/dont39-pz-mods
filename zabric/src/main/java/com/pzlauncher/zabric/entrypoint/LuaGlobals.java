package com.pzlauncher.zabric.entrypoint;

import com.pzlauncher.zabric.loader.Loader;
import se.krka.kahlua.integration.annotations.LuaMethod;
import se.krka.kahlua.integration.expose.AnnotationUtil;
import se.krka.kahlua.vm.KahluaTable;
import zombie.Lua.LuaManager;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static com.pzlauncher.zabric.internal.Log.PREFIX;

/**
 * Puts into the current Lua state the global Lua functions the loader left out of it. The loader exposes them only when
 * the game starts a Lua state, and the game starts one before it loads a new mod list (joining a server, the main
 * menu's mod reloads), so a jar first loaded there would have none.
 */
final class LuaGlobals {
    /** What one call exposed, and what it could not. */
    record Result(List<Class<?>> exposed, Map<Class<?>, Throwable> failed) {
    }

    private static final LuaGlobals session = new LuaGlobals();

    // reported once, not retried
    private final Set<Class<?>> failed = new HashSet<>();
    private boolean unavailable;

    /**
     * At the end of each of the loader's passes over the mod list, under {@link Lifecycle}'s lock, which also guards
     * this state. Never throws.
     */
    static void exposeMissing() {
        try {
            KahluaTable env = LuaManager.env;
            LuaManager.Exposer exposer = LuaManager.exposer;
            session.exposeMissing(env, exposer == null ? null : exposer::exposeGlobalFunctions,
                    Loader::globalLuaClasses);
        } catch (Throwable t) {
            session.giveUp(t);
        }
    }

    /**
     * {@link #exposeMissing()} on {@code env} through {@code exposer}, for the classes {@code source} lists; null
     * {@code env} or {@code exposer}: no Lua state yet. Returns what this pass exposed and newly failed.
     */
    Result exposeMissing(KahluaTable env, Consumer<Object> exposer, Supplier<Collection<Class<?>>> source) {
        // no Lua state at game start: the loader exposes them when the game starts Lua
        if (unavailable || env == null || exposer == null) {
            return new Result(List.of(), Map.of());
        }
        try {
            Result result = expose(env, exposer, source.get(), failed);
            for (Class<?> cls : result.exposed()) {
                DebugType.Mod.println(PREFIX + "Lua: exposed the global functions of %s, whose jar %s loaded after the"
                        + " game started Lua", cls.getName(), Loader.name());
            }
            result.failed().forEach((cls, t) -> {
                failed.add(cls);
                DebugType.Mod.printException(t, PREFIX + "Lua: cannot expose the global functions of " + cls.getName()
                        + "; Lua code that calls them fails", LogSeverity.Error);
            });
            return result;
        } catch (Throwable t) {
            giveUp(t);
            return new Result(List.of(), Map.of());
        }
    }

    private void giveUp(Throwable t) {
        if (unavailable) {
            return;
        }
        unavailable = true;
        DebugType.Mod.printException(t, PREFIX + "Lua: global functions of Java mods that load with a server's or"
                + " a reloaded mod list stay missing this session", LogSeverity.Error);
    }

    /** Whether a failure ended the exposure for this session. */
    boolean unavailable() {
        return unavailable;
    }

    /**
     * Exposes, through {@code exposer} (Kahlua's {@code exposeGlobalFunctions}), each class of {@code classes} not in
     * {@code skip} that lacks one of its global functions in {@code env}, on a new instance as the loader makes one.
     */
    static Result expose(KahluaTable env, Consumer<Object> exposer, Collection<Class<?>> classes,
                         Set<Class<?>> skip) {
        List<Class<?>> exposed = new ArrayList<>();
        Map<Class<?>, Throwable> errors = new LinkedHashMap<>();
        for (Class<?> cls : classes) {
            if (skip.contains(cls)) {
                continue;
            }
            try {
                // getMethods links the signatures' types, which one mod's jar may lack
                if (missing(env, cls).isEmpty()) {
                    continue;
                }
                exposer.accept(Loader.newGlobalLuaInstance(cls));
                // Kahlua skips classes it hides from Lua without a word; they would come back on every pass
                List<String> still = missing(env, cls);
                if (!still.isEmpty()) {
                    throw new IllegalStateException("Kahlua's exposer did not set " + still);
                }
                exposed.add(cls);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                errors.put(cls, e);
            }
        }
        return new Result(exposed, errors);
    }

    private static List<String> missing(KahluaTable env, Class<?> cls) {
        return globalNames(cls).stream().filter(name -> env.rawget(name) == null).toList();
    }

    /** The names Kahlua's {@code exposeGlobalFunctions} gives {@code cls}'s global functions. */
    static Set<String> globalNames(Class<?> cls) {
        Set<String> names = new LinkedHashSet<>();
        for (Method m : cls.getMethods()) {
            // as Kahlua reads it: also from the interfaces and superclasses the method overrides
            LuaMethod lua = AnnotationUtil.getAnnotation(m, LuaMethod.class);
            if (lua != null && lua.global()) {
                names.add(lua.name().isEmpty() ? m.getName() : lua.name());
            }
        }
        return names;
    }
}
