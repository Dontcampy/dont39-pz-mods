package com.pzlauncher.zabric.loader;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * ZombieBuddy's record of its decision for each jar ({@code Loader.g_jarLoadStatus}), and the mod list it is loading.
 * Read by reflection: both are package-private and ZombieBuddy loads before Zabric, so Zabric cannot widen them.
 */
final class LoadStatus {
    private static final String LOADER = "me.zed_0xff.zombie_buddy.Loader";
    private static final String PRELOADED = "preloaded";
    private static final String LOADED = "loaded";

    // the accessors every caller needs; the hash is read apart, so that a change to it fails only its own callers
    private record Shape(Field status, Class<?> state, Method id, Method jarPath, Method reason) {
    }

    private static volatile Shape shape;
    private static volatile Method hashAccessor;

    private LoadStatus() {
    }

    static List<Loader.ModJar> preloaded() {
        return jars(PRELOADED, null);
    }

    static List<Loader.ModJar> loaded(Collection<String> pass) {
        return jars(LOADED, new HashSet<>(pass));
    }

    static Set<String> currentPass() {
        Object value;
        try {
            value = staticField("g_curr_modSet", Set.class).get(null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            throw new IllegalStateException(mismatch("g_curr_modSet", e.toString()), e);
        }
        if (!(value instanceof Set<?> set)) {
            throw new IllegalStateException(Loader.name() + " " + Loader.version() + " is not loading the mod list ("
                    + LOADER + ".g_curr_modSet is " + value + ")");
        }
        Set<String> ids = new HashSet<>();
        set.forEach(id -> ids.add(String.valueOf(id)));
        return ids;
    }

    /** The loader's records as they are now, to tell them from the ones a pass writes ({@link #loadedSince}). */
    static Set<Object> records() {
        Shape s = shape();
        Set<Object> records = Collections.newSetFromMap(new IdentityHashMap<>());
        try {
            records.addAll(statuses(s));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            throw new IllegalStateException(mismatch("g_jarLoadStatus", e.toString()), e);
        }
        return records;
    }

    /**
     * The jars recorded as loaded since {@code before} was taken, by the canonical path the loader keys them by, with
     * their mod ids: in a pass, the jars it loads once it has decided on every mod.
     */
    static Map<Path, String> loadedSince(Set<Object> before) {
        Map<Path, String> jars = new HashMap<>();
        records(LOADED, state -> !before.contains(state), jar -> jars.put(jar.jar(), jar.modId()));
        return jars;
    }

    /** The jars recorded as preloaded or loaded for {@code modId}, by the canonical path the loader keys them by. */
    static Set<Path> jarsOf(String modId) {
        Set<Path> jars = new HashSet<>();
        Consumer<Loader.ModJar> ofMod = jar -> {
            if (modId.equals(jar.modId())) {
                jars.add(jar.jar());
            }
        };
        records(PRELOADED, state -> true, ofMod);
        records(LOADED, state -> true, ofMod);
        return jars;
    }

    private static List<Loader.ModJar> jars(String wanted, Set<String> ids) {
        List<Loader.ModJar> jars = new ArrayList<>();
        records(wanted, state -> true, jar -> {
            if (ids == null || ids.contains(jar.modId())) {
                jars.add(jar);
            }
        });
        return jars;
    }

    private static void records(String wanted, Predicate<Object> which, Consumer<Loader.ModJar> found) {
        Shape s = shape();
        try {
            for (Object state : statuses(s)) {
                String id = (String) s.id().invoke(state);
                Path jar = (Path) s.jarPath().invoke(state);
                String reason = (String) s.reason().invoke(state);
                // skip reasons are "(...)"; anything else means the meaning of the field changed
                if (!PRELOADED.equals(reason) && !LOADED.equals(reason) && (reason == null || !reason.startsWith("("))) {
                    throw new NoSuchFieldException("the record of " + id + " has the reason '" + reason + "'");
                }
                if (wanted.equals(reason) && which.test(state)) {
                    found.accept(new Loader.ModJar(id, jar));
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            throw new IllegalStateException(mismatch("g_jarLoadStatus", e.toString()), e);
        }
    }

    private static Collection<?> statuses(Shape s) throws IllegalAccessException {
        return ((Map<?, ?>) s.status().get(null)).values();
    }

    static Map<Path, String> sha256() {
        Shape s = shape();
        Map<Path, String> hashes = new HashMap<>();
        try {
            Method hash = sha256Accessor(s);
            for (Object state : ((Map<?, ?>) s.status().get(null)).values()) {
                hashes.put((Path) s.jarPath().invoke(state), (String) hash.invoke(state));
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            throw new IllegalStateException(mismatch("g_jarLoadStatus", e.toString()), e);
        }
        return hashes;
    }

    private static Method sha256Accessor(Shape s) throws NoSuchFieldException {
        Method m = hashAccessor;
        if (m == null) {
            m = accessor(accessors(s.state()), "sha256", String.class);
            hashAccessor = m;
        }
        return m;
    }

    private static Shape shape() {
        Shape s = shape;
        if (s == null) {
            s = readShape();
            shape = s;
        }
        return s;
    }

    private static Shape readShape() {
        try {
            Field status = staticField("g_jarLoadStatus", Map.class);
            Class<?> state = Class.forName(LOADER + "$JavaModLoadState", false,
                    me.zed_0xff.zombie_buddy.Loader.class.getClassLoader());
            if (!state.isRecord()) {
                throw new NoSuchFieldException(state.getName() + " is not a record");
            }
            Map<String, Method> accessors = accessors(state);
            return new Shape(status, state, accessor(accessors, "id", String.class),
                    accessor(accessors, "jarPath", Path.class), accessor(accessors, "reason", String.class));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            throw new IllegalStateException(mismatch("g_jarLoadStatus", e.toString()), e);
        }
    }

    private static Map<String, Method> accessors(Class<?> state) {
        Map<String, Method> accessors = new HashMap<>();
        for (RecordComponent c : state.getRecordComponents()) {
            accessors.put(c.getName() + " " + c.getType().getName(), c.getAccessor());
        }
        return accessors;
    }

    private static Field staticField(String name, Class<?> type) throws NoSuchFieldException {
        Field f = me.zed_0xff.zombie_buddy.Loader.class.getDeclaredField(name);
        if (!Modifier.isStatic(f.getModifiers()) || !type.isAssignableFrom(f.getType())) {
            throw new NoSuchFieldException(name + " is " + f);
        }
        f.setAccessible(true);
        return f;
    }

    private static Method accessor(Map<String, Method> accessors, String name, Class<?> type)
            throws NoSuchFieldException {
        Method m = accessors.get(name + " " + type.getName());
        if (m == null) {
            throw new NoSuchFieldException("its records have no " + type.getSimpleName() + " " + name + " (they have "
                    + accessors.keySet() + ")");
        }
        // the record class is package-private
        m.setAccessible(true);
        return m;
    }

    private static String mismatch(String field, String detail) {
        return Loader.name() + " " + Loader.version() + " does not keep its mod records as Zabric expects (" + LOADER
                + "." + field + "): " + detail;
    }
}
