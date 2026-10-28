package com.pzlauncher.zabric.entrypoint;

import com.pzlauncher.zabric.loader.Loader;
import se.krka.kahlua.integration.annotations.LuaMethod;
import se.krka.kahlua.integration.expose.AnnotationUtil;
import se.krka.kahlua.vm.KahluaTable;
import zombie.Lua.LuaManager;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;

import java.lang.reflect.Constructor;
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

    // reported once, not retried
    private static final Set<Class<?>> failed = new HashSet<>();
    private static boolean unavailable;

    private LuaGlobals() {
    }

    /** At the end of each of the loader's passes over the mod list. Never throws. */
    static synchronized void exposeMissing() {
        if (unavailable) {
            return;
        }
        try {
            KahluaTable env = LuaManager.env;
            LuaManager.Exposer exposer = LuaManager.exposer;
            // game start: the loader exposes them when the game starts Lua
            if (env == null || exposer == null) {
                return;
            }
            Result result = expose(env, exposer::exposeGlobalFunctions, Loader.globalLuaClasses(), failed);
            for (Class<?> cls : result.exposed()) {
                DebugType.Mod.println(PREFIX + "Lua: exposed the global functions of %s, whose jar %s loaded after the"
                        + " game started Lua", cls.getName(), Loader.name());
            }
            result.failed().forEach((cls, t) -> {
                failed.add(cls);
                DebugType.Mod.printException(t, PREFIX + "Lua: cannot expose the global functions of " + cls.getName()
                        + "; Lua code that calls them fails", LogSeverity.Error);
            });
        } catch (Throwable t) {
            unavailable = true;
            DebugType.Mod.printException(t, PREFIX + "Lua: global functions of Java mods that load with a server's or"
                    + " a reloaded mod list stay missing this session: " + t, LogSeverity.Error);
        }
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
                Constructor<?> constructor = cls.getDeclaredConstructor();
                constructor.setAccessible(true);
                exposer.accept(constructor.newInstance());
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
