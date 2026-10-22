package com.dont39.pz.fasterloading.depth;

import com.dont39.pz.fasterloading.hooks.Session;
import zombie.GameWindow;
import zombie.debug.DebugType;
import zombie.fileSystem.FileSystem;
import zombie.fileSystem.FileSystemImpl;
import zombie.tileDepth.TileDepthTextureManager;
import zombie.tileDepth.TileDepthTextures;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reorders the depth-map tasks one {@code TileDepthTextureManager.init} queued, on the game thread before the next
 * pump of the file queue sorts them in by priority.
 */
public final class DepthOrder {
    private DepthOrder() {
    }

    /** The game's count of depth-map tasks not yet done. */
    public static int queued() {
        return TileDepthTextureManager.getInstance().remainingLoadTasks;
    }

    /** Sets the priorities of the tasks queued since {@link #queued} returned {@code before}. */
    public static void reorder(int before) {
        FileSystem fileSystem = GameWindow.fileSystem;
        if (!(fileSystem instanceof FileSystemImpl queue)) {
            throw new IllegalStateException("the game's file system is a " + className(fileSystem) + ", not the "
                    + FileSystemImpl.class.getName() + " whose queue the depth map order reads");
        }
        if (!(queue.executor instanceof ThreadPoolExecutor workers)) {
            throw new IllegalStateException("the file system's workers are a " + className(queue.executor) + ", not a "
                    + ThreadPoolExecutor.class.getName());
        }
        List<TileDepthTextures.LoadTask> loads = unsorted(queue);
        List<Batch.Task> tasks = new ArrayList<>(loads.size());
        for (TileDepthTextures.LoadTask load : loads) {
            TileDepthTextures textures = load.textures;
            tasks.add(new Batch.Task(textures, textures.modId, textures.tilesetRows,
                    load.path.getFileName().toString(), load.priority));
        }
        Batch.Plan plan = Batch.plan(tasks, queued() - before, workers.getCorePoolSize());
        for (int i = 0; i < loads.size(); i++) {
            loads.get(i).setPriority(plan.priorities()[i]);
        }
        if (plan.chains() < 2) {
            DebugType.Mod.println(Session.PREFIX + "depth map order: %d tasks of %d depthmaps folders, left as queued",
                    loads.size(), plan.chains());
        } else {
            DebugType.Mod.println(Session.PREFIX + "depth map order: %d tasks of %d depthmaps folders on %d workers,"
                    + " most work in %s (%d tasks)", loads.size(), plan.chains(), workers.getCorePoolSize(),
                    plan.longestMod(), plan.longestTasks());
        }
    }

    // Other threads queue under the same lock; a throw while holding it would stall the game's file queue for good.
    private static List<TileDepthTextures.LoadTask> unsorted(FileSystemImpl queue) {
        AtomicBoolean lock = queue.lock;
        while (!lock.compareAndSet(false, true)) {
            Thread.onSpinWait();
        }
        List<TileDepthTextures.LoadTask> loads = new ArrayList<>();
        try {
            for (FileSystemImpl.AsyncItem item : queue.added) {
                if (item.task instanceof TileDepthTextures.LoadTask load) {
                    loads.add(load);
                }
            }
        } finally {
            lock.set(false);
        }
        return loads;
    }

    private static String className(Object o) {
        return o == null ? "null" : o.getClass().getName();
    }
}
