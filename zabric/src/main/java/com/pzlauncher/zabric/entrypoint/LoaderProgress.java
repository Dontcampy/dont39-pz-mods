package com.pzlauncher.zabric.entrypoint;

import com.pzlauncher.zabric.hook.ModHooks;
import com.pzlauncher.zabric.loader.Loader;

/**
 * The loader's progress through the Java mod jars of each pass over the mod list, for mods that show loading progress.
 * No game method runs between those jars, so a hook cannot follow them.
 * <p>
 * A listener hears each jar from its registration on, while its mod's hooks are open ({@link ModHooks#open()}), on the
 * loader's thread (the game's main thread), outside any class transformation. A mod Zabric keeps preloaded registers
 * from its {@link PreLaunchEntrypoint} to hear the launch's first pass; registering loads no game class. A listener
 * that throws is removed and stops its mod's hooks with the error reported, like a hook's error; the loader goes on.
 * When Zabric cannot follow the loader's passes this session, every listener is removed that way.
 */
public final class LoaderProgress {
    static final JarListeners LISTENERS = new JarListeners(Loader::recordedJars);

    private LoaderProgress() {
    }

    /** Hears each Java mod jar of a pass. Arguments are JDK types only. */
    @FunctionalInterface
    public interface Listener {
        /**
         * The loader is about to load jar {@code index} (from 1) of the {@code total} it loads in this pass, for
         * {@code modId}. Those are the jars it approved for the pass's mod list and its preloaded mods; on a later
         * pass, a jar it loaded before returns at once.
         */
        void jarLoading(int index, int total, String modId);

        /** The loader returned from loading that jar, whether it could load it or not (it reports that itself). */
        default void jarLoaded(int index, int total, String modId) {
        }
    }

    /**
     * Adds {@code listener} for {@code modId}, the mod it reports against, whose jar must hold the listener's class.
     * Throws {@link IllegalArgumentException} when the loader recorded no such jar for {@code modId}, and
     * {@link IllegalStateException} naming the loader version when its records cannot be read.
     */
    public static void listen(String modId, Listener listener) {
        LISTENERS.listen(modId, listener);
    }

    /**
     * A count that grows by one for each class the loader transforms or loads on its thread between
     * {@link Listener#jarLoading} and {@link Listener#jarLoaded}: while it patches a jar, which can take seconds, the
     * game's main thread runs none of its own code, so another thread (the render thread) can read this to show that
     * the loader still works. It does not move between jars, outside passes, while the loader hangs, or for the rest
     * of a pass Zabric cannot follow; it is no total and no fraction. Cheap, any thread, loads no game class.
     */
    public static long heartbeat() {
        return Loader.jarWork();
    }
}
