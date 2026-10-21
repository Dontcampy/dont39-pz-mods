package com.pzlauncher.zabric.entrypoint;

import com.pzlauncher.zabric.entrypoint.LoaderProgress.Listener;
import com.pzlauncher.zabric.hook.ModHooks;
import com.pzlauncher.zabric.hook.ZabricHooks;
import com.pzlauncher.zabric.internal.Jars;
import com.pzlauncher.zabric.loader.Loader;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/** The {@link LoaderProgress} listeners and their calls. */
final class JarListeners {
    private record Registered(String modId, Listener listener) {
    }

    // registered at preLaunch, called on the loader's thread
    private final List<Registered> listeners = new CopyOnWriteArrayList<>();
    private volatile Throwable unavailable;
    // a mod id's jars as the loader recorded them, by canonical path
    private final Function<String, Set<Path>> recordedJars;

    JarListeners(Function<String, Set<Path>> recordedJars) {
        this.recordedJars = recordedJars;
    }

    void listen(String modId, Listener listener) {
        Objects.requireNonNull(modId, "modId");
        Objects.requireNonNull(listener, "listener");
        // a wrong id would tie the listener to another mod's hooks, or to none
        Path jar = jar(listener.getClass());
        Set<Path> recorded = recordedJars.apply(modId);
        if (!recorded.contains(jar)) {
            throw new IllegalArgumentException("cannot listen to the loader's progress for " + modId + " with "
                    + listener.getClass().getName() + ": it is from " + jar + ", not from a jar " + Loader.name()
                    + " recorded for " + modId + " (" + recorded + ")");
        }
        Registered registered = new Registered(modId, listener);
        Throwable cause = unavailable;
        if (cause != null) {
            fail(registered, cause);
            return;
        }
        listeners.add(registered);
    }

    /** The loader's hook, at each jar of a pass. */
    void jar(int index, int total, String modId, boolean loaded) {
        for (Registered r : listeners) {
            ModHooks mod = ZabricHooks.mod(r.modId());
            if (!mod.open()) {
                continue;
            }
            try {
                if (loaded) {
                    r.listener().jarLoaded(index, total, modId);
                } else {
                    r.listener().jarLoading(index, total, modId);
                }
            } catch (Throwable t) {
                listeners.remove(r);
                mod.failed(mod.defaultGate(), "running its loader progress listener " + r.listener().getClass()
                        .getName() + " at jar " + index + " / " + total + " (" + modId + ")", t);
            }
        }
    }

    /**
     * Zabric cannot follow the loader this session: removes every listener and stops its mod, and stops each mod that
     * registers later. Returns whether any listener heard it, so that the caller reports it otherwise. Call once the
     * game's log is up.
     */
    boolean unavailable(Throwable cause) {
        unavailable = cause;
        boolean heard = false;
        for (Registered r : listeners) {
            listeners.remove(r);
            fail(r, cause);
            heard = true;
        }
        return heard;
    }

    private static Path jar(Class<?> c) {
        try {
            return Jars.codeSource(c).toRealPath();
        } catch (URISyntaxException | IOException e) {
            throw new IllegalStateException("cannot tell the jar of " + c.getName() + ": " + e, e);
        }
    }

    private static void fail(Registered r, Throwable cause) {
        ModHooks mod = ZabricHooks.mod(r.modId());
        mod.failed(mod.defaultGate(), "following the loader's progress with " + r.listener().getClass().getName(),
                cause);
    }
}
