package com.pzlauncher.zabric.entrypoint;

import com.pzlauncher.zabric.loader.Loader;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import static com.pzlauncher.zabric.internal.Log.PREFIX;

/** The entrypoint classes a mod declares in its jar manifest, and calling them. */
final class Entrypoints {
    record Declared(String preLaunch, String main) {
        static final Declared NONE = new Declared(null, null);
    }

    private Entrypoints() {
    }

    // a mod's declarations; none after logging why they cannot be read
    static Declared declared(String modId, List<Path> jars) {
        List<Declared> all = new ArrayList<>();
        for (Path jar : jars) {
            try {
                all.add(declared(jar));
            } catch (Throwable t) {
                DebugType.Mod.printException(t, PREFIX + "cannot read the entrypoints of " + modId + " from " + jar,
                        LogSeverity.Error);
                return Declared.NONE;
            }
        }
        if (all.stream().allMatch(Declared.NONE::equals)) {
            return Declared.NONE;
        }
        if (jars.size() > 1) {
            DebugType.Mod.error(PREFIX + "cannot tell the entrypoints of %s: %s recorded more than one loaded jar for"
                    + " it: %s", modId, Loader.name(), jars);
            return Declared.NONE;
        }
        return all.get(0);
    }

    static Declared declared(Path jar) throws IOException {
        try (JarFile jf = new JarFile(jar.toFile())) {
            Manifest manifest = jf.getManifest();
            if (manifest == null) {
                return Declared.NONE;
            }
            Attributes attributes = manifest.getMainAttributes();
            return new Declared(className(attributes, Lifecycle.PRE_LAUNCH), className(attributes, Lifecycle.MAIN));
        }
    }

    private static String className(Attributes attributes, String name) {
        String value = attributes.getValue(name);
        if (value != null && value.isBlank()) {
            throw new IllegalStateException("its manifest has an empty " + name);
        }
        return value == null ? null : value.strip();
    }

    // one failing entrypoint is its mod's problem; the others still run
    static <T> Throwable call(String className, Class<T> type, Consumer<T> body) {
        try {
            body.accept(instance(className, type));
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    // a Kotlin object's INSTANCE, otherwise a public no-arg constructor
    private static <T> T instance(String className, Class<T> type) throws ReflectiveOperationException {
        Class<?> c = Class.forName(className, true, ClassLoader.getSystemClassLoader());
        if (!type.isAssignableFrom(c)) {
            throw new ClassCastException(className + " does not implement " + type.getName());
        }
        Field instance;
        try {
            instance = c.getField("INSTANCE");
        } catch (NoSuchFieldException e) {
            instance = null;
        }
        if (instance != null && Modifier.isStatic(instance.getModifiers()) && instance.getType() == c) {
            return type.cast(instance.get(null));
        }
        return type.cast(c.getConstructor().newInstance());
    }
}
